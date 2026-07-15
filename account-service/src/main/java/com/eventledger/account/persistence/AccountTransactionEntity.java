package com.eventledger.account.persistence;

import com.eventledger.account.domain.TransactionData;
import com.eventledger.account.domain.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "account_transactions")
public class AccountTransactionEntity {

    @Id
    @Column(name = "event_id", length = 128)
    private String eventId;

    @Column(name = "account_id", length = 128, nullable = false, updatable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 10, nullable = false, updatable = false)
    private TransactionType type;

    @Column(name = "amount", precision = 38, scale = 18, nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    private String currency;

    @Column(name = "event_timestamp", nullable = false, updatable = false)
    private Instant eventTimestamp;

    @Column(name = "applied_at", nullable = false, updatable = false)
    private Instant appliedAt;

    protected AccountTransactionEntity() {
    }

    public AccountTransactionEntity(TransactionData data, Instant appliedAt) {
        this.eventId = data.eventId();
        this.accountId = data.accountId();
        this.type = data.type();
        this.amount = data.amount();
        this.currency = data.currency();
        this.eventTimestamp = data.eventTimestamp();
        this.appliedAt = appliedAt;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAccountId() {
        return accountId;
    }

    public TransactionType getType() {
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

    public Instant getAppliedAt() {
        return appliedAt;
    }
}
