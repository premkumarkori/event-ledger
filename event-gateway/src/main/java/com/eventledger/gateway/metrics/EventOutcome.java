package com.eventledger.gateway.metrics;

public enum EventOutcome {
    CREATED("created"),
    REPLAYED("replayed"),
    CONFLICT("conflict"),
    APPLY_FAILED("apply_failed");

    private final String tagValue;

    EventOutcome(String tagValue) {
        this.tagValue = tagValue;
    }

    public String tagValue() {
        return tagValue;
    }
}
