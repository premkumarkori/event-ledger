package com.eventledger.account.api;

import com.eventledger.account.domain.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;

public record ApplyTransactionRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$",
                message = "must be a URL-safe identifier of at most 128 characters")
        String eventId,

        @NotNull
        TransactionType type,

        @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0")
        BigDecimal amount,

        @NotBlank
        @Pattern(regexp = "[A-Za-z]{3}", message = "must be exactly three ASCII letters")
        String currency,

        @NotNull
        Instant eventTimestamp
) {
}
