package com.eventledger.gateway.api;

import com.eventledger.gateway.domain.ApplicationStatus;
import com.eventledger.gateway.domain.EventType;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;

public record EventResponse(
        String eventId,
        String accountId,
        EventType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        JsonNode metadata,
        ApplicationStatus applicationStatus,
        Instant receivedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant appliedAt
) {
}
