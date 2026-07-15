package com.eventledger.account;

import com.eventledger.account.domain.TransactionData;
import com.eventledger.account.domain.TransactionType;
import com.eventledger.account.error.AccountRaceDidNotConvergeException;
import com.eventledger.account.error.CurrencyConflictException;
import com.eventledger.account.error.IdempotencyConflictException;
import com.eventledger.account.persistence.AccountEntity;
import com.eventledger.account.persistence.AccountRepository;
import com.eventledger.account.persistence.AccountTransactionEntity;
import com.eventledger.account.persistence.AccountTransactionRepository;
import com.eventledger.account.service.AccountCollisionResolver;
import com.eventledger.account.service.AccountTransactionWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AccountTransactionPersistenceIT {

    private static final Instant EVENT_TIME =
            Instant.parse("2026-05-15T14:02:11.123456789Z");
    private static final Instant APPLIED_TIME =
            Instant.parse("2026-07-14T16:00:00Z");

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountTransactionRepository transactions;

    @Autowired
    private AccountTransactionWriter writer;

    @Autowired
    private AccountCollisionResolver collisionResolver;

    @BeforeEach
    void resetDatabase() {
        transactions.deleteAll();
        accounts.deleteAll();
    }

    @Test
    void applyOnce_shouldRollBackNewAccount_whenTransactionInsertFails() {
        TransactionData invalidTransaction = new TransactionData(
                "evt-broken",
                "acct-atomic",
                TransactionType.CREDIT,
                BigDecimal.ZERO,
                "USD",
                EVENT_TIME);

        assertThatThrownBy(() -> writer.applyOnce(invalidTransaction))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(accounts.findById("acct-atomic")).isEmpty();
        assertThat(transactions.findById("evt-broken")).isEmpty();
    }

    @Test
    void resolveAfterFailedInsert_shouldReturnReplay_whenEventIsIdentical() {
        TransactionData transaction = transaction(
                "evt-1", "acct-1", TransactionType.CREDIT, "150.00", "USD");
        save(transaction);

        assertThat(collisionResolver.resolveAfterFailedInsert(transaction))
                .hasValueSatisfying(outcome -> {
                    assertThat(outcome.newlyApplied()).isFalse();
                    assertThat(outcome.transaction().eventId()).isEqualTo("evt-1");
                });
    }

    @Test
    void resolveAfterFailedInsert_shouldThrowIdempotencyConflict_whenEventDiffers() {
        TransactionData stored = transaction(
                "evt-1", "acct-1", TransactionType.CREDIT, "150.00", "USD");
        save(stored);
        TransactionData changed = transaction(
                "evt-1", "acct-1", TransactionType.DEBIT, "150.00", "USD");

        assertThatThrownBy(() -> collisionResolver.resolveAfterFailedInsert(changed))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void resolveAfterFailedInsert_shouldThrowCurrencyConflict_whenAccountCurrencyDiffers() {
        accounts.save(new AccountEntity("acct-1", "USD", APPLIED_TIME));
        TransactionData euroTransaction = transaction(
                "evt-2", "acct-1", TransactionType.CREDIT, "10.00", "EUR");

        assertThatThrownBy(() ->
                collisionResolver.resolveAfterFailedInsert(euroTransaction))
                .isInstanceOf(CurrencyConflictException.class);
    }

    @Test
    void resolveAfterFailedInsert_shouldPermitRetry_whenAccountCurrencyMatches() {
        accounts.save(new AccountEntity("acct-1", "USD", APPLIED_TIME));
        TransactionData transaction = transaction(
                "evt-2", "acct-1", TransactionType.CREDIT, "10.00", "USD");

        assertThat(collisionResolver.resolveAfterFailedInsert(transaction)).isEmpty();
    }

    @Test
    void resolveAfterFailedInsert_shouldThrowRaceError_whenAccountIsMissing() {
        TransactionData transaction = transaction(
                "evt-2", "acct-missing", TransactionType.CREDIT, "10.00", "USD");

        assertThatThrownBy(() -> collisionResolver.resolveAfterFailedInsert(transaction))
                .isInstanceOf(AccountRaceDidNotConvergeException.class);
    }

    private TransactionData transaction(
            String eventId,
            String accountId,
            TransactionType type,
            String amount,
            String currency) {
        return new TransactionData(
                eventId,
                accountId,
                type,
                new BigDecimal(amount),
                currency,
                EVENT_TIME);
    }

    private void save(TransactionData transaction) {
        accounts.save(new AccountEntity(
                transaction.accountId(), transaction.currency(), APPLIED_TIME));
        transactions.save(new AccountTransactionEntity(transaction, APPLIED_TIME));
    }
}
