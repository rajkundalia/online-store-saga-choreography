package org.example.common.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentProcessedEvent {
    private String eventId;
    private String sagaId;
    private String orderId;
    private String paymentId;
    private BigDecimal amount;
    private String status;
    private LocalDateTime timestamp;
}