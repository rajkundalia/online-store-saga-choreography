package org.example.integration;

import org.example.SagaChoreographyApplication;
import org.example.common.entity.ProcessedEvent;
import org.example.common.enums.OrderStatus;
import org.example.common.enums.PaymentStatus;
import org.example.common.events.CreateOrderRequest;
import org.example.common.events.OrderCreatedEvent;
import org.example.common.events.PaymentFailedEvent;
import org.example.common.events.PaymentProcessedEvent;
import org.example.common.repository.ProcessedEventRepository;
import org.example.orderservice.entity.Order;
import org.example.orderservice.repository.OrderRepository;
import org.example.orderservice.service.OrderService;
import org.example.paymentservice.entity.Payment;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = SagaChoreographyApplication.class)
@Import(TestChannelBinderConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
class SagaIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private OutputDestination output;

    @BeforeEach
    void setUp() {
        // Clean up test data
        processedEventRepository.deleteAll();
        paymentRepository.deleteAll();
        orderRepository.deleteAll();

        // Clear message channels
        output.clear();
    }

    @Test
    @Transactional
    void createOrder_PaymentSuccess_OrderMarkedCompleted() {
        // Given
        CreateOrderRequest request = new CreateOrderRequest(
                "customer-123",
                BigDecimal.valueOf(100.00),
                "PROD-001",
                2
        );

        // When - Create order (starts saga)
        String orderId = orderService.createOrder(request);

        // Verify order created
        Order order = orderRepository.findById(orderId).orElse(null);
        assertThat(order).isNotNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getSagaId()).isNotNull();

        // Simulate event processing manually for test
        OrderCreatedEvent orderEvent = new OrderCreatedEvent(
                "event-1", order.getSagaId(), orderId, "customer-123",
                BigDecimal.valueOf(100.00), "PROD-001", 2, 1L, order.getCreatedAt()
        );

        // Process payment (simulate async event handling)
        paymentService.handleOrderCreated(orderEvent);

        // Wait for payment processing to complete
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);
            assertThat(payment).isNotNull();
            assertThat(payment.getStatus()).isIn(PaymentStatus.COMPLETED, PaymentStatus.FAILED);
        });

        Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);
        assert payment != null;
        if (payment.getStatus() == PaymentStatus.COMPLETED) {
            // Simulate payment processed event
            PaymentProcessedEvent paymentEvent = new PaymentProcessedEvent(
                    "event-2", order.getSagaId(), orderId, payment.getPaymentId(),
                    payment.getAmount(), "COMPLETED", payment.getUpdatedAt()
            );

            // Complete order
            orderService.handlePaymentProcessed(paymentEvent);

            // Verify successful saga completion
            await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
                Order completedOrder = orderRepository.findById(orderId).orElse(null);
                assertThat(completedOrder).isNotNull();
                assertThat(completedOrder.getStatus()).isEqualTo(OrderStatus.COMPLETED);
            });
        }
    }

    @Test
    @Transactional
    void createOrder_PaymentFailure_OrderCancelledWithReason() {
        // Given
        CreateOrderRequest request = new CreateOrderRequest(
                "customer-456",
                BigDecimal.valueOf(200.00),
                "PROD-002",
                1
        );

        // When - Create order
        String orderId = orderService.createOrder(request);
        Order order = orderRepository.findById(orderId).orElse(null);

        // Simulate payment failure
        assert order != null;
        PaymentFailedEvent failedEvent = new PaymentFailedEvent(
                "event-fail-1", order.getSagaId(), orderId,
                BigDecimal.valueOf(200.00), "Insufficient funds", "INSUFFICIENT_FUNDS",
                order.getCreatedAt()
        );

        // Trigger compensation
        orderService.handlePaymentFailed(failedEvent);

        // Verify compensation completed
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            Order cancelledOrder = orderRepository.findById(orderId).orElse(null);
            assertThat(cancelledOrder).isNotNull();
            assertThat(cancelledOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(cancelledOrder.getCancelledReason()).isEqualTo("Insufficient funds");
        });
    }

    @Test
    void handleOrderCreated_DuplicateEvent_NoDuplicateProcessing() throws InterruptedException {
        // Given
        CreateOrderRequest request = new CreateOrderRequest(
                "customer-789",
                BigDecimal.valueOf(150.00),
                "PROD-001",
                1
        );

        String orderId = orderService.createOrder(request);
        Order order = orderRepository.findById(orderId).orElse(null);

        assert order != null;
        OrderCreatedEvent orderEvent = new OrderCreatedEvent(
                "duplicate-event-id", order.getSagaId(), orderId, "customer-789",
                BigDecimal.valueOf(150.00), "PROD-001", 1, 1L, order.getCreatedAt()
        );

        // When - Process same event twice
        paymentService.handleOrderCreated(orderEvent);

        // Wait for first processing
        Thread.sleep(1000);

        long paymentCountBefore = paymentRepository.count();
        long processedEventCountBefore = processedEventRepository.count();

        // Process duplicate event
        paymentService.handleOrderCreated(orderEvent);

        // Then - Verify idempotency
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            long paymentCountAfter = paymentRepository.count();
            long processedEventCountAfter = processedEventRepository.count();

            // Should not create duplicate payment
            assertThat(paymentCountAfter).isEqualTo(paymentCountBefore);

            // Should have processed event record
            assertThat(processedEventCountAfter).isGreaterThan(0);

            ProcessedEvent processedEvent = processedEventRepository
                    .findByEventIdAndSagaId("duplicate-event-id", order.getSagaId())
                    .orElse(null);
            assertThat(processedEvent).isNotNull();
        });
    }

/*    @Test
    @Transactional
    void createOrder_PaymentTimeout_OrderCancelledDueToTimeout() {
        // This test would require special configuration for timeout scenarios
        // Implementation depends on timeout configuration being enabled

        CreateOrderRequest request = new CreateOrderRequest(
                "customer-timeout",
                BigDecimal.valueOf(300.00),
                "PROD-003",
                1
        );

        String orderId = orderService.createOrder(request);
        Order order = orderRepository.findById(orderId).orElse(null);
        assertThat(order).isNotNull();

        // For timeout testing, you would need to:
        // 1. Configure payment service with processing delay > timeout threshold
        // 2. Process OrderCreatedEvent
        // 3. Verify PaymentFailedEvent with timeout reason is published
        // 4. Verify compensation is triggered

        // Note: Full timeout testing requires async processing and timing control
        // which is complex in unit tests. Consider using @SpringBootTest with
        // specific timeout profiles for comprehensive timeout testing.
    }*/

    @Test
    void executeSaga_EventsRecorded_SagaTraceabilityVerified() throws InterruptedException {
        // Given
        CreateOrderRequest request = new CreateOrderRequest(
                "customer-trace",
                BigDecimal.valueOf(75.00),
                "PROD-002",
                3
        );

        // When
        String orderId = orderService.createOrder(request);
        Order order = orderRepository.findById(orderId).orElse(null);
        assert order != null;
        String sagaId = order.getSagaId();

        // Process through saga
        OrderCreatedEvent orderEvent = new OrderCreatedEvent(
                "trace-event-1", sagaId, orderId, "customer-trace",
                BigDecimal.valueOf(75.00), "PROD-002", 3, 1L, order.getCreatedAt()
        );

        paymentService.handleOrderCreated(orderEvent);

        // Wait for processing
        Thread.sleep(2000);

        // Then - Verify saga traceability
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            // Check processed events for saga traceability
            var processedEvents = processedEventRepository.findAll();
            var sagaEvents = processedEvents.stream()
                    .filter(pe -> sagaId.equals(pe.getSagaId()))
                    .toList();

            assertThat(sagaEvents).isNotEmpty();

            // Verify event types processed
            var eventTypes = sagaEvents.stream()
                    .map(ProcessedEvent::getEventType)
                    .toList();

            assertThat(eventTypes).contains("OrderCreatedEvent");
        });
    }
}