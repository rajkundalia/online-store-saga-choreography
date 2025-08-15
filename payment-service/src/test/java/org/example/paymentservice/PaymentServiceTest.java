package org.example.paymentservice;

import org.example.common.enums.PaymentStatus;
import org.example.common.events.OrderCreatedEvent;
import org.example.common.service.IdempotencyService;
import org.example.paymentservice.entity.Payment;
import org.example.paymentservice.repository.PaymentRepository;
import org.example.paymentservice.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private StreamBridge streamBridge;

    @Mock
    private IdempotencyService idempotencyService;

    @InjectMocks
    private PaymentService paymentService;

    private OrderCreatedEvent orderEvent;
    private Payment samplePayment;

    @BeforeEach
    void setUp() {
        // Configure service properties
        ReflectionTestUtils.setField(paymentService, "processingDelay", Duration.ofSeconds(0));
        ReflectionTestUtils.setField(paymentService, "timeoutThreshold", Duration.ofSeconds(30));
        ReflectionTestUtils.setField(paymentService, "failureRate", 0.0);
        ReflectionTestUtils.setField(paymentService, "enableTimeout", false);

        orderEvent = new OrderCreatedEvent(
                "event-123", "saga-123", "order-123", "customer-123",
                BigDecimal.valueOf(100.00), "PROD-001", 2, 1L, LocalDateTime.now()
        );

        samplePayment = new Payment();
        samplePayment.setPaymentId("payment-123");
        samplePayment.setOrderId("order-123");
        samplePayment.setCustomerId("customer-123");
        samplePayment.setAmount(BigDecimal.valueOf(100.00));
        samplePayment.setStatus(PaymentStatus.PROCESSING);
        samplePayment.setSagaId("saga-123");
    }

    @Test
    void handleOrderCreated_SuccessfulPayment_SendsProcessedEvent() throws InterruptedException {
        // Given
        when(idempotencyService.isEventAlreadyProcessed("event-123", "saga-123")).thenReturn(false);
        when(paymentRepository.save(any(Payment.class))).thenReturn(samplePayment);

        // When
        paymentService.handleOrderCreated(orderEvent);

        // Allow async processing to complete
        Thread.sleep(1000);

        // Then
        verify(idempotencyService).isEventAlreadyProcessed("event-123", "saga-123");
        verify(paymentRepository, atLeastOnce()).save(any(Payment.class));
        verify(idempotencyService).markEventAsProcessed(
                "event-123", "saga-123", "OrderCreatedEvent", "payment-service", "order-123", null);
        verify(streamBridge).send(eq("paymentProcessed-out-0"), any());
    }

    @Test
    void handleOrderCreated_AlreadyProcessed_DoesNothing() {
        // Given
        when(idempotencyService.isEventAlreadyProcessed("event-123", "saga-123")).thenReturn(true);

        // When
        paymentService.handleOrderCreated(orderEvent);

        // Then
        verify(idempotencyService).isEventAlreadyProcessed("event-123", "saga-123");
        verify(paymentRepository, never()).save(any());
        verify(streamBridge, never()).send(anyString(), any());
    }

    @Test
    void handleOrderCreated_FailingPayment_SendsFailedEvent() throws InterruptedException {
        // Given
        ReflectionTestUtils.setField(paymentService, "failureRate", 1.0); // Force failure
        when(idempotencyService.isEventAlreadyProcessed("event-123", "saga-123")).thenReturn(false);
        when(paymentRepository.save(any(Payment.class))).thenReturn(samplePayment);

        // When
        paymentService.handleOrderCreated(orderEvent);

        // Allow async processing to complete
        Thread.sleep(1000);

        // Then
        verify(paymentRepository, atLeastOnce()).save(any(Payment.class));
        verify(streamBridge).send(eq("paymentFailed-out-0"), any());
    }

    @Test
    void processRefund_SuccessfulRefund_UpdatesStatusAndSendsEvent() {
        // Given
        samplePayment.setStatus(PaymentStatus.COMPLETED);
        when(paymentRepository.findByOrderId("order-123")).thenReturn(Optional.of(samplePayment));
        when(paymentRepository.save(any(Payment.class))).thenReturn(samplePayment);

        // When
        paymentService.processRefund("order-123", "saga-123", BigDecimal.valueOf(100.00));

        // Then
        verify(paymentRepository).findByOrderId("order-123");
        verify(paymentRepository).save(argThat(payment ->
                payment.getStatus() == PaymentStatus.REFUNDED &&
                        payment.getRefundedAt() != null));
        verify(streamBridge).send(eq("refundProcessed-out-0"), any());
    }

    @Test
    void getPaymentByOrderId_PaymentExists_ReturnsPayment() {
        // Given
        when(paymentRepository.findByOrderId("order-123")).thenReturn(Optional.of(samplePayment));

        // When
        Payment result = paymentService.getPaymentByOrderId("order-123");

        // Then
        assertThat(result).isEqualTo(samplePayment);
        verify(paymentRepository).findByOrderId("order-123");
    }

    @Test
    void getPaymentByOrderId_PaymentDoesNotExist_ThrowsException() {
        // Given
        when(paymentRepository.findByOrderId("order-999")).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> paymentService.getPaymentByOrderId("order-999"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Payment not found for order: order-999");
    }

    @Test
    void getPaymentBySagaId_PaymentExists_ReturnsPayment() {
        // Given
        when(paymentRepository.findBySagaId("saga-123")).thenReturn(Optional.of(samplePayment));

        // When
        Payment result = paymentService.getPaymentBySagaId("saga-123");

        // Then
        assertThat(result).isEqualTo(samplePayment);
        verify(paymentRepository).findBySagaId("saga-123");
    }

    @Test
    void getPaymentBySagaId_PaymentDoesNotExist_ThrowsException() {
        // Given
        when(paymentRepository.findBySagaId("saga-999")).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> paymentService.getPaymentBySagaId("saga-999"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Payment not found for saga: saga-999");
    }
}