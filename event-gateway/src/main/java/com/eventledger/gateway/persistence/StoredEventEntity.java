package com.eventledger.gateway.persistence;

import com.eventledger.gateway.domain.ApplicationStatus;
import com.eventledger.gateway.domain.EventType;
import com.eventledger.gateway.domain.LastFailureCode;
import com.eventledger.gateway.domain.NormalizedEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "gateway_events")
public class StoredEventEntity {

    @Id
    @Column(name = "event_id", length = 128)
    private String eventId;

    @Column(name = "account_id", length = 128, nullable = false, updatable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 10, nullable = false, updatable = false)
    private EventType type;

    @Column(name = "amount", precision = 38, scale = 18, nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    private String currency;

    @Column(name = "event_timestamp", nullable = false, updatable = false)
    private Instant eventTimestamp;

    @Lob
    @Column(name = "metadata_json", nullable = false, updatable = false)
    private String metadataJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "application_status", length = 20, nullable = false)
    private ApplicationStatus applicationStatus;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_failure_code", length = 40)
    private LastFailureCode lastFailureCode;

    @Column(name = "last_failure_message", length = 300)
    private String lastFailureMessage;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected StoredEventEntity() {
    }

    public StoredEventEntity(NormalizedEvent event, Instant receivedAt) {
        this.eventId = event.eventId();
        this.accountId = event.accountId();
        this.type = event.type();
        this.amount = event.amount();
        this.currency = event.currency();
        this.eventTimestamp = event.eventTimestamp();
        this.metadataJson = event.metadata();
        this.applicationStatus = ApplicationStatus.RECEIVED;
        this.receivedAt = receivedAt;
        this.attemptCount = 0;
        this.version = 0;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAccountId() {
        return accountId;
    }

    public EventType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getEventTimestamp() {
        return eventTimestamp;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public ApplicationStatus getApplicationStatus() {
        return applicationStatus;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public LastFailureCode getLastFailureCode() {
        return lastFailureCode;
    }

    public String getLastFailureMessage() {
        return lastFailureMessage;
    }

    public long getVersion() {
        return version;
    }
}
