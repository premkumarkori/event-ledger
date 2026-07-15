package com.eventledger.account;

import com.eventledger.account.api.ApplyTransactionRequest;
import com.eventledger.account.domain.TransactionData;
import com.eventledger.account.domain.TransactionType;
import com.eventledger.account.error.AccountRaceDidNotConvergeException;
import com.eventledger.account.service.AccountCollisionResolver;
import com.eventledger.account.service.AccountTransactionRequestValidator;
import com.eventledger.account.service.AccountTransactionService;
import com.eventledger.account.service.AccountTransactionWriter;
import com.eventledger.account.service.ApplyOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class AccountTransactionServiceTest {

    private static final Instant EVENT_TIME = Instant.parse("2026-05-15T14:02:11Z");
    private static final Instant APPLIED_TIME = Instant.parse("2026-07-14T16:00:00Z");

    @Mock
    private AccountTransactionWriter writer;

    @Mock
    private AccountCollisionResolver collisionResolver;

    private AccountTransactionService transactionService;

    @BeforeEach
    void createTransactionService() {
        transactionService = new AccountTransactionService(
                new AccountTransactionRequestValidator(), writer, collisionResolver);
    }

    private ApplyTransactionRequest request(String amount, String currency) {
        return new ApplyTransactionRequest(
                "evt-1",
                TransactionType.CREDIT,
                new BigDecimal(amount),
                currency,
                EVENT_TIME);
    }

    private ApplyOutcome outcome(boolean newlyApplied) {
        TransactionData transaction = new TransactionData(
                "evt-1",
                "acct-1",
                TransactionType.CREDIT,
                new BigDecimal("150.00"),
                "USD",
                EVENT_TIME);
        return new ApplyOutcome(transaction, APPLIED_TIME, newlyApplied);
    }

    @Test
    void applyTransaction_shouldReturnWriterOutcome_whenFirstAttemptSucceeds() {
        ApplyOutcome expected = outcome(true);
        doReturn(expected).when(writer).applyOnce(any());

        ApplyOutcome actual = transactionService.applyTransaction(
                "acct-1", request("150.00", "USD"));

        assertThat(actual).isSameAs(expected);
        verify(writer, times(1)).applyOnce(any());
        verifyNoInteractions(collisionResolver);
    }

    @Test
    void applyTransaction_shouldReturnReplay_whenUniqueCollisionFindsExistingEvent() {
        ApplyOutcome replay = outcome(false);
        doThrow(new DataIntegrityViolationException("duplicate"))
                .when(writer).applyOnce(any());
        doReturn(Optional.of(replay))
                .when(collisionResolver).resolveAfterFailedInsert(any());

        ApplyOutcome actual = transactionService.applyTransaction(
                "acct-1", request("150.00", "USD"));

        assertThat(actual).isSameAs(replay);
        verify(writer, times(1)).applyOnce(any());
        verify(collisionResolver, times(1)).resolveAfterFailedInsert(any());
    }

    @Test
    void applyTransaction_shouldResolveCollision_whenFirstAttemptCannotAcquireLock() {
        ApplyOutcome replay = outcome(false);
        doThrow(new CannotAcquireLockException("lock timeout"))
                .when(writer).applyOnce(any());
        doReturn(Optional.of(replay))
                .when(collisionResolver).resolveAfterFailedInsert(any());

        ApplyOutcome actual = transactionService.applyTransaction(
                "acct-1", request("150.00", "USD"));

        assertThat(actual).isSameAs(replay);
        verify(collisionResolver, times(1)).resolveAfterFailedInsert(any());
    }

    @Test
    void applyTransaction_shouldRetryExactlyOnce_whenAccountCollisionHasMatchingCurrency() {
        ApplyOutcome applied = outcome(true);
        doThrow(new DataIntegrityViolationException("account race"))
                .doReturn(applied)
                .when(writer).applyOnce(any());
        doReturn(Optional.empty())
                .when(collisionResolver).resolveAfterFailedInsert(any());

        ApplyOutcome actual = transactionService.applyTransaction(
                "acct-1", request("150.00", "USD"));

        assertThat(actual).isSameAs(applied);
        verify(writer, times(2)).applyOnce(any());
        verify(collisionResolver, times(1)).resolveAfterFailedInsert(any());
    }

    @Test
    void applyTransaction_shouldLogFailureAndThrowRaceError_whenBoundedRetryFails(CapturedOutput output) {
        DataIntegrityViolationException secondFailure =
                new DataIntegrityViolationException("second collision");
        doThrow(new DataIntegrityViolationException("first collision"))
                .doThrow(secondFailure)
                .when(writer).applyOnce(any());
        doReturn(Optional.empty())
                .when(collisionResolver).resolveAfterFailedInsert(any());

        assertThatThrownBy(() -> transactionService.applyTransaction(
                "acct-1", request("150.00", "USD")))
                .isInstanceOf(AccountRaceDidNotConvergeException.class)
                .hasCause(secondFailure);
        verify(writer, times(2)).applyOnce(any());
        assertThat(output)
                .contains("Account transaction completed")
                .contains("outcome")
                .contains("\"FAILED\"");
    }

}
