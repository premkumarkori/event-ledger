package com.eventledger.gateway.observability;

import com.eventledger.gateway.health.AccountDependencyState;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AccountDependencyStateTest {

    @Test
    void recordUnavailable_shouldKeepNewerObservation_whenOlderResultIsPublishedLate() {
        AccountDependencyState state = new AccountDependencyState();
        Instant newerObservation = Instant.parse("2026-07-15T16:00:02Z");

        state.recordAvailable(newerObservation);
        state.recordUnavailable(Instant.parse("2026-07-15T16:00:01Z"));

        assertThat(state.snapshot()).isEqualTo(new AccountDependencyState.Snapshot(
                AccountDependencyState.Observation.AVAILABLE,
                newerObservation));
    }

    @Test
    void clearObservations_shouldReturnToUnknown_whenStateIsReset() {
        AccountDependencyState state = new AccountDependencyState();
        state.recordUnavailable(Instant.parse("2026-07-15T16:00:01Z"));

        state.clearObservations();

        assertThat(state.snapshot()).isEqualTo(new AccountDependencyState.Snapshot(
                AccountDependencyState.Observation.UNKNOWN,
                null));
    }
}
