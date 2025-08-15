package org.example.orderservice.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.events.CreateOrderRequest;
import org.example.orderservice.entity.Order;
import org.example.orderservice.service.InventoryService;
import org.example.orderservice.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;
    private final InventoryService inventoryService;

    /**
     * Create a new order
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> createOrder(@RequestBody CreateOrderRequest request) {
        try {
            log.info("Received create order request: customerId={}, amount={}, productId={}, quantity={}",
                    request.getCustomerId(), request.getAmount(), request.getProductId(), request.getQuantity());

            String orderId = orderService.createOrder(request);

            return ResponseEntity.ok(Map.of(
                    "orderId", orderId,
                    "status", "Order created and saga initiated",
                    "message", "Order is being processed"
            ));

        } catch (IllegalStateException e) {
            log.error("Failed to create order: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "status", "failed"
            ));
        } catch (Exception e) {
            log.error("Unexpected error creating order: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Internal server error",
                    "status", "failed"
            ));
        }
    }

    /**
     * Get order by ID
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<Order> getOrder(@PathVariable String orderId) {
        try {
            Order order = orderService.getOrder(orderId);
            return ResponseEntity.ok(order);
        } catch (IllegalStateException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get inventory status for a product
     */
    @GetMapping("/inventory/{productId}")
    public ResponseEntity<Map<String, Object>> getInventory(@PathVariable String productId) {
        try {
            var inventory = inventoryService.getInventory(productId);
            return ResponseEntity.ok(Map.of(
                    "productId", inventory.getProductId(),
                    "availableQuantity", inventory.getAvailableQuantity(),
                    "reservedQuantity", inventory.getReservedQuantity(),
                    "lastUpdated", inventory.getLastUpdated()
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.notFound().build();
        }
    }
}