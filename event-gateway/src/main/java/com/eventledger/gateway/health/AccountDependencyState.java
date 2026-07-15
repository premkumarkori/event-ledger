package com.eventledger.gateway.health;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class AccountDependencyState {

    public enum Observation {
        UNKNOWN,
        AVAILABLE,
        UNAVAILABLE
    }

    public record Snapshot(Observation observation, Instant observedAt) {

        public Snapshot {
            Objects.requireNonNull(observation, "observation is required");
            if (observation == Observation.UNKNOWN && observedAt != null) {
                throw new IllegalArgumentException("an unknown dependency has no observation time");
            }
            if (observation != Observation.UNKNOWN && observedAt == null) {
                throw new IllegalArgumentException("an observed dependency requires an observation time");
            }
        }
    }

    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(new Snapshot(Observation.UNKNOWN, null));

    public void recordAvailable(Instant observedAt) {
        record(Observation.AVAILABLE, observedAt);
    }

    public void recordUnavailable(Instant observedAt) {
        record(Observation.UNAVAILABLE, observedAt);
    }

    public Snapshot snapshot() {
        return current.get();
    }

    public void clearObservations() {
        current.set(new Snapshot(Observation.UNKNOWN, null));
    }

    private void record(Observation observation, Instant observedAt) {
        Snapshot next = new Snapshot(observation, observedAt);
        current.updateAndGet(existing -> isOlder(next, existing) ? existing : next);
    }

    private boolean isOlder(Snapshot candidate, Snapshot existing) {
        return existing.observedAt() != null
                && candidate.observedAt().isBefore(existing.observedAt());
    }
}
