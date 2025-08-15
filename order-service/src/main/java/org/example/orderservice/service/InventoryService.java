package org.example.orderservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.orderservice.entity.Inventory;
import org.example.orderservice.repository.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    /**
     * Check if inventory is available for reservation
     */
    @Transactional(readOnly = true)
    public boolean isAvailable(String productId, Integer quantity) {
        return inventoryRepository.findAvailableQuantityByProductId(productId)
                .map(available -> available >= quantity)
                .orElse(false);
    }

    /**
     * Reserve inventory with pessimistic locking to prevent dirty reads
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void reserveInventory(String productId, Integer quantity, String sagaId) {
        log.info("Reserving inventory: productId={}, quantity={}, sagaId={}",
                productId, quantity, sagaId);

        Inventory inventory = inventoryRepository.findByProductIdWithLock(productId)
                .orElseThrow(() -> new IllegalStateException("Product not found: " + productId));

        if (!inventory.canReserve(quantity)) {
            log.error("Insufficient inventory: productId={}, available={}, requested={}",
                    productId, inventory.getAvailableQuantity(), quantity);
            throw new IllegalStateException("Insufficient inventory for product: " + productId);
        }

        inventory.reserve(quantity);
        inventoryRepository.save(inventory);

        log.info("Inventory reserved successfully: productId={}, quantity={}, remaining={}",
                productId, quantity, inventory.getAvailableQuantity());
    }

    /**
     * Release reserved inventory back to available pool
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void releaseReservation(String productId, Integer quantity, String sagaId) {
        log.info("Releasing inventory reservation: productId={}, quantity={}, sagaId={}",
                productId, quantity, sagaId);

        Inventory inventory = inventoryRepository.findByProductIdWithLock(productId)
                .orElseThrow(() -> new IllegalStateException("Product not found: " + productId));

        inventory.releaseReservation(quantity);
        inventoryRepository.save(inventory);

        log.info("Inventory reservation released: productId={}, quantity={}, available={}",
                productId, quantity, inventory.getAvailableQuantity());
    }

    /**
     * Get current inventory status
     */
    @Transactional(readOnly = true)
    public Inventory getInventory(String productId) {
        return inventoryRepository.findById(productId)
                .orElseThrow(() -> new IllegalStateException("Product not found: " + productId));
    }
}