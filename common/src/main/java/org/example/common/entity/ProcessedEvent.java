package org.example.common.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "processed_events",
        uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "saga_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "saga_id", nullable = false)
    private String sagaId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "service_name", nullable = false, length = 50)
    private String serviceName;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    @Column(name = "order_id")
    private String orderId;

    @Column(name = "correlation_id")
    private String correlationId;

    public ProcessedEvent(String eventId, String sagaId, String eventType, String serviceName) {
        this.eventId = eventId;
        this.sagaId = sagaId;
        this.eventType = eventType;
        this.serviceName = serviceName;
        this.processedAt = LocalDateTime.now();
    }
}