package org.example.common.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentFailedEvent {
    private String eventId;
    private String sagaId;
    private String orderId;
    private BigDecimal amount;
    private String reason;
    private String errorCode;
    private LocalDateTime timestamp;
}