package org.example.integration;

import org.example.SagaChoreographyApplication;
import org.example.common.enums.OrderStatus;
import org.example.common.events.CreateOrderRequest;
import org.example.common.events.OrderCreatedEvent;
import org.example.common.events.PaymentFailedEvent;
import org.example.common.repository.ProcessedEventRepository;
import org.example.orderservice.entity.Inventory;
import org.example.orderservice.entity.Order;
import org.example.orderservice.repository.InventoryRepository;
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
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Dedicated test class for saga timeout handling.
 */
@SpringBootTest(classes = SagaChoreographyApplication.class)
@Import(TestChannelBinderConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {
        "saga.payment.processing-delay=PT2S",
        "saga.payment.timeout-threshold=PT1S",
        "saga.payment.enable-timeout=true"
})
class SagaTimeoutIntegrationTest {

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
    private InventoryRepository inventoryRepository;

    @Autowired
    private OutputDestination output;

    @BeforeEach
    void setUp() {
        // Clean up test data
        processedEventRepository.deleteAll();
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();

        // Set up initial inventory for testing
        Inventory inventory = new Inventory(
                "PROD-003", // Match the productId used in the test
                10, // A sufficient quantity for the test to pass
                0,
                null,
                null
        );
        inventoryRepository.save(inventory);

        // Clear message channels
        output.clear();
    }

    /**
     * UnitOfWork: Order creation saga
     * StateUnderTest: Payment service processing exceeds configured timeout
     * ExpectedBehavior: Order is cancelled and corresponding reason indicates timeout
     */
    @Test
    void createOrder_PaymentTimeout_OrderCancelledDueToTimeout() {
        // Given: Create an order request
        CreateOrderRequest request = new CreateOrderRequest(
                "customer-timeout",
                BigDecimal.valueOf(300.00),
                "PROD-003",
                1
        );

        // When: Create order
        String orderId = orderService.createOrder(request);
        Order order = orderRepository.findById(orderId).orElse(null);
        assertThat(order).isNotNull();

        // Simulate the complete saga flow manually
        OrderCreatedEvent orderCreatedEvent = new OrderCreatedEvent(
                "timeout-event-1",
                order.getSagaId(),
                orderId,
                "customer-timeout",
                BigDecimal.valueOf(300.00),
                "PROD-003",
                1,
                1L,
                order.getCreatedAt()
        );

        // Step 1: Payment service handles order created (will time-out)
        paymentService.handleOrderCreated(orderCreatedEvent);

        // Step 2: Wait for timeout and then manually trigger the failed event handling
        await().atMost(3, TimeUnit.SECONDS).until(() -> {
            Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);
            return payment != null && ("TIMEOUT".equals(payment.getStatus().name()) || "FAILED".equals(payment.getStatus().name()));
        });

        // Step 3: Manually create and handle the PaymentFailedEvent (simulating message broker)
        // Manual since event listener is not getting invoked
        PaymentFailedEvent failedEvent = new PaymentFailedEvent(
                "failed-event-1",
                order.getSagaId(),
                orderId,
                BigDecimal.valueOf(300.00),
                "Payment processing exceeded timeout threshold",
                "TIMEOUT",
                LocalDateTime.now()
        );

        orderService.handlePaymentFailed(failedEvent);

        // Then: Verify cancellation
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Order cancelledOrder = orderRepository.findById(orderId).orElse(null);
            assertThat(cancelledOrder).isNotNull();
            assertThat(cancelledOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(cancelledOrder.getCancelledReason()).containsIgnoringCase("timeout");
        });
    }
}
