package com.eventledger.gateway.service;

import com.eventledger.gateway.domain.StoredEvent;
import com.eventledger.gateway.error.EventNotFoundException;
import com.eventledger.gateway.persistence.EventRepository;
import com.eventledger.gateway.persistence.StoredEventEntity;
import com.eventledger.gateway.persistence.StoredEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class EventQueryService {

    private final EventRepository events;
    private final EventIdentifierValidator identifierValidator;

    public EventQueryService(EventRepository events,
                             EventIdentifierValidator identifierValidator) {
        this.events = events;
        this.identifierValidator = identifierValidator;
    }

    @Transactional(readOnly = true)
    public StoredEvent getEvent(String eventId) {
        identifierValidator.requireValid("eventId", eventId);
        StoredEventEntity entity = events.findById(eventId)
                .orElseThrow(EventNotFoundException::new);
        return StoredEventMapper.toStoredEvent(entity);
    }

    @Transactional(readOnly = true)
    public List<StoredEvent> listEvents(String accountId) {
        identifierValidator.requireValid("account", accountId);
        List<StoredEvent> storedEvents = new ArrayList<>();
        for (StoredEventEntity entity
                : events.findByAccountIdOrderByEventTimestampAscEventIdAsc(accountId)) {
            storedEvents.add(StoredEventMapper.toStoredEvent(entity));
        }
        return storedEvents;
    }
}
