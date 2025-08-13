package org.example.common.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCancelledEvent {
    private String eventId;
    private String sagaId;
    private String orderId;
    private String customerId;
    private String reason;
    private LocalDateTime timestamp;
}