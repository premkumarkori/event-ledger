package com.eventledger.account.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

public final class MutableTestClock extends Clock {

    private volatile Instant current;
    private final ZoneId zone;

    public MutableTestClock(Instant initialInstant) {
        this(initialInstant, ZoneOffset.UTC);
    }

    private MutableTestClock(Instant initialInstant, ZoneId zone) {
        this.current = Objects.requireNonNull(initialInstant);
        this.zone = Objects.requireNonNull(zone);
    }

    public void setInstant(Instant instant) {
        current = Objects.requireNonNull(instant);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        if (zone.equals(newZone)) {
            return this;
        }
        return new MutableTestClock(current, newZone);
    }

    @Override
    public Instant instant() {
        return current;
    }
}
