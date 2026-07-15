package com.eventledger.gateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
public class EventOutcomeMetrics {

    public static final String METER_NAME = "ledger.events";
    public static final String OUTCOME_TAG = "outcome";

    private final Map<EventOutcome, Counter> counters = new EnumMap<>(EventOutcome.class);

    public EventOutcomeMetrics(MeterRegistry registry) {
        for (EventOutcome outcome : EventOutcome.values()) {
            counters.put(outcome, Counter.builder(METER_NAME)
                    .description("Gateway event ingestion outcomes")
                    .tag(OUTCOME_TAG, outcome.tagValue())
                    .register(registry));
        }
    }

    public void increment(EventOutcome outcome) {
        counters.get(outcome).increment();
    }
}
