package com.eventledger.gateway.service;

import com.eventledger.gateway.domain.StoredEvent;
import com.eventledger.gateway.persistence.EventRepository;
import com.eventledger.gateway.persistence.StoredEventMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
public class EventReader {

    private final EventRepository events;

    public EventReader(EventRepository events) {
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<StoredEvent> find(String eventId) {
        return events.findById(eventId).map(StoredEventMapper::toStoredEvent);
    }
}
