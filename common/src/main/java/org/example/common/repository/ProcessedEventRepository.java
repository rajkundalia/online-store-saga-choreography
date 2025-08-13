package org.example.common.repository;

import org.example.common.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    Optional<ProcessedEvent> findByEventIdAndSagaId(String eventId, String sagaId);

    boolean existsByEventIdAndSagaId(String eventId, String sagaId);

    @Query("SELECT pe FROM ProcessedEvent pe WHERE pe.orderId = :orderId AND pe.eventType = :eventType")
    Optional<ProcessedEvent> findByOrderIdAndEventType(@Param("orderId") String orderId,
                                                       @Param("eventType") String eventType);
}