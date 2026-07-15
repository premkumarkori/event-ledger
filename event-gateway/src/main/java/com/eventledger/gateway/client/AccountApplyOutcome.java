package com.eventledger.gateway.client;

public record AccountApplyOutcome(Kind kind, ConflictType conflictType) {

    public AccountApplyOutcome {
        if (kind == null) {
            throw new IllegalArgumentException("outcome kind is required");
        }
        if (kind == Kind.TERMINAL_CONFLICT && conflictType == null) {
            throw new IllegalArgumentException("conflict type is required for a terminal conflict");
        }
        if (kind != Kind.TERMINAL_CONFLICT && conflictType != null) {
            throw new IllegalArgumentException("conflict type is only valid for a terminal conflict");
        }
    }

    public enum Kind {
        CONFIRMED,
        TERMINAL_CONFLICT,
        CONTRACT_ERROR,
        RETRYABLE_UNCONFIRMED
    }

    public enum ConflictType {
        IDEMPOTENCY,
        CURRENCY
    }

    static AccountApplyOutcome confirmed() {
        return new AccountApplyOutcome(Kind.CONFIRMED, null);
    }

    static AccountApplyOutcome terminalConflict(ConflictType conflictType) {
        return new AccountApplyOutcome(Kind.TERMINAL_CONFLICT, conflictType);
    }

    static AccountApplyOutcome contractError() {
        return new AccountApplyOutcome(Kind.CONTRACT_ERROR, null);
    }

    static AccountApplyOutcome retryableUnconfirmed() {
        return new AccountApplyOutcome(Kind.RETRYABLE_UNCONFIRMED, null);
    }
}
