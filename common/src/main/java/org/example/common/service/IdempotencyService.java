package org.example.common.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.entity.ProcessedEvent;
import org.example.common.repository.ProcessedEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final ProcessedEventRepository processedEventRepository;

    /**
     * Check if event was already processed (for idempotency)
     */
    public boolean isEventAlreadyProcessed(String eventId, String sagaId) {
        return processedEventRepository.existsByEventIdAndSagaId(eventId, sagaId);
    }

    /**
     * Mark event as processed
     */
    @Transactional
    public void markEventAsProcessed(String eventId, String sagaId, String eventType,
                                     String serviceN, String orderId, String correlationId) {
        if (isEventAlreadyProcessed(eventId, sagaId)) {
            log.warn("Event already processed: eventId={}, sagaId={}, eventType={}",
                    eventId, sagaId, eventType);
            return;
        }

        ProcessedEvent processedEvent = new ProcessedEvent(eventId, sagaId, eventType, serviceN);
        processedEvent.setOrderId(orderId);
        processedEvent.setCorrelationId(correlationId);

        processedEventRepository.save(processedEvent);
        log.info("Marked event as processed: eventId={}, sagaId={}, eventType={}",
                eventId, sagaId, eventType);
    }
}