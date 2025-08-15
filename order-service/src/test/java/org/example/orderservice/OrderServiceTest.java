package org.example.orderservice;

import org.example.common.enums.OrderStatus;
import org.example.common.events.CreateOrderRequest;
import org.example.common.events.PaymentFailedEvent;
import org.example.common.events.PaymentProcessedEvent;
import org.example.common.service.IdempotencyService;
import org.example.orderservice.entity.Order;
import org.example.orderservice.repository.OrderRepository;
import org.example.orderservice.service.InventoryService;
import org.example.orderservice.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.function.StreamBridge;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private StreamBridge streamBridge;

    @Mock
    private IdempotencyService idempotencyService;

    @InjectMocks
    private OrderService orderService;

    private CreateOrderRequest validOrderRequest;
    private Order sampleOrder;

    @BeforeEach
    void setUp() {
        validOrderRequest = new CreateOrderRequest(
                "customer-123",
                BigDecimal.valueOf(100.00),
                "PROD-001",
                2
        );

        sampleOrder = new Order();
        sampleOrder.setOrderId("order-123");
        sampleOrder.setCustomerId("customer-123");
        sampleOrder.setAmount(BigDecimal.valueOf(100.00));
        sampleOrder.setProductId("PROD-001");
        sampleOrder.setQuantity(2);
        sampleOrder.setStatus(OrderStatus.PENDING);
        sampleOrder.setSagaId("saga-123");
        sampleOrder.setVersion(1L);
        sampleOrder.setCreatedAt(LocalDateTime.now());
    }

    @Test
    void createOrder_SufficientInventory_OrderCreatedAndEventsSent() {
        // Given
        when(inventoryService.isAvailable("PROD-001", 2)).thenReturn(true);
        when(orderRepository.save(any(Order.class))).thenReturn(sampleOrder);

        // When
        String orderId = orderService.createOrder(validOrderRequest);

        // Then
        assertThat(orderId).isNotNull();
        verify(inventoryService).isAvailable("PROD-001", 2);
        verify(inventoryService).reserveInventory(eq("PROD-001"), eq(2), anyString());
        verify(orderRepository).save(any(Order.class));
        verify(streamBridge).send(eq("orderCreated-out-0"), any());
    }

    @Test
    void createOrder_InsufficientInventory_ThrowsExceptionAndNoStateChange() {
        // Given
        when(inventoryService.isAvailable("PROD-001", 2)).thenReturn(false);

        // When & Then
        assertThatThrownBy(() -> orderService.createOrder(validOrderRequest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient inventory");

        verify(inventoryService).isAvailable("PROD-001", 2);
        verify(inventoryService, never()).reserveInventory(anyString(), anyInt(), anyString());
        verify(orderRepository, never()).save(any());
        verify(streamBridge, never()).send(anyString(), any());
    }

    @Test
    void handlePaymentProcessed_ValidEvent_OrderCompletedAndEventSent() {
        // Given
        PaymentProcessedEvent event = new PaymentProcessedEvent(
                "event-123", "saga-123", "order-123", "payment-123",
                BigDecimal.valueOf(100.00), "COMPLETED", LocalDateTime.now()
        );

        when(idempotencyService.isEventAlreadyProcessed("event-123", "saga-123")).thenReturn(false);
        when(orderRepository.findById("order-123")).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenReturn(sampleOrder);

        // When
        orderService.handlePaymentProcessed(event);

        // Then
        verify(idempotencyService).isEventAlreadyProcessed("event-123", "saga-123");
        verify(orderRepository).findById("order-123");
        verify(orderRepository).save(argThat(order ->
                order.getStatus() == OrderStatus.COMPLETED));
        verify(idempotencyService).markEventAsProcessed(
                "event-123", "saga-123", "PaymentProcessedEvent", "order-service", "order-123", null);
        verify(streamBridge).send(eq("orderCompleted-out-0"), any());
    }

    @Test
    void handlePaymentProcessed_EventAlreadyProcessed_NoActionTaken() {
        // Given
        PaymentProcessedEvent event = new PaymentProcessedEvent(
                "event-123", "saga-123", "order-123", "payment-123",
                BigDecimal.valueOf(100.00), "COMPLETED", LocalDateTime.now()
        );

        when(idempotencyService.isEventAlreadyProcessed("event-123", "saga-123")).thenReturn(true);

        // When
        orderService.handlePaymentProcessed(event);

        // Then
        verify(idempotencyService).isEventAlreadyProcessed("event-123", "saga-123");
        verify(orderRepository, never()).findById(anyString());
        verify(orderRepository, never()).save(any());
        verify(streamBridge, never()).send(anyString(), any());
    }

    @Test
    void handlePaymentFailed_PaymentFails_OrderCancelledAndCompensationStarted() {
        // Given
        PaymentFailedEvent event = new PaymentFailedEvent(
                "event-456", "saga-123", "order-123", BigDecimal.valueOf(100.00),
                "Insufficient funds", "INSUFFICIENT_FUNDS", LocalDateTime.now()
        );

        when(idempotencyService.isEventAlreadyProcessed("event-456", "saga-123")).thenReturn(false);
        when(orderRepository.findById("order-123")).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenReturn(sampleOrder);

        // When
        orderService.handlePaymentFailed(event);

        // Then
        verify(idempotencyService).isEventAlreadyProcessed("event-456", "saga-123");
        verify(orderRepository).findById("order-123");
        verify(orderRepository).save(argThat(order ->
                order.getStatus() == OrderStatus.CANCELLED &&
                        "Insufficient funds".equals(order.getCancelledReason())));
        verify(inventoryService).releaseReservation("PROD-001", 2, "saga-123");
        verify(idempotencyService).markEventAsProcessed(
                "event-456", "saga-123", "PaymentFailedEvent", "order-service", "order-123", null);
        verify(streamBridge).send(eq("orderCancelled-out-0"), any());
        verify(streamBridge).send(eq("inventoryRestocked-out-0"), any());
    }

    @Test
    void getOrder_OrderExists_ReturnsOrder() {
        // Given
        when(orderRepository.findById("order-123")).thenReturn(Optional.of(sampleOrder));

        // When
        Order result = orderService.getOrder("order-123");

        // Then
        assertThat(result).isEqualTo(sampleOrder);
        verify(orderRepository).findById("order-123");
    }

    @Test
    void getOrder_OrderDoesNotExist_ThrowsException() {
        // Given
        when(orderRepository.findById("order-999")).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> orderService.getOrder("order-999"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Order not found: order-999");
    }

    @Test
    void getOrderBySagaId_OrderExists_ReturnsOrder() {
        // Given
        when(orderRepository.findBySagaId("saga-123")).thenReturn(Optional.of(sampleOrder));

        // When
        Order result = orderService.getOrderBySagaId("saga-123");

        // Then
        assertThat(result).isEqualTo(sampleOrder);
        verify(orderRepository).findBySagaId("saga-123");
    }

    @Test
    void getOrderBySagaId_OrderDoesNotExist_ThrowsException() {
        // Given
        when(orderRepository.findBySagaId("saga-999")).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> orderService.getOrderBySagaId("saga-999"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Order not found for saga: saga-999");
    }
}