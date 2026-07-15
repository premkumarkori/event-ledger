package com.eventledger.gateway.persistence;

import com.eventledger.gateway.domain.LastFailureCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface EventRepository extends JpaRepository<StoredEventEntity, String> {

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE StoredEventEntity e
               SET e.applicationStatus = com.eventledger.gateway.domain.ApplicationStatus.APPLIED,
                   e.appliedAt = :now,
                   e.lastAttemptAt = :now,
                   e.attemptCount = e.attemptCount + 1,
                   e.lastFailureCode = NULL,
                   e.lastFailureMessage = NULL,
                   e.version = e.version + 1
             WHERE e.eventId = :eventId
               AND e.applicationStatus <> com.eventledger.gateway.domain.ApplicationStatus.APPLIED
            """)
    int markApplied(@Param("eventId") String eventId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE StoredEventEntity e
               SET e.applicationStatus = com.eventledger.gateway.domain.ApplicationStatus.APPLY_FAILED,
                   e.lastFailureCode = :code,
                   e.lastFailureMessage = :message,
                   e.lastAttemptAt = :now,
                   e.attemptCount = e.attemptCount + 1,
                   e.version = e.version + 1
             WHERE e.eventId = :eventId
               AND e.applicationStatus <> com.eventledger.gateway.domain.ApplicationStatus.APPLIED
            """)
    int markFailed(@Param("eventId") String eventId,
                   @Param("code") LastFailureCode code,
                   @Param("message") String message,
                   @Param("now") Instant now);
}
