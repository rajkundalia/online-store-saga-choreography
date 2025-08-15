package org.example.coreconfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.events.OrderCreatedEvent;
import org.example.common.events.PaymentFailedEvent;
import org.example.common.events.PaymentProcessedEvent;
import org.example.orderservice.service.OrderService;
import org.example.paymentservice.service.PaymentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class EventListenerConfig {

    private final OrderService orderService;
    private final PaymentService paymentService;

    /**
     * Payment service listens to order created events
     */
    @Bean
    public Consumer<OrderCreatedEvent> orderCreatedConsumer() {
        return event -> {
            log.info("Received OrderCreatedEvent via stream: orderId={}, sagaId={}",
                    event.getOrderId(), event.getSagaId());
            paymentService.handleOrderCreated(event);
        };
    }

    /**
     * Order service listens to payment processed events
     */
    @Bean
    public Consumer<PaymentProcessedEvent> paymentProcessedConsumer() {
        return event -> {
            log.info("Received PaymentProcessedEvent via stream: orderId={}, sagaId={}",
                    event.getOrderId(), event.getSagaId());
            orderService.handlePaymentProcessed(event);
        };
    }

    /**
     * Order service listens to payment failed events
     */
    @Bean
    public Consumer<PaymentFailedEvent> paymentFailedConsumer() {
        return event -> {
            log.info("Received PaymentFailedEvent via stream: orderId={}, sagaId={}, reason={}",
                    event.getOrderId(), event.getSagaId(), event.getReason());
            orderService.handlePaymentFailed(event);
        };
    }
}