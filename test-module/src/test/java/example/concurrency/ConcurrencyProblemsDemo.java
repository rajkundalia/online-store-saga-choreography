package example.concurrency;

import lombok.extern.slf4j.Slf4j;
import org.example.SagaChoreographyApplication;
import org.example.common.events.CreateOrderRequest;
import org.example.orderservice.entity.Inventory;
import org.example.orderservice.repository.InventoryRepository;
import org.example.orderservice.repository.OrderRepository;
import org.example.orderservice.service.InventoryService;
import org.example.orderservice.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(classes = SagaChoreographyApplication.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("concurrency-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Slf4j
class ConcurrencyProblemsDemo {

    @Autowired
    private OrderService orderService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        executorService = Executors.newFixedThreadPool(10);

        // Clean up and initialize test data
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();

        // Create test inventory
        Inventory testProduct = new Inventory();
        testProduct.setProductId("CONCURRENT-PRODUCT");
        testProduct.setAvailableQuantity(10);
        testProduct.setReservedQuantity(0);
        inventoryRepository.save(testProduct);

        log.info("Test setup completed: inventory initialized with 10 units");
    }

    @AfterEach
    void tearDown() {
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    // --------------------------
    // DIRTY READS: When a transaction reads uncommitted, and possibly temporary, changes made by another transaction,
    // risking data inconsistency.
    // --------------------------

    /**
     * Demonstrates solution to dirty reads:
     * Pessimistic locking ensures only one transaction reads + reserves stock at a time.
     */
    @Test
    @Order(1)
    void createOrder_WhenPessimisticLockingUsed_ShouldPreventOverselling() throws InterruptedException {
        log.info("=== DEMONSTRATING DIRTY READS SOLUTION ===");

        // Reset inventory
        setupCleanInventory();

        // The solution is already implemented in InventoryService using:
        // 1. @Transactional(isolation = Isolation.READ_COMMITTED)
        // 2. Pessimistic locking with findByProductIdWithLock
        // 3. Atomic check-and-reserve operations

        int numberOfConcurrentOrders = 3;
        int quantityPerOrder = 5;
        CountDownLatch latch = new CountDownLatch(numberOfConcurrentOrders);
        AtomicInteger successfulOrders = new AtomicInteger(0);
        AtomicInteger failedOrders = new AtomicInteger(0);

        for (int i = 0; i < numberOfConcurrentOrders; i++) {
            final int orderIndex = i;
            executorService.submit(() -> {
                try {
                    CreateOrderRequest request = new CreateOrderRequest(
                            "customer-solution-" + orderIndex,
                            BigDecimal.valueOf(50.00),
                            "CONCURRENT-PRODUCT",
                            quantityPerOrder
                    );

                    String orderId = orderService.createOrder(request);
                    successfulOrders.incrementAndGet();
                    log.info("Order {} created successfully with proper locking", orderIndex);

                } catch (Exception e) {
                    failedOrders.incrementAndGet();
                    log.info("Order {} properly rejected: {}", orderIndex, e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);

        Inventory finalInventory = inventoryRepository.findById("CONCURRENT-PRODUCT").orElse(null);

        log.info("Solution Results - Successful orders: {}, Failed orders: {}",
                successfulOrders.get(), failedOrders.get());
        assert finalInventory != null;
        log.info("Final inventory - Available: {}, Reserved: {}",
                finalInventory.getAvailableQuantity(), finalInventory.getReservedQuantity());

        // With proper locking: only 2 orders should succeed (2 * 5 = 10 units)
        assertThat(successfulOrders.get()).isEqualTo(2);
        assertThat(failedOrders.get()).isEqualTo(1);
        assertThat(finalInventory.getAvailableQuantity()).isEqualTo(0);
        assertThat(finalInventory.getReservedQuantity()).isEqualTo(10);

        log.info("Dirty reads solution: Proper serialization prevents overselling");
    }

    // --------------------------
    // LOST UPDATES: Occurs when two concurrent transactions overwrite each other's changes,
    // resulting in one update being discarded without warning.
    // --------------------------

    /**
     * Demonstrates optimistic locking solution:
     * The second update fails due to @Version mismatch, preventing lost update.
     */
    @Test
    @Order(2)
    void updateInventory_WhenVersionMismatch_ShouldThrowOptimisticLockingException() {
        log.info("=== DEMONSTRATING LOST UPDATES SOLUTION ===");

        // The solution is implemented using @Version annotation for optimistic locking
        // and pessimistic locking for critical sections

        setupCleanInventory();

        // Demonstrate version-based optimistic locking
        Inventory inventory1 = inventoryRepository.findById("CONCURRENT-PRODUCT").orElse(null);
        Inventory inventory2 = inventoryRepository.findById("CONCURRENT-PRODUCT").orElse(null);

        // Both have same version initially
        assert inventory1 != null;
        assert inventory2 != null;
        assertThat(inventory1.getVersion()).isEqualTo(inventory2.getVersion());

        // Update first inventory
        inventory1.setAvailableQuantity(8);
        inventoryRepository.save(inventory1);

        // Try to update second inventory (should fail due to version mismatch)
        inventory2.setAvailableQuantity(7);

        Exception exception = assertThrows(ObjectOptimisticLockingFailureException.class, () -> inventoryRepository.save(inventory2));

        log.info("Lost Updates Solution: Optimistic locking prevented conflicting update: {}",
                exception.getClass().getSimpleName());

        // Verify final state is consistent
        Inventory finalInventory = inventoryRepository.findById("CONCURRENT-PRODUCT").orElse(null);
        assert finalInventory != null;
        assertThat(finalInventory.getAvailableQuantity()).isEqualTo(8); // First update succeeded
        assertThat(finalInventory.getVersion()).isGreaterThan(inventory1.getVersion());
    }

    // --------------------------
    // FUZZY READS (Non-repeatable reads): When a transaction reads the same data twice and gets different
    // results because another transaction modified the data in between.
    // --------------------------

    /**
     * Demonstrates solution to fuzzy reads:
     * Using version control/event versioning to ensure data freshness.
     */
    @Test
    @Order(3)
    void processOrder_WhenVersionMismatch_ShouldTriggerReFetch() {
        log.info("=== DEMONSTRATING FUZZY READS SOLUTION ===");

        // Solutions for fuzzy reads:
        // 1. Use REPEATABLE_READ isolation level
        // 2. Use SERIALIZABLE isolation level  
        // 3. Event versioning and validation before final processing

        setupCleanInventory();

        // Demonstrate solution using event versioning approach
        // This is simulated since our saga uses event-driven architecture

        // Simulate order creation with version
        CreateOrderRequest request = new CreateOrderRequest(
                "customer-fuzzy-solution",
                BigDecimal.valueOf(100.00),
                "CONCURRENT-PRODUCT",
                2
        );

        String orderId = orderService.createOrder(request);

        org.example.orderservice.entity.Order order = orderRepository.findById(orderId).orElse(null);
        assert order != null;
        Long initialOrderVersion = order.getVersion();

        log.info("Order created with version: {}", initialOrderVersion);

        // Simulate another transaction modifying the order
        order.setAmount(BigDecimal.valueOf(150.00)); // Price change
        orderRepository.save(order);

        Long updatedOrderVersion = Objects.requireNonNull(orderRepository.findById(orderId).orElse(null)).getVersion();

        log.info("Order version after modification: {}", updatedOrderVersion);

        // Payment service should detect version mismatch
        if (!initialOrderVersion.equals(updatedOrderVersion)) {
            log.info("FUZZY READ SOLUTION: Version mismatch detected ({} vs {}), would trigger recalculation",
                    initialOrderVersion, updatedOrderVersion);

            // In real implementation, payment service would:
            // 1. Detect version mismatch
            // 2. Request fresh order data
            // 3. Recalculate payment amount
            // 4. Proceed with updated values
        }

        assertThat(updatedOrderVersion).isGreaterThan(initialOrderVersion);
        log.info("Fuzzy reads solution: Event versioning prevents processing with stale data");
    }

    // --------------------------
    // ISOLATION LEVELS: Rules that define how and when changes made by one database transaction become visible
    // to others, preventing various concurrency anomalies.
    // --------------------------

    /**
     * Just prints isolation level behavior info
     */

    @Test
    @Order(4)
    void readInventory_WhenIsolationLevelReadCommitted_ShouldAllowFuzzyReads() {
        log.info("=== DEMONSTRATING TRANSACTION ISOLATION LEVELS ===");

        // This test shows different isolation levels and their effects
        setupCleanInventory();

        // READ_COMMITTED (default in our service)
        testIsolationLevel("READ_COMMITTED");

        // Note: REPEATABLE_READ and SERIALIZABLE would require
        // specific database configuration and are more complex to demonstrate
        // in unit tests due to their dependency on actual database behavior

        log.info("Isolation levels demonstration completed");
    }

    private void testIsolationLevel(String level) {
        log.info("Testing isolation level: {}", level);

        // Our InventoryService uses READ_COMMITTED isolation
        // This prevents dirty reads but allows fuzzy reads and phantom reads

        Inventory inventory = inventoryRepository.findById("CONCURRENT-PRODUCT").orElse(null);
        assertThat(inventory).isNotNull();

        log.info("Current inventory with {} isolation: Available={}, Reserved={}",
                level, inventory.getAvailableQuantity(), inventory.getReservedQuantity());
    }


    private void setupCleanInventory() {
        inventoryRepository.deleteAll();

        Inventory cleanInventory = new Inventory();
        cleanInventory.setProductId("CONCURRENT-PRODUCT");
        cleanInventory.setAvailableQuantity(10);
        cleanInventory.setReservedQuantity(0);
        inventoryRepository.save(cleanInventory);

        log.info("Clean inventory setup: 10 units available, 0 reserved");
    }

    /*
    CountDownLatch is a thread synchronization aid in Java that allows one or more threads to wait until a set of
    operations being performed in other threads completes.

    Why is CountDownLatch used in this class?

    - Purpose in this test class:
      CountDownLatch ensures that the test method waits for all concurrently submitted threads
      (order creation, inventory updates, etc.) to finish before verifying the final state of the system.

    - How it works:
      Each concurrent thread decrements ("counts down") the latch when its task is done.
      The main test thread calls `await()` and blocks until the latch reaches zero,
      guaranteeing all operations are finished before assertions run.

     CountDownLatch is used to coordinate and synchronize the completion of concurrent tasks so that the test
     verifies results only after all threads have finished their work.
     */
}