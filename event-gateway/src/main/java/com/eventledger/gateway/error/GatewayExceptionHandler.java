package com.eventledger.gateway.error;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
