package org.example.common.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundProcessedEvent {
    private String eventId;
    private String sagaId;
    private String orderId;
    private String paymentId;
    private BigDecimal refundAmount;
    private LocalDateTime timestamp;
}