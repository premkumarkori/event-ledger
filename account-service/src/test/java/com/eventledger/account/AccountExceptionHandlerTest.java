package com.eventledger.account;

import com.eventledger.account.error.AccountExceptionHandler;
import com.eventledger.account.error.AccountRaceDidNotConvergeException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class AccountExceptionHandlerTest {

    private final AccountExceptionHandler exceptionHandler =
            new AccountExceptionHandler();

    @Test
    void handleRaceThatDidNotConverge_shouldReturnSanitized500_whenRecoveryFails() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        doReturn("/accounts/acct-1/transactions")
                .when(request).getRequestURI();
        AccountRaceDidNotConvergeException exception =
                new AccountRaceDidNotConvergeException(
                        new IllegalStateException("database host and SQL details"));

        ProblemDetail problem = exceptionHandler.handleRaceThatDidNotConverge(
                exception, request);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getType()).isEqualTo(URI.create("urn:event-ledger:problem:internal"));
        assertThat(problem.getTitle()).isEqualTo("Transaction application failed");
        assertThat(problem.getDetail())
                .isEqualTo("Transaction application did not converge; retrying the same eventId is safe")
                .doesNotContain("database", "SQL");
        assertThat(problem.getInstance())
                .isEqualTo(URI.create("/accounts/acct-1/transactions"));
    }
}
