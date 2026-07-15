package com.eventledger.gateway.service;

import com.eventledger.gateway.domain.NormalizedEvent;
import com.eventledger.gateway.domain.StoredEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class EventEquality {

    private final JsonMapper jsonMapper;

    public EventEquality(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public boolean sameBusinessInstruction(StoredEvent stored, NormalizedEvent incoming) {
        return stored.eventId().equals(incoming.eventId())
                && stored.accountId().equals(incoming.accountId())
                && stored.type() == incoming.type()
                && stored.amount().compareTo(incoming.amount()) == 0
                && stored.currency().equals(incoming.currency())
                && stored.eventTimestamp().equals(incoming.eventTimestamp())
                && sameMetadata(stored.metadata(), incoming.metadata());
    }

    private boolean sameMetadata(String storedMetadata, String incomingMetadata) {
        return jsonMapper.readTree(storedMetadata).equals(jsonMapper.readTree(incomingMetadata));
    }
}
