package com.eventledger.gateway.service;

import com.eventledger.gateway.api.EventRequest;
import com.eventledger.gateway.domain.EventType;
import com.eventledger.gateway.domain.NormalizedEvent;
import com.eventledger.gateway.error.FieldValidationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class EventNormalizer {

    private static final Pattern CURRENCY_PATTERN = Pattern.compile("[A-Za-z]{3}");
    private static final int MAX_INTEGER_DIGITS = 20;
    private static final int MAX_FRACTION_DIGITS = 18;
    private static final String EMPTY_OBJECT = "{}";

    private final JsonMapper jsonMapper;
    private final EventIdentifierValidator identifierValidator;

    public EventNormalizer(JsonMapper jsonMapper, EventIdentifierValidator identifierValidator) {
        this.jsonMapper = jsonMapper;
        this.identifierValidator = identifierValidator;
    }

    public NormalizedEvent normalize(EventRequest request) {
        if (request == null) {
            throw new FieldValidationException("requestBody", "is required");
        }
        identifierValidator.requireValid("eventId", request.eventId());
        identifierValidator.requireValid("accountId", request.accountId());
        requireType(request.type());
        requireRepresentableAmount(request.amount());
        String currency = normalizeCurrency(request.currency());
        requireTimestamp(request.eventTimestamp());
        String metadata = normalizeMetadata(request.metadata());

        return new NormalizedEvent(
                request.eventId(),
                request.accountId(),
                request.type(),
                request.amount(),
                currency,
                request.eventTimestamp(),
                metadata);
    }

    private void requireType(EventType type) {
        if (type == null) {
            throw new FieldValidationException("type", "must be CREDIT or DEBIT");
        }
    }

    private void requireRepresentableAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new FieldValidationException("amount", "must be greater than 0");
        }
        BigDecimal normalized = amount.stripTrailingZeros();
        int fractionDigits = Math.max(normalized.scale(), 0);
        int integerDigits = Math.max(normalized.precision() - normalized.scale(), 0);
        if (fractionDigits > MAX_FRACTION_DIGITS || integerDigits > MAX_INTEGER_DIGITS) {
            throw new FieldValidationException("amount", "must fit decimal(38,18)");
        }
    }

    private String normalizeCurrency(String currency) {
        if (currency == null || !CURRENCY_PATTERN.matcher(currency).matches()) {
            throw new FieldValidationException("currency", "must be exactly three ASCII letters");
        }
        String upper = currency.toUpperCase(Locale.ROOT);
        try {
            Currency.getInstance(upper);
        } catch (IllegalArgumentException unrecognizedCurrency) {
            throw new FieldValidationException("currency",
                    "is not a recognized ISO 4217 currency code");
        }
        return upper;
    }

    private void requireTimestamp(Instant eventTimestamp) {
        if (eventTimestamp == null) {
            throw new FieldValidationException("eventTimestamp", "is required");
        }
    }

    private String normalizeMetadata(JsonNode metadata) {
        if (metadata == null || metadata.isNull()) {
            return EMPTY_OBJECT;
        }
        if (!metadata.isObject()) {
            throw new FieldValidationException("metadata", "must be a JSON object");
        }
        return jsonMapper.writeValueAsString(metadata);
    }
}
