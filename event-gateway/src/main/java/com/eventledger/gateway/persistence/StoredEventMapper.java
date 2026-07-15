package com.eventledger.gateway.persistence;

import com.eventledger.gateway.domain.StoredEvent;

public final class StoredEventMapper {

    private StoredEventMapper() {
    }

    public static StoredEvent toStoredEvent(StoredEventEntity entity) {
        return new StoredEvent(
                entity.getEventId(),
                entity.getAccountId(),
                entity.getType(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getEventTimestamp(),
                entity.getMetadataJson(),
                entity.getApplicationStatus(),
                entity.getReceivedAt(),
                entity.getLastAttemptAt(),
                entity.getAppliedAt(),
                entity.getAttemptCount(),
                entity.getLastFailureCode(),
                entity.getLastFailureMessage(),
                entity.getVersion());
    }
}
