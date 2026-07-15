package com.eventledger.gateway.error;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.core.JacksonException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestControllerAdvice
public class GatewayExceptionHandler {

    private static final Set<String> REQUEST_FIELDS = Set.of(
            "eventId", "accountId", "type", "amount", "currency", "eventTimestamp", "metadata");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", safeMessage(error.getDefaultMessage())))
                .toList();
        return validationProblem(errors, request);
    }

    @ExceptionHandler(FieldValidationException.class)
    public ProblemDetail handleFieldValidation(
            FieldValidationException exception, HttpServletRequest request) {
        return validationProblem(
                List.of(Map.of("field", exception.getField(), "message", exception.getMessage())),
                request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableRequest(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        return validationProblem(
                List.of(Map.of("field", unreadableField(exception),
                        "message", "is malformed or contains a value with an invalid format")),
                request);
    }

    @ExceptionHandler(EventNotFoundException.class)
    public ProblemDetail handleEventNotFound(HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, "The requested event does not exist");
        problem.setType(URI.create("urn:event-ledger:problem:not-found"));
        problem.setTitle("Event not found");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleAccountNotFound(HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, "The requested account does not exist");
        problem.setType(URI.create("urn:event-ledger:problem:not-found"));
        problem.setTitle("Account not found");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    @ExceptionHandler(AccountQueryUnavailableException.class)
    public ProblemDetail handleAccountQueryUnavailable(HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Account balance is temporarily unavailable.");
        problem.setType(URI.create("urn:event-ledger:problem:account-unavailable"));
        problem.setTitle("Account Service unavailable");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(HttpServletRequest request) {
        return conflictProblem("urn:event-ledger:problem:idempotency-conflict",
                "Event identifier conflict",
                "The eventId already belongs to a different event", request);
    }

    @ExceptionHandler(CurrencyConflictException.class)
    public ProblemDetail handleCurrencyConflict(HttpServletRequest request) {
        return conflictProblem("urn:event-ledger:problem:currency-conflict",
                "Account currency conflict",
                "The event conflicts with the established account currency", request);
    }

    @ExceptionHandler(DownstreamContractException.class)
    public ProblemDetail handleDownstreamContract(HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "The Account Service returned an invalid response");
        problem.setType(URI.create("urn:event-ledger:problem:downstream-contract"));
        problem.setTitle("Account Service contract error");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    @ExceptionHandler(AccountUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleAccountUnavailable(
            AccountUnavailableException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Application of event " + exception.getEventId()
                        + " could not be confirmed. Retrying the same eventId is safe.");
        problem.setType(URI.create("urn:event-ledger:problem:account-unavailable"));
        problem.setTitle("Account Service unavailable");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("eventId", exception.getEventId());
        problem.setProperty("applicationStatus", "APPLY_FAILED");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(problem);
    }

    private ProblemDetail conflictProblem(
            String type, String title, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
        problem.setType(URI.create(type));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    private String unreadableField(HttpMessageNotReadableException exception) {
        if (!(exception.getCause() instanceof JacksonException jacksonException)
                || jacksonException.getPath().isEmpty()) {
            return "requestBody";
        }

        List<JacksonException.Reference> path = jacksonException.getPath();
        String field = path.get(path.size() - 1).getPropertyName();
        return field != null && REQUEST_FIELDS.contains(field) ? field : "requestBody";
    }

    private ProblemDetail validationProblem(
            List<Map<String, String>> errors, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request has " + errors.size() + " invalid field(s)");
        problem.setType(URI.create("urn:event-ledger:problem:validation"));
        problem.setTitle("Validation failed");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errors", errors);
        return problem;
    }

    private String safeMessage(String message) {
        return message == null ? "is invalid" : message;
    }
}
