package com.eventledger.account;

import com.eventledger.account.api.AccountDetailsResponse;
import com.eventledger.account.api.BalanceResponse;
import com.eventledger.account.api.TransactionSummaryResponse;
import com.eventledger.account.domain.TransactionData;
import com.eventledger.account.domain.TransactionType;
import com.eventledger.account.error.AccountNotFoundException;
import com.eventledger.account.persistence.AccountEntity;
import com.eventledger.account.persistence.AccountRepository;
import com.eventledger.account.persistence.AccountTransactionEntity;
import com.eventledger.account.persistence.AccountTransactionRepository;
import com.eventledger.account.service.AccountQueryService;
import com.eventledger.account.support.MutableTestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AccountQueryIT {

    private static final Instant BASE_TIME = Instant.parse("2026-05-15T14:00:00Z");
    private static final Instant QUERY_TIME = Instant.parse("2026-07-15T09:30:00Z");

    @TestConfiguration
    static class TestClockConfiguration {

        @Bean
        @Primary
        MutableTestClock testClock() {
            return new MutableTestClock(QUERY_TIME);
        }
    }

    @Autowired
    private AccountQueryService queryService;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountTransactionRepository transactions;

    @BeforeEach
    void resetDatabase() {
        transactions.deleteAll();
        accounts.deleteAll();
    }

    private void seedAccount(String accountId, String currency) {
        accounts.save(new AccountEntity(accountId, currency, BASE_TIME));
    }

    private void seedTransaction(
            String accountId, String eventId, TransactionType type, String amount, Instant eventTime) {
        TransactionData data = new TransactionData(
                eventId, accountId, type, new BigDecimal(amount), "USD", eventTime);
        transactions.save(new AccountTransactionEntity(data, BASE_TIME));
    }

    @Test
    void getBalance_shouldReturnCreditMinusDebit_whenAccountHasTransactions() {
        seedAccount("acct-1", "USD");
        seedTransaction("acct-1", "evt-1", TransactionType.CREDIT, "150.00", BASE_TIME);
        seedTransaction("acct-1", "evt-2", TransactionType.DEBIT, "29.50", BASE_TIME.plusSeconds(60));

        BalanceResponse balance = queryService.getBalance("acct-1");

        assertThat(balance.accountId()).isEqualTo("acct-1");
        assertThat(balance.currency()).isEqualTo("USD");
        assertThat(balance.balance()).isEqualByComparingTo("120.50");
        assertThat(balance.asOf()).isEqualTo(QUERY_TIME);
    }

    @Test
    void getBalance_shouldRemainExact_whenDecimalScalesDiffer() {
        seedAccount("acct-1", "USD");
        seedTransaction("acct-1", "evt-1", TransactionType.CREDIT, "100.1", BASE_TIME);
        seedTransaction("acct-1", "evt-2", TransactionType.CREDIT, "0.020", BASE_TIME.plusSeconds(1));
        seedTransaction("acct-1", "evt-3", TransactionType.DEBIT, "0.003", BASE_TIME.plusSeconds(2));

        assertThat(queryService.getBalance("acct-1").balance()).isEqualByComparingTo("100.117");
    }

    @Test
    void getBalance_shouldReturnNegative_whenDebitsExceedCredits() {
        seedAccount("acct-1", "USD");
        seedTransaction("acct-1", "evt-1", TransactionType.CREDIT, "10.00", BASE_TIME);
        seedTransaction("acct-1", "evt-2", TransactionType.DEBIT, "25.00", BASE_TIME.plusSeconds(1));

        assertThat(queryService.getBalance("acct-1").balance()).isEqualByComparingTo("-15.00");
    }

    @Test
    void getBalance_shouldBeIndependentOfArrivalOrder_whenSameTransactionsInsertedInDifferentOrder() {
        seedAccount("acct-forward", "USD");
        seedTransaction("acct-forward", "f-1", TransactionType.CREDIT, "5.00", BASE_TIME);
        seedTransaction("acct-forward", "f-2", TransactionType.DEBIT, "2.00", BASE_TIME.plusSeconds(1));
        seedTransaction("acct-forward", "f-3", TransactionType.CREDIT, "3.00", BASE_TIME.plusSeconds(2));

        seedAccount("acct-reversed", "USD");
        seedTransaction("acct-reversed", "r-3", TransactionType.CREDIT, "3.00", BASE_TIME.plusSeconds(2));
        seedTransaction("acct-reversed", "r-2", TransactionType.DEBIT, "2.00", BASE_TIME.plusSeconds(1));
        seedTransaction("acct-reversed", "r-1", TransactionType.CREDIT, "5.00", BASE_TIME);

        BigDecimal forward = queryService.getBalance("acct-forward").balance();
        BigDecimal reversed = queryService.getBalance("acct-reversed").balance();

        assertThat(forward).isEqualByComparingTo("6.00");
        assertThat(reversed).isEqualByComparingTo(forward);
    }

    @Test
    void getDetails_shouldReturnExactlyTwentyNewest_whenMoreThanTwentyTransactions() {
        seedAccount("acct-1", "USD");
        for (int i = 1; i <= 25; i++) {
            seedTransaction("acct-1", "evt-" + i, TransactionType.CREDIT, "1.00", BASE_TIME.plusSeconds(i));
        }

        AccountDetailsResponse details = queryService.getDetails("acct-1");

        assertThat(details.recentTransactions()).hasSize(20);
        List<String> expectedEventIds = new ArrayList<>();
        for (int i = 25; i >= 6; i--) {
            expectedEventIds.add("evt-" + i);
        }
        assertThat(details.recentTransactions())
                .extracting(TransactionSummaryResponse::eventId)
                .containsExactlyElementsOf(expectedEventIds);
        assertThat(details.balance()).isEqualByComparingTo("25.00");
    }

    @Test
    void getDetails_shouldOrderByEventTimestampDescThenEventIdDesc_whenTimestampsMixAndTie() {
        seedAccount("acct-1", "USD");
        seedTransaction("acct-1", "evt-a", TransactionType.CREDIT, "1.00", BASE_TIME.plusSeconds(10));
        seedTransaction("acct-1", "evt-b", TransactionType.CREDIT, "1.00", BASE_TIME.plusSeconds(20));
        seedTransaction("acct-1", "evt-c", TransactionType.CREDIT, "1.00", BASE_TIME.plusSeconds(20));

        List<String> orderedEventIds = queryService.getDetails("acct-1").recentTransactions().stream()
                .map(TransactionSummaryResponse::eventId)
                .toList();

        assertThat(orderedEventIds).containsExactly("evt-c", "evt-b", "evt-a");
    }

    @Test
    void getDetails_shouldExposeContractSummaryFields_whenAccountHasTransactions() {
        seedAccount("acct-1", "USD");
        seedTransaction("acct-1", "evt-1", TransactionType.DEBIT, "24.50", BASE_TIME.plusSeconds(5));

        TransactionSummaryResponse summary = queryService.getDetails("acct-1").recentTransactions().get(0);

        assertThat(summary.eventId()).isEqualTo("evt-1");
        assertThat(summary.type()).isEqualTo(TransactionType.DEBIT);
        assertThat(summary.amount()).isEqualByComparingTo("24.50");
        assertThat(summary.eventTimestamp()).isEqualTo(BASE_TIME.plusSeconds(5));
    }

    @Test
    void getBalance_shouldThrowAccountNotFound_whenAccountUnknown() {
        assertThatThrownBy(() -> queryService.getBalance("acct-missing"))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void getDetails_shouldThrowAccountNotFound_whenAccountUnknown() {
        assertThatThrownBy(() -> queryService.getDetails("acct-missing"))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void getAccountQueries_shouldNotMutateStoredRows_whenAccountExists() {
        seedAccount("acct-1", "USD");
        seedTransaction("acct-1", "evt-1", TransactionType.CREDIT, "10.00", BASE_TIME);
        long accountsBefore = accounts.count();
        long transactionsBefore = transactions.count();

        queryService.getBalance("acct-1");
        queryService.getDetails("acct-1");

        assertThat(accounts.count()).isEqualTo(accountsBefore);
        assertThat(transactions.count()).isEqualTo(transactionsBefore);
    }
}
