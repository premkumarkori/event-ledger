package com.eventledger.gateway.api;

import com.eventledger.gateway.domain.EventType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;

public record EventRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$",
                message = "must be a URL-safe identifier of at most 128 characters")
        String eventId,

        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$",
                message = "must be a URL-safe identifier of at most 128 characters")
        String accountId,

        @NotNull
        EventType type,

        @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0")
        BigDecimal amount,

        @NotBlank
        String currency,

        @NotNull
        Instant eventTimestamp,

        JsonNode metadata
) {
}
