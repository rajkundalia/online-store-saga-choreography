package org.example.orderservice.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "inventory")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Inventory {

    @Id
    private String productId;

    @Column(name = "available_quantity", nullable = false)
    private Integer availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private Integer reservedQuantity;

    @Version
    private Long version;

    @Column(name = "last_updated")
    private LocalDateTime lastUpdated;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        lastUpdated = LocalDateTime.now();
    }

    public boolean canReserve(Integer quantity) {
        return availableQuantity >= quantity;
    }

    public void reserve(Integer quantity) {
        if (!canReserve(quantity)) {
            throw new IllegalStateException("Insufficient inventory for product: " + productId);
        }
        availableQuantity -= quantity;
        reservedQuantity += quantity;
    }

    public void releaseReservation(Integer quantity) {
        if (reservedQuantity < quantity) {
            throw new IllegalStateException("Cannot release more than reserved for product: " + productId);
        }
        reservedQuantity -= quantity;
        availableQuantity += quantity;
    }
}