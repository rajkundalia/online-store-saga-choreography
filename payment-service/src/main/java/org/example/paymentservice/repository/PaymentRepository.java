package org.example.paymentservice.repository;

import org.example.common.enums.PaymentStatus;
import org.example.paymentservice.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, String> {

    Optional<Payment> findByOrderId(String orderId);

    Optional<Payment> findBySagaId(String sagaId);

    List<Payment> findByStatus(PaymentStatus status);

    List<Payment> findByCustomerId(String customerId);

    @Query("SELECT p FROM Payment p WHERE p.orderId = :orderId AND p.status = :status")
    Optional<Payment> findByOrderIdAndStatus(@Param("orderId") String orderId,
                                             @Param("status") PaymentStatus status);
}