package com.eventledger.account.error;

import java.util.List;

public class AccountRequestValidationException extends RuntimeException {

    public record InvalidField(String field, String message) {
    }

    private final List<InvalidField> errors;

    public AccountRequestValidationException(List<InvalidField> errors) {
        super("Request has " + errors.size() + " invalid field(s)");
        this.errors = List.copyOf(errors);
    }

    public List<InvalidField> getErrors() {
        return errors;
    }
}
