package com.eventledger.account;

import com.eventledger.account.api.ApplyTransactionRequest;
import com.eventledger.account.domain.TransactionType;
import com.eventledger.account.error.CurrencyConflictException;
import com.eventledger.account.error.IdempotencyConflictException;
import com.eventledger.account.persistence.AccountEntity;
import com.eventledger.account.persistence.AccountRepository;
import com.eventledger.account.persistence.AccountTransactionEntity;
import com.eventledger.account.persistence.AccountTransactionRepository;
import com.eventledger.account.service.AccountTransactionService;
import com.eventledger.account.service.ApplyOutcome;
import com.eventledger.account.support.MutableTestClock;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AccountConcurrencyTest {

    private static final int RACE_REPETITIONS = 40;
    private static final long CALLER_TIMEOUT_SECONDS = 10;
    private static final Instant EVENT_TIME = Instant.parse("2026-05-15T14:02:11.123456789Z");
    private static final Instant FIXED_APPLY_TIME = Instant.parse("2026-07-14T16:00:00Z");

    @TestConfiguration
    static class TestClockConfiguration {

        @Bean
        @Primary
        MutableTestClock testClock() {
            return new MutableTestClock(FIXED_APPLY_TIME);
        }
    }

    @Autowired
    private AccountTransactionService service;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountTransactionRepository transactions;

    @RepeatedTest(RACE_REPETITIONS)
    void applyTransaction_shouldStoreOneEffect_whenIdenticalEventsRaceConcurrently() {
        resetDatabase();

        List<Attempt> attempts = runConcurrently(List.of(
                applyCall("acct-1", "evt-1", TransactionType.CREDIT, "150.00", "USD"),
                applyCall("acct-1", "evt-1", TransactionType.CREDIT, "150.00", "USD"),
                applyCall("acct-1", "evt-1", TransactionType.CREDIT, "150.00", "USD")));

        assertThat(attempts).allSatisfy(attempt -> assertThat(attempt.succeeded())
                .as("identical concurrent events must converge, but a caller failed with %s",
                        attempt.failure())
                .isTrue());
        long createdOutcomes = attempts.stream()
                .filter(attempt -> attempt.outcome().newlyApplied())
                .count();
        assertThat(createdOutcomes)
                .as("only the caller that inserts the row reports a new transaction")
                .isEqualTo(1);
        assertThat(transactions.count())
                .as("one identical event yields exactly one stored transaction")
                .isEqualTo(1);
        assertThat(accounts.count()).isEqualTo(1);
        AccountTransactionEntity stored = transactions.findById("evt-1").orElseThrow();
        assertThat(stored.getAmount()).isEqualByComparingTo("150.00");
        assertThat(stored.getCurrency()).isEqualTo("USD");
    }

    @RepeatedTest(RACE_REPETITIONS)
    void applyTransaction_shouldRejectChangedPayload_whenSameEventIdRacesConcurrently() {
        resetDatabase();

        List<Attempt> attempts = runConcurrently(List.of(
                applyCall("acct-1", "evt-1", TransactionType.CREDIT, "150.00", "USD"),
                applyCall("acct-1", "evt-1", TransactionType.DEBIT, "40.00", "USD")));

        List<Attempt> applied = attempts.stream().filter(Attempt::succeeded).toList();
        List<Attempt> rejected = attempts.stream().filter(attempt -> !attempt.succeeded()).toList();
        assertThat(applied).as("one payload establishes the event").hasSize(1);
        assertThat(applied.get(0).outcome().newlyApplied()).isTrue();
        assertThat(rejected).as("the changed payload conflicts").hasSize(1);
        assertThat(rejected.get(0).failure()).isInstanceOf(IdempotencyConflictException.class);

        assertThat(accounts.count()).isEqualTo(1);
        assertThat(transactions.count()).isEqualTo(1);
        AccountTransactionEntity stored = transactions.findById("evt-1").orElseThrow();
        ApplyOutcome winner = applied.get(0).outcome();
        assertThat(stored.getAccountId()).isEqualTo(winner.transaction().accountId());
        assertThat(stored.getType()).isEqualTo(winner.transaction().type());
        assertThat(stored.getAmount()).isEqualByComparingTo(winner.transaction().amount());
        assertThat(stored.getCurrency()).isEqualTo(winner.transaction().currency());
        assertThat(stored.getEventTimestamp()).isEqualTo(winner.transaction().eventTimestamp());
    }

    @RepeatedTest(RACE_REPETITIONS)
    void applyTransaction_shouldEstablishOneCurrencyAndConflictTheOther_whenDifferentCurrenciesRaceForOneAccount() {
        resetDatabase();

        List<Attempt> attempts = runConcurrently(List.of(
                applyCall("acct-1", "evt-usd", TransactionType.CREDIT, "150.00", "USD"),
                applyCall("acct-1", "evt-eur", TransactionType.CREDIT, "150.00", "EUR")));

        List<Attempt> applied = attempts.stream().filter(Attempt::succeeded).toList();
        List<Attempt> rejected = attempts.stream().filter(attempt -> !attempt.succeeded()).toList();
        assertThat(applied).as("exactly one currency wins the account").hasSize(1);
        assertThat(rejected).hasSize(1);
        assertThat(rejected.get(0).failure()).isInstanceOf(CurrencyConflictException.class);

        assertThat(accounts.count()).isEqualTo(1);
        assertThat(transactions.count()).isEqualTo(1);
        AccountEntity account = accounts.findAll().get(0);
        AccountTransactionEntity stored = transactions.findAll().get(0);
        assertThat(stored.getCurrency())
                .as("the only stored transaction matches the established account currency")
                .isEqualTo(account.getCurrency());
    }

    @RepeatedTest(RACE_REPETITIONS)
    void applyTransaction_shouldApplyBothEventsUnderOneAccount_whenSameCurrencyEventsRaceForNewAccount() {
        resetDatabase();

        List<Attempt> attempts = runConcurrently(List.of(
                applyCall("acct-1", "evt-a", TransactionType.CREDIT, "150.00", "USD"),
                applyCall("acct-1", "evt-b", TransactionType.DEBIT, "40.00", "USD")));

        assertThat(attempts).allSatisfy(attempt -> assertThat(attempt.succeeded())
                .as("same-currency events must both apply, but a caller failed with %s",
                        attempt.failure())
                .isTrue());
        assertThat(attempts).allSatisfy(attempt -> assertThat(attempt.outcome().newlyApplied())
                .as("each distinct event is a new effect, not a replay")
                .isTrue());
        assertThat(accounts.count()).isEqualTo(1);
        assertThat(transactions.count()).isEqualTo(2);
        assertThat(accounts.findAll().get(0).getCurrency()).isEqualTo("USD");
        assertThat(transactions.findAll())
                .allSatisfy(stored -> assertThat(stored.getCurrency()).isEqualTo("USD"));
        assertThat(transactions.findById("evt-a")).isPresent();
        assertThat(transactions.findById("evt-b")).isPresent();
    }

    private Callable<ApplyOutcome> applyCall(
            String accountId, String eventId, TransactionType type, String amount, String currency) {
        ApplyTransactionRequest request = new ApplyTransactionRequest(
                eventId, type, new BigDecimal(amount), currency, EVENT_TIME);
        return () -> service.applyTransaction(accountId, request);
    }

    private List<Attempt> runConcurrently(List<Callable<ApplyOutcome>> calls) {
        int callerCount = calls.size();
        ExecutorService pool = Executors.newFixedThreadPool(callerCount);
        CyclicBarrier startGate = new CyclicBarrier(callerCount);
        try {
            List<Future<ApplyOutcome>> futures = new ArrayList<>();
            for (Callable<ApplyOutcome> call : calls) {
                futures.add(pool.submit(() -> {
                    startGate.await(CALLER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    return call.call();
                }));
            }
            List<Attempt> attempts = new ArrayList<>();
            for (Future<ApplyOutcome> future : futures) {
                attempts.add(awaitAttempt(future));
            }
            return attempts;
        } finally {
            pool.shutdownNow();
        }
    }

    private Attempt awaitAttempt(Future<ApplyOutcome> future) {
        try {
            return Attempt.succeeded(future.get(CALLER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (ExecutionException executionFailure) {
            return Attempt.failed(executionFailure.getCause());
        } catch (TimeoutException hang) {
            future.cancel(true);
            return fail("a concurrent apply did not finish within " + CALLER_TIMEOUT_SECONDS
                    + "s; possible spin or deadlock");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return fail("interrupted while awaiting a concurrent apply");
        }
    }

    private void resetDatabase() {
        transactions.deleteAll();
        accounts.deleteAll();
    }

    private record Attempt(ApplyOutcome outcome, Throwable failure) {

        static Attempt succeeded(ApplyOutcome outcome) {
            return new Attempt(outcome, null);
        }

        static Attempt failed(Throwable failure) {
            return new Attempt(null, failure);
        }

        boolean succeeded() {
            return failure == null;
        }
    }
}
