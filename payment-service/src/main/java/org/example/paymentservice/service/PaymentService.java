package org.example.paymentservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.enums.PaymentStatus;
import org.example.common.events.OrderCreatedEvent;
import org.example.common.events.PaymentFailedEvent;
import org.example.common.events.PaymentProcessedEvent;
import org.example.common.events.RefundProcessedEvent;
import org.example.common.service.IdempotencyService;
import org.example.common.util.EventIdGenerator;
import org.example.paymentservice.entity.Payment;
import org.example.paymentservice.repository.PaymentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final StreamBridge streamBridge;
    private final IdempotencyService idempotencyService;

    @Value("${saga.payment.processing-delay:PT0S}")
    private Duration processingDelay;

    @Value("${saga.payment.timeout-threshold:PT30S}")
    private Duration timeoutThreshold;

    @Value("${saga.payment.failure-rate:0.0}")
    private double failureRate;

    @Value("${saga.payment.enable-timeout:false}")
    private boolean enableTimeout;

    /**
     * Handle order created - start payment processing
     */
    @EventListener
    @Async
    @Transactional
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("Handling OrderCreatedEvent: orderId={}, sagaId={}, amount={}",
                event.getOrderId(), event.getSagaId(), event.getAmount());

        // Check idempotency
        if (idempotencyService.isEventAlreadyProcessed(event.getEventId(), event.getSagaId())) {
            log.info("OrderCreatedEvent already processed: eventId={}, sagaId={}",
                    event.getEventId(), event.getSagaId());
            return;
        }

        try {
            processPayment(event);

            // Mark event as processed
            idempotencyService.markEventAsProcessed(
                    event.getEventId(),
                    event.getSagaId(),
                    "OrderCreatedEvent",
                    "payment-service",
                    event.getOrderId(),
                    null
            );

        } catch (Exception e) {
            log.error("Error handling OrderCreatedEvent: orderId={}, sagaId={}, error={}",
                    event.getOrderId(), event.getSagaId(), e.getMessage(), e);

            publishPaymentFailedEvent(event, "PROCESSING_ERROR", e.getMessage());
        }
    }

    /**
     * Process payment with artificial delay and timeout handling
     */
    private void processPayment(OrderCreatedEvent event) {
        String paymentId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        // Create payment record
        Payment payment = new Payment();
        payment.setPaymentId(paymentId);
        payment.setOrderId(event.getOrderId());
        payment.setCustomerId(event.getCustomerId());
        payment.setAmount(event.getAmount());
        payment.setStatus(PaymentStatus.PROCESSING);
        payment.setSagaId(event.getSagaId());
        payment.setPaymentMethod("CREDIT_CARD");
        payment.setTransactionId("TXN-" + UUID.randomUUID());

        paymentRepository.save(payment);

        log.info("Payment processing started: paymentId={}, orderId={}, amount={}, delay={}ms",
                paymentId, event.getOrderId(), event.getAmount(), processingDelay.toMillis());

        // Process with timeout handling
        CompletableFuture<Void> processingTask = CompletableFuture.runAsync(() -> {
            try {
                // Simulate processing delay
                if (!processingDelay.isZero()) {
                    Thread.sleep(processingDelay.toMillis());
                }

                // Simulate random failures
                if (Math.random() < failureRate) {
                    throw new RuntimeException("Simulated payment failure");
                }

                // Payment successful
                long processingTime = System.currentTimeMillis() - startTime;
                completePayment(payment, processingTime);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Payment processing interrupted", e);
            }
        });

        try {
            if (enableTimeout && !timeoutThreshold.isZero()) {
                // Wait with timeout
                processingTask.get(timeoutThreshold.toMillis(), TimeUnit.MILLISECONDS);
            } else {
                // Wait without timeout
                processingTask.get();
            }
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("Payment processing timeout: paymentId={}, orderId={}, timeout={}ms",
                    paymentId, event.getOrderId(), timeoutThreshold.toMillis());

            handlePaymentTimeout(payment, event);
        } catch (Exception e) {
            log.error("Payment processing failed: paymentId={}, orderId={}, error={}",
                    paymentId, event.getOrderId(), e.getMessage());

            handlePaymentFailure(payment, event, e.getMessage());
        }
    }

    /**
     * Complete successful payment
     */
    @Transactional
    protected void completePayment(Payment payment, long processingTimeMs) {
        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setProcessingTimeMs(processingTimeMs);
        paymentRepository.save(payment);

        PaymentProcessedEvent event = new PaymentProcessedEvent(
                EventIdGenerator.generateEventId(),
                payment.getSagaId(),
                payment.getOrderId(),
                payment.getPaymentId(),
                payment.getAmount(),
                PaymentStatus.COMPLETED.name(),
                LocalDateTime.now()
        );

        streamBridge.send("paymentProcessed-out-0", event);

        log.info("Payment completed successfully: paymentId={}, orderId={}, amount={}, processingTime={}ms",
                payment.getPaymentId(), payment.getOrderId(), payment.getAmount(), processingTimeMs);
    }

    /**
     * Handle payment timeout
     */
    @Transactional
    protected void handlePaymentTimeout(Payment payment, OrderCreatedEvent orderEvent) {
        payment.setStatus(PaymentStatus.TIMEOUT);
        payment.setFailureReason("Payment processing timeout");
        paymentRepository.save(payment);

        publishPaymentFailedEvent(orderEvent, "TIMEOUT", "Payment processing exceeded timeout threshold");
    }

    /**
     * Handle payment failure
     */
    @Transactional
    protected void handlePaymentFailure(Payment payment, OrderCreatedEvent orderEvent, String reason) {
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureReason(reason);
        paymentRepository.save(payment);

        publishPaymentFailedEvent(orderEvent, "PROCESSING_FAILED", reason);
    }

    /**
     * Publish payment failed event
     */
    private void publishPaymentFailedEvent(OrderCreatedEvent orderEvent, String errorCode, String reason) {
        PaymentFailedEvent failedEvent = new PaymentFailedEvent(
                EventIdGenerator.generateEventId(),
                orderEvent.getSagaId(),
                orderEvent.getOrderId(),
                orderEvent.getAmount(),
                reason,
                errorCode,
                LocalDateTime.now()
        );

        streamBridge.send("paymentFailed-out-0", failedEvent);

        log.info("Published PaymentFailedEvent: orderId={}, sagaId={}, reason={}",
                orderEvent.getOrderId(), orderEvent.getSagaId(), reason);
    }

    /**
     * Handle refund request (compensation)
     */
    @Transactional
    public void processRefund(String orderId, String sagaId, BigDecimal refundAmount) {
        log.info("Processing refund: orderId={}, sagaId={}, amount={}", orderId, sagaId, refundAmount);

        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("Payment not found for order: " + orderId));

        // Update payment status
        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setRefundedAt(LocalDateTime.now());
        paymentRepository.save(payment);

        // Publish refund processed event
        RefundProcessedEvent refundEvent = new RefundProcessedEvent(
                EventIdGenerator.generateEventId(),
                sagaId,
                orderId,
                payment.getPaymentId(),
                refundAmount,
                LocalDateTime.now()
        );

        streamBridge.send("refundProcessed-out-0", refundEvent);

        log.info("Refund processed successfully: orderId={}, paymentId={}, amount={}",
                orderId, payment.getPaymentId(), refundAmount);
    }

    /**
     * Get payment by order ID
     */
    public Payment getPaymentByOrderId(String orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("Payment not found for order: " + orderId));
    }

    /**
     * Get payment by saga ID
     */
    public Payment getPaymentBySagaId(String sagaId) {
        return paymentRepository.findBySagaId(sagaId)
                .orElseThrow(() -> new IllegalStateException("Payment not found for saga: " + sagaId));
    }
}