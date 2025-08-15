package example.integration;

import org.example.SagaChoreographyApplication;
import org.example.common.enums.OrderStatus;
import org.example.common.events.CreateOrderRequest;
import org.example.common.events.OrderCreatedEvent;
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

/**
 * Dedicated test class for saga timeout handling.
 */
@SpringBootTest(classes = SagaChoreographyApplication.class)
@Import(TestChannelBinderConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test-timeout")
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

    /**
     * UnitOfWork: Order creation saga
     * StateUnderTest: Payment service processing exceeds configured timeout
     * ExpectedBehavior: Order is cancelled and corresponding reason indicates timeout
     */
    @Test
    @Transactional
    void createOrder_PaymentTimeout_OrderCancelledDueToTimeout() {
        // Given: Create an order request that will trigger the saga
        CreateOrderRequest request = new CreateOrderRequest(
                "customer-timeout",
                BigDecimal.valueOf(300.00),
                "PROD-003",
                1
        );

        // When: Create order and simulate saga
        String orderId = orderService.createOrder(request);
        Order order = orderRepository.findById(orderId).orElse(null);
        assertThat(order).isNotNull();

        // Simulate event handling with forced delay (PaymentService should handle this)
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

        paymentService.handleOrderCreated(orderCreatedEvent);

        // Then: Await for cancellation due to timeout
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            Order cancelledOrder = orderRepository.findById(orderId).orElse(null);
            assertThat(cancelledOrder).isNotNull();
            assertThat(cancelledOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(cancelledOrder.getCancelledReason()).containsIgnoringCase("timeout");
        });
    }
}
