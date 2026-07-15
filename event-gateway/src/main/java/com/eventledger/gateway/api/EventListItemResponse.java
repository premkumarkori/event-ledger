package com.eventledger.gateway.api;

import com.eventledger.gateway.domain.ApplicationStatus;

import java.time.Instant;

public record EventListItemResponse(
        String eventId,
        Instant eventTimestamp,
        ApplicationStatus applicationStatus
) {
}
