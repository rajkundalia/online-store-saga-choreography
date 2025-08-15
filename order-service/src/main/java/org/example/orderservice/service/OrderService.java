package org.example.orderservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.enums.OrderStatus;
import org.example.common.events.CreateOrderRequest;
import org.example.common.events.InventoryRestockedEvent;
import org.example.common.events.OrderCancelledEvent;
import org.example.common.events.OrderCompletedEvent;
import org.example.common.events.OrderCreatedEvent;
import org.example.common.events.PaymentFailedEvent;
import org.example.common.events.PaymentProcessedEvent;
import org.example.common.service.IdempotencyService;
import org.example.common.util.EventIdGenerator;
import org.example.orderservice.entity.Order;
import org.example.orderservice.repository.OrderRepository;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;
    private final StreamBridge streamBridge;
    private final IdempotencyService idempotencyService;

    /**
     * Create new order and start saga
     */
    @Transactional
    public String createOrder(CreateOrderRequest request) {
        String orderId = UUID.randomUUID().toString();
        String sagaId = EventIdGenerator.generateSagaId();
        String correlationId = EventIdGenerator.generateCorrelationId();

        log.info("Creating order: orderId={}, sagaId={}, customerId={}, amount={}",
                orderId, sagaId, request.getCustomerId(), request.getAmount());

        // Check inventory availability first
        if (!inventoryService.isAvailable(request.getProductId(), request.getQuantity())) {
            log.warn("Insufficient inventory for order: orderId={}, productId={}, quantity={}",
                    orderId, request.getProductId(), request.getQuantity());
            throw new IllegalStateException("Insufficient inventory");
        }

        // Reserve inventory
        inventoryService.reserveInventory(request.getProductId(), request.getQuantity(), sagaId);

        // Create order entity
        Order order = new Order();
        order.setOrderId(orderId);
        order.setCustomerId(request.getCustomerId());
        order.setAmount(request.getAmount());
        order.setProductId(request.getProductId());
        order.setQuantity(request.getQuantity());
        order.setStatus(OrderStatus.PENDING);
        order.setSagaId(sagaId);
        order.setCorrelationId(correlationId);

        order = orderRepository.save(order);

        // Publish OrderCreatedEvent to start saga
        OrderCreatedEvent event = new OrderCreatedEvent(
                EventIdGenerator.generateEventId(),
                sagaId,
                orderId,
                request.getCustomerId(),
                request.getAmount(),
                request.getProductId(),
                request.getQuantity(),
                order.getVersion(),
                LocalDateTime.now()
        );

        streamBridge.send("orderCreated-out-0", event);
        log.info("Published OrderCreatedEvent: orderId={}, sagaId={}", orderId, sagaId);

        return orderId;
    }

    /**
     * Handle payment processed successfully - complete order
     */
    @EventListener
    @Async
    @Transactional
    public void handlePaymentProcessed(PaymentProcessedEvent event) {
        log.info("Handling PaymentProcessedEvent: orderId={}, sagaId={}",
                event.getOrderId(), event.getSagaId());

        // Check idempotency
        if (idempotencyService.isEventAlreadyProcessed(event.getEventId(), event.getSagaId())) {
            log.info("PaymentProcessedEvent already processed: eventId={}, sagaId={}",
                    event.getEventId(), event.getSagaId());
            return;
        }

        try {
            Order order = orderRepository.findById(event.getOrderId())
                    .orElseThrow(() -> new IllegalStateException("Order not found: " + event.getOrderId()));

            // Update order status to completed
            order.setStatus(OrderStatus.COMPLETED);
            orderRepository.save(order);

            // Mark event as processed
            idempotencyService.markEventAsProcessed(
                    event.getEventId(),
                    event.getSagaId(),
                    "PaymentProcessedEvent",
                    "order-service",
                    event.getOrderId(),
                    order.getCorrelationId()
            );

            // Publish OrderCompletedEvent
            OrderCompletedEvent completedEvent = new OrderCompletedEvent(
                    EventIdGenerator.generateEventId(),
                    event.getSagaId(),
                    event.getOrderId(),
                    order.getCustomerId(),
                    LocalDateTime.now()
            );

            streamBridge.send("orderCompleted-out-0", completedEvent);
            log.info("Order completed successfully: orderId={}, sagaId={}",
                    event.getOrderId(), event.getSagaId());

        } catch (Exception e) {
            log.error("Error handling PaymentProcessedEvent: orderId={}, sagaId={}, error={}",
                    event.getOrderId(), event.getSagaId(), e.getMessage(), e);
        }
    }

    /**
     * Handle payment failed - start compensation
     */
    @EventListener
    @Async
    @Transactional
    public void handlePaymentFailed(PaymentFailedEvent event) {
        log.info("Handling PaymentFailedEvent: orderId={}, sagaId={}, reason={}",
                event.getOrderId(), event.getSagaId(), event.getReason());

        // Check idempotency
        if (idempotencyService.isEventAlreadyProcessed(event.getEventId(), event.getSagaId())) {
            log.info("PaymentFailedEvent already processed: eventId={}, sagaId={}",
                    event.getEventId(), event.getSagaId());
            return;
        }

        try {
            compensateOrder(event.getOrderId(), event.getSagaId(), event.getReason());

            // Mark event as processed
            idempotencyService.markEventAsProcessed(
                    event.getEventId(),
                    event.getSagaId(),
                    "PaymentFailedEvent",
                    "order-service",
                    event.getOrderId(),
                    null
            );

        } catch (Exception e) {
            log.error("Error handling PaymentFailedEvent: orderId={}, sagaId={}, error={}",
                    event.getOrderId(), event.getSagaId(), e.getMessage(), e);
        }
    }

    /**
     * Compensate order - cancel and release inventory
     */
    private void compensateOrder(String orderId, String sagaId, String reason) {
        log.info("Starting order compensation: orderId={}, sagaId={}, reason={}",
                orderId, sagaId, reason);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));

        // Update order status to cancelled
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelledReason(reason);
        orderRepository.save(order);

        // Release reserved inventory
        inventoryService.releaseReservation(order.getProductId(), order.getQuantity(), sagaId);

        // Publish OrderCancelledEvent
        OrderCancelledEvent cancelledEvent = new OrderCancelledEvent(
                EventIdGenerator.generateEventId(),
                sagaId,
                orderId,
                order.getCustomerId(),
                reason,
                LocalDateTime.now()
        );

        streamBridge.send("orderCancelled-out-0", cancelledEvent);

        // Publish InventoryRestockedEvent
        InventoryRestockedEvent restockedEvent = new InventoryRestockedEvent(
                EventIdGenerator.generateEventId(),
                sagaId,
                orderId,
                order.getProductId(),
                order.getQuantity(),
                LocalDateTime.now()
        );

        streamBridge.send("inventoryRestocked-out-0", restockedEvent);

        log.info("Order compensation completed: orderId={}, sagaId={}", orderId, sagaId);
    }

    /**
     * Get order by ID
     */
    public Order getOrder(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
    }

    /**
     * Get order by saga ID
     */
    public Order getOrderBySagaId(String sagaId) {
        return orderRepository.findBySagaId(sagaId)
                .orElseThrow(() -> new IllegalStateException("Order not found for saga: " + sagaId));
    }
}
