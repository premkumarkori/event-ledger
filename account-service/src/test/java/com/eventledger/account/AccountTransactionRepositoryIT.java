package com.eventledger.account;

import com.eventledger.account.domain.TransactionData;
import com.eventledger.account.domain.TransactionType;
import com.eventledger.account.persistence.AccountEntity;
import com.eventledger.account.persistence.AccountTransactionEntity;
import com.eventledger.account.persistence.AccountTransactionRepository;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AccountTransactionRepositoryIT {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private AccountTransactionRepository transactions;

    @Autowired
    private DataSource dataSource;

    private static final Instant EVENT_TIME = Instant.parse("2026-05-15T14:02:11.123456789Z");
    private static final Instant APPLIED_TIME = Instant.parse("2026-07-14T16:00:00.000100999Z");

    private AccountEntity persistedAccount(String accountId) {
        return entityManager.persistAndFlush(
                new AccountEntity(accountId, "USD", APPLIED_TIME));
    }

    private AccountTransactionEntity transaction(String eventId, String accountId, String amount) {
        return transaction(eventId, accountId, amount, "USD");
    }

    private AccountTransactionEntity transaction(
            String eventId, String accountId, String amount, String currency) {
        TransactionData data = new TransactionData(
                eventId, accountId, TransactionType.CREDIT, new BigDecimal(amount), currency, EVENT_TIME);
        return new AccountTransactionEntity(data, APPLIED_TIME);
    }

    @Test
    void usesIsolatedInMemoryDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:account-test-");
        }
    }

    @Test
    void insertsAccountAndTransaction() {
        persistedAccount("acct-101");
        entityManager.persistAndFlush(transaction("evt-101", "acct-101", "150.00"));
        entityManager.clear();

        List<AccountTransactionEntity> stored =
                transactions.findTop20ByAccountIdOrderByEventTimestampDescEventIdDesc("acct-101");

        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getEventId()).isEqualTo("evt-101");
        assertThat(stored.get(0).getAccountId()).isEqualTo("acct-101");
        assertThat(stored.get(0).getType()).isEqualTo(TransactionType.CREDIT);
        assertThat(stored.get(0).getCurrency()).isEqualTo("USD");
        assertThat(transactions.existsByAccountId("acct-101")).isTrue();
    }

    @Test
    void roundTripsExactDecimalByComparison() {
        persistedAccount("acct-102");
        entityManager.persistAndFlush(transaction("evt-102", "acct-102", "150.0"));
        entityManager.clear();

        BigDecimal stored = transactions.findById("evt-102").orElseThrow().getAmount();

        assertThat(stored).isEqualByComparingTo("150.00");
        assertThat(stored).isEqualByComparingTo(new BigDecimal("150.0"));
    }

    @Test
    void roundTripsMaximumSupportedDecimalWithoutRounding() {
        persistedAccount("acct-max");
        String maximum = "99999999999999999999.123456789012345678";
        entityManager.persistAndFlush(transaction("evt-max", "acct-max", maximum));
        entityManager.clear();

        assertThat(transactions.findById("evt-max").orElseThrow().getAmount())
                .isEqualByComparingTo(maximum);
    }

    @Test
    void amountColumnIsDecimal38x18() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT NUMERIC_PRECISION, NUMERIC_SCALE FROM INFORMATION_SCHEMA.COLUMNS "
                             + "WHERE TABLE_NAME = 'ACCOUNT_TRANSACTIONS' AND COLUMN_NAME = 'AMOUNT'")) {
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt("NUMERIC_PRECISION")).isEqualTo(38);
                assertThat(row.getInt("NUMERIC_SCALE")).isEqualTo(18);
            }
        }
    }

    @Test
    void rejectsDuplicateEventIdAtTheDatabase() {
        persistedAccount("acct-103");
        entityManager.persistAndFlush(transaction("evt-103", "acct-103", "10"));
        entityManager.clear();

        assertThatThrownBy(() ->
                entityManager.persistAndFlush(transaction("evt-103", "acct-103", "10")))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void rejectsTransactionForMissingAccount() {
        assertThat(transactions.existsByAccountId("acct-ghost")).isFalse();

        assertThatThrownBy(() ->
                entityManager.persistAndFlush(transaction("evt-104", "acct-ghost", "10")))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void rejectsTransactionWhoseCurrencyDiffersFromAccount() {
        persistedAccount("acct-currency");

        assertThatThrownBy(() -> entityManager.persistAndFlush(
                transaction("evt-currency", "acct-currency", "10", "EUR")))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void rejectsZeroAmount() {
        persistedAccount("acct-105");

        assertThatThrownBy(() ->
                entityManager.persistAndFlush(transaction("evt-105", "acct-105", "0")))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void roundTripsInstantsUnchanged() {
        persistedAccount("acct-106");
        entityManager.persistAndFlush(transaction("evt-106", "acct-106", "25.75"));
        entityManager.clear();

        AccountTransactionEntity stored = transactions.findById("evt-106").orElseThrow();

        assertThat(stored.getEventTimestamp()).isEqualTo(EVENT_TIME);
        assertThat(stored.getAppliedAt()).isEqualTo(APPLIED_TIME);
    }

    @Test
    void schemaHasNoMutableBalanceColumn() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                             + "WHERE TABLE_NAME IN ('ACCOUNTS', 'ACCOUNT_TRANSACTIONS') "
                             + "AND COLUMN_NAME LIKE '%BALANCE%'")) {
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).isZero();
            }
        }
    }
}
