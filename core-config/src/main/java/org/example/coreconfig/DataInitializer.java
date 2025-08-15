package org.example.coreconfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.orderservice.entity.Inventory;
import org.example.orderservice.repository.InventoryRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final InventoryRepository inventoryRepository;

    @Override
    @Transactional
    public void run(String... args) {
        initializeInventory();
    }

    private void initializeInventory() {
        if (inventoryRepository.count() == 0) {
            log.info("Initializing inventory data...");

            // Create sample inventory items
            Inventory product1 = new Inventory();
            product1.setProductId("PROD-001");
            product1.setAvailableQuantity(100);
            product1.setReservedQuantity(0);

            Inventory product2 = new Inventory();
            product2.setProductId("PROD-002");
            product2.setAvailableQuantity(50);
            product2.setReservedQuantity(0);

            Inventory product3 = new Inventory();
            product3.setProductId("PROD-003");
            product3.setAvailableQuantity(10);
            product3.setReservedQuantity(0);

            inventoryRepository.save(product1);
            inventoryRepository.save(product2);
            inventoryRepository.save(product3);

            log.info("Initialized inventory: PROD-001(100), PROD-002(50), PROD-003(10)");
        }
    }
}