package com.eventledger.account.error;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class AccountExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        return createValidationProblem(errors, request);
    }

    @ExceptionHandler(AccountRequestValidationException.class)
    public ProblemDetail handleRequestValidation(
            AccountRequestValidationException exception,
            HttpServletRequest request) {
        List<Map<String, String>> errors = exception.getErrors().stream()
                .map(error -> Map.of("field", error.field(), "message", error.message()))
                .toList();
        return createValidationProblem(errors, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableRequestBody(HttpServletRequest request) {
        return createValidationProblem(
                List.of(Map.of("field", "requestBody",
                        "message", "is malformed or contains a value with an invalid format")),
                request);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(
            IdempotencyConflictException exception,
            HttpServletRequest request) {
        return createProblem(HttpStatus.CONFLICT, "urn:event-ledger:problem:idempotency-conflict",
                "Event identifier conflict", exception.getMessage(), request);
    }

    @ExceptionHandler(CurrencyConflictException.class)
    public ProblemDetail handleCurrencyConflict(
            CurrencyConflictException exception,
            HttpServletRequest request) {
        return createProblem(HttpStatus.CONFLICT, "urn:event-ledger:problem:currency-conflict",
                "Account currency conflict", exception.getMessage(), request);
    }

    @ExceptionHandler(AccountRaceDidNotConvergeException.class)
    public ProblemDetail handleRaceThatDidNotConverge(
            AccountRaceDidNotConvergeException exception,
            HttpServletRequest request) {
        return createProblem(HttpStatus.INTERNAL_SERVER_ERROR, "urn:event-ledger:problem:internal",
                "Transaction application failed", exception.getMessage(), request);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleAccountNotFound(
            AccountNotFoundException exception,
            HttpServletRequest request) {
        return createProblem(HttpStatus.NOT_FOUND, "urn:event-ledger:problem:not-found",
                "Account not found", exception.getMessage(), request);
    }

    private ProblemDetail createValidationProblem(
            List<Map<String, String>> errors,
            HttpServletRequest request) {
        ProblemDetail problem = createProblem(HttpStatus.BAD_REQUEST,
                "urn:event-ledger:problem:validation", "Validation failed",
                "Request has " + errors.size() + " invalid field(s)", request);
        problem.setProperty("errors", errors);
        return problem;
    }

    private ProblemDetail createProblem(
            HttpStatus status,
            String type,
            String title,
            String detail,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(type));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
