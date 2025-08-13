package org.example.common.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent {
    private String eventId;
    private String sagaId;
    private String orderId;
    private String customerId;
    private BigDecimal amount;
    private String productId;
    private Integer quantity;
    private Long orderVersion;
    private LocalDateTime timestamp;
}