package org.example.orderservice.repository;

import org.example.common.enums.OrderStatus;
import org.example.orderservice.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {

    List<Order> findByCustomerId(String customerId);

    List<Order> findByStatus(OrderStatus status);

    Optional<Order> findBySagaId(String sagaId);

    // PESSIMISTIC_WRITE allows us to obtain an exclusive lock and prevent the data from being read, updated or deleted
    // by other transactions. The lock is retained until the transaction commits or rolls back.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.orderId = :orderId")
    Optional<Order> findByIdWithLock(@Param("orderId") String orderId);
}