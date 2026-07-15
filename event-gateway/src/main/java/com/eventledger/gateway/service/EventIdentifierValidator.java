package com.eventledger.gateway.service;

import com.eventledger.gateway.error.FieldValidationException;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class EventIdentifierValidator {

    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

    public void requireValid(String field, String value) {
        if (value == null || !IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw new FieldValidationException(field,
                    "must be a URL-safe identifier of at most 128 characters");
        }
    }
}
