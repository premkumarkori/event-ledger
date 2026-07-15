package com.eventledger.account.service;

import com.eventledger.account.api.ApplyTransactionRequest;
import com.eventledger.account.domain.TransactionData;
import com.eventledger.account.error.AccountRequestValidationException;
import com.eventledger.account.error.AccountRequestValidationException.InvalidField;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class AccountTransactionRequestValidator {

    private static final Pattern IDENTIFIER =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Za-z]{3}");
    private static final int MAX_INTEGER_DIGITS = 20;
    private static final int MAX_FRACTION_DIGITS = 18;

    public TransactionData validateAndNormalize(
            String accountId, ApplyTransactionRequest request) {
        List<InvalidField> errors = new ArrayList<>();

        validateIdentifier("accountId", accountId, errors);
        validateIdentifier("eventId", request.eventId(), errors);
        validateRequiredField("type", request.type(), errors);
        validateAmount(request.amount(), errors);
        String currency = normalizeCurrency(request.currency(), errors);
        validateRequiredField("eventTimestamp", request.eventTimestamp(), errors);
        throwIfRequestIsInvalid(errors);

        return new TransactionData(
                request.eventId(),
                accountId,
                request.type(),
                request.amount(),
                currency,
                request.eventTimestamp());
    }

    private void validateIdentifier(
            String field, String value, List<InvalidField> errors) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            errors.add(new InvalidField(field,
                    "must be a URL-safe identifier of at most 128 characters"));
        }
    }

    private void validateRequiredField(
            String field, Object value, List<InvalidField> errors) {
        if (value == null) {
            errors.add(new InvalidField(field, "must not be null"));
        }
    }

    private void validateAmount(BigDecimal amount, List<InvalidField> errors) {
        if (amount == null) {
            errors.add(new InvalidField("amount", "must not be null"));
            return;
        }
        if (amount.signum() <= 0) {
            errors.add(new InvalidField("amount", "must be greater than 0"));
            return;
        }

        BigDecimal stripped = amount.stripTrailingZeros();
        int integerDigits = Math.max(stripped.precision() - stripped.scale(), 0);
        int fractionDigits = Math.max(stripped.scale(), 0);
        if (integerDigits > MAX_INTEGER_DIGITS) {
            errors.add(new InvalidField("amount", "must have at most 20 integer digits"));
        }
        if (fractionDigits > MAX_FRACTION_DIGITS) {
            errors.add(new InvalidField("amount", "must have at most 18 fractional digits"));
        }
    }

    private String normalizeCurrency(String currency, List<InvalidField> errors) {
        if (currency == null || !CURRENCY_CODE.matcher(currency).matches()) {
            errors.add(new InvalidField("currency", "must be exactly three ASCII letters"));
            return currency;
        }

        String normalized = currency.toUpperCase(Locale.ROOT);
        try {
            Currency.getInstance(normalized);
        } catch (IllegalArgumentException unrecognizedCurrency) {
            errors.add(new InvalidField(
                    "currency", "is not a recognized ISO 4217 currency code"));
        }
        return normalized;
    }

    private void throwIfRequestIsInvalid(List<InvalidField> errors) {
        if (!errors.isEmpty()) {
            throw new AccountRequestValidationException(errors);
        }
    }
}
