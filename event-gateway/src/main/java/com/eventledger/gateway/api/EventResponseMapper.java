package com.eventledger.gateway.api;

import com.eventledger.gateway.domain.StoredEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class EventResponseMapper {

    private final JsonMapper jsonMapper;

    public EventResponseMapper(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public EventResponse toEventResponse(StoredEvent event) {
        return new EventResponse(
                event.eventId(),
                event.accountId(),
                event.type(),
                event.amount(),
                event.currency(),
                event.eventTimestamp(),
                jsonMapper.readTree(event.metadata()),
                event.applicationStatus(),
                event.receivedAt(),
                event.appliedAt());
    }

    public EventListItemResponse toListItem(StoredEvent event) {
        return new EventListItemResponse(
                event.eventId(), event.eventTimestamp(), event.applicationStatus());
    }
}
