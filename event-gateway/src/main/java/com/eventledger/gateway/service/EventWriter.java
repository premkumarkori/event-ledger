package com.eventledger.gateway.service;

import com.eventledger.gateway.domain.NormalizedEvent;
import com.eventledger.gateway.domain.StoredEvent;
import com.eventledger.gateway.persistence.StoredEventEntity;
import com.eventledger.gateway.persistence.StoredEventMapper;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Repository
public class EventWriter {

    private final EntityManager entityManager;
    private final Clock clock;

    public EventWriter(EntityManager entityManager, Clock clock) {
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StoredEvent insert(NormalizedEvent event) {
        StoredEventEntity entity = new StoredEventEntity(event, clock.instant());
        entityManager.persist(entity);
        entityManager.flush();
        return StoredEventMapper.toStoredEvent(entity);
    }
}
