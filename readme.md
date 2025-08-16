[![Java CI with Maven](https://github.com/rajkundalia/online-store-saga-choreography/actions/workflows/maven.yml/badge.svg)](https://github.com/rajkundalia/online-store-saga-choreography/actions/workflows/maven.yml)

# Spring Boot Saga Choreography Pattern - Complete Implementation

## **Note:** The best way to learn about this would be understanding the test scenarios!

A comprehensive Spring Boot project demonstrating **Choreography-based Saga Pattern** covering all essential distributed system challenges 
in a 2-service architecture with Spring Cloud Stream Test Binder.

## Key Features Implemented

### ✅ Rollback Mechanism with Local Compensating Transactions

**Order Service Compensation:**
- `CancelOrder` → Updates order status to CANCELLED
- `RestockItems` → Returns reserved items to inventory

**Payment Service Compensation:**
- `RefundPayment` → Processes payment refund
- `UpdateLedger` → Reverses accounting entries

**Flow Examples:**
```
SUCCESS: OrderCreated → PaymentProcessed → OrderCompleted
FAILURE: OrderCreated → PaymentFailed → RefundPayment → CancelOrder → RestockItems
```

### ✅ Comprehensive Idempotency Implementation

- **Idempotent Token System** using `ProcessedEvent` entity
- **Detection & Skip Logic** for duplicate event processing
- **Database-backed** idempotency tracking with unique constraints
- **Service-level** idempotency checks in all event handlers

### Timeout Handling with Artificial Delays

**Configuration Options:**
```yaml
saga:
  payment:
    processing-delay: PT10S    # Artificial delay
    timeout-threshold: PT8S    # Shorter than delay = timeout
    enable-timeout: true       # Enable timeout scenarios
    failure-rate: 0.3          # 30% random failure rate
```

**Timeout Scenarios:**
- Normal flow: Payment completes within timeout
- Timeout flow: Payment exceeds threshold → compensation triggered
- Configurable delays and thresholds for testing

### Concurrency Problem Demonstrations

#### **A. Dirty Reads Problem & Solution**
- **Problem:** Multiple sagas read same inventory simultaneously
- **Solution:** `READ_COMMITTED` isolation + pessimistic locking
- **Implementation:** `@Lock(LockModeType.PESSIMISTIC_WRITE)` in repositories

#### **B. Lost Updates Problem & Solution**
- **Problem:** Concurrent updates overwrite each other
- **Solution:** Optimistic locking with `@Version` annotation
- **Implementation:** Version-based conflict detection and retry

#### **C. Fuzzy/Non-repeatable Reads Problem & Solution**
- **Problem:** Same data read twice returns different values
- **Solution:** Event versioning + validation before processing
- **Implementation:** Order version checking in payment processing

### Event-Driven Architecture

**Event Flow:**
```
OrderCreatedEvent → PaymentService
PaymentProcessedEvent → OrderService  
PaymentFailedEvent → OrderService (triggers compensation)
```

**Spring Cloud Stream Integration:**
- Test Binder for simplified in-memory messaging
- No external message brokers required
- Configurable event routing and processing

## Quick Start

### Prerequisites
- Java 17+
- Maven 3.6+
- No external dependencies (H2 database, test binder included)

### Setup and Run
```bash
# Clone and build
git clone https://github.com/rajkundalia/online-store-saga-choreography.git
cd online-store-saga-choreography
mvn clean install

cd app-runner

# Run application
mvn spring-boot:run

# Access H2 Console
open http://localhost:8080/h2-console
# JDBC URL: jdbc:h2:file:./saga-choreography-db
# Username: sa (no password)
```

### Test API Endpoints

**Create Order (Success Scenario):**
```bash
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "customer-123",
    "amount": 100.00,
    "productId": "PROD-001",
    "quantity": 2
  }'
```

**Check Order Status:**
```bash
curl http://localhost:8080/orders/{orderId}
```

**Check Inventory:**
```bash
curl http://localhost:8080/orders/inventory/PROD-001
```

### Run Tests

**All Tests (Self-Contained):**
```bash
mvn test
```

**Integration Tests:**
```bash
mvn test -Dtest=SagaIntegrationTest
mvn test -Dtest=SagaTimeoutIntegrationTest
```

**Concurrency Demonstrations:**
```bash
mvn test -Dtest=ConcurrencyProblemsDemo
```

**Service Unit Tests:**
```bash
mvn test -Dtest=OrderServiceTest
mvn test -Dtest=PaymentServiceTest
```

## Testing Scenarios

### 1. Success Flow Testing
- Order creation with sufficient inventory
- Payment processing completion
- Order status updates to COMPLETED
- Event traceability verification

### 2. Compensation Flow Testing
- Payment failure scenarios
- Automatic order cancellation
- Inventory restock verification
- Compensation event publishing

### 3. Idempotency Testing
- Duplicate event processing
- Database constraint verification
- Skip logic validation
- Event deduplication

### 4. Concurrency Testing
- **Dirty Reads:** Multiple sagas reading same data
- **Lost Updates:** Concurrent modifications
- **Fuzzy Reads:** Non-repeatable read scenarios
- **Solutions:** Locking strategies and isolation levels

### 5. Timeout Testing
```bash
# Run with timeout profile
mvn spring-boot:run -Dspring.profiles.active=timeout-demo

# This configures:
# - processing-delay: PT10S (10 seconds)
# - timeout-threshold: PT8S (8 seconds)
# - enable-timeout: true
```

### 6. Failure Simulation
```bash
# Run with failure profile  
mvn spring-boot:run -Dspring.profiles.active=failure-demo

# This configures:
# - failure-rate: 0.5 (50% payment failures)
# - processing-delay: PT1S
```

## Architecture Highlights

### **Multi-Module Maven Structure**
- **common:** Shared events, entities, utilities
- **order-service:** Order management and inventory
- **payment-service:** Payment processing with timeouts
- **app-runner:** Main class for running the app
- **core-config:** Contains listener config and initial data

### **Database Design**
- **H2 File-based:** Persistent across restarts
- **Optimistic Locking:** `@Version` for conflict detection
- **Pessimistic Locking:** `SELECT FOR UPDATE` for critical sections
- **Idempotency Tracking:** `processed_events` table

### **Event Processing**
- **Asynchronous:** `@Async` event handlers
- **Reliable:** Database-backed idempotency
- **Traceable:** Correlation IDs and saga IDs
- **Configurable:** Test binder for development

### **Observability**
- **Structured Logging:** Correlation ID tracking
- **H2 Console:** Real-time database inspection
- **Event Tracing:** ProcessedEvent audit trail
- **Health Endpoints:** Spring Actuator integration

## Configuration Profiles

### Default Profile
```yaml
saga:
  payment:
    processing-delay: PT2S
    timeout-threshold: PT5S
    failure-rate: 0.0
    enable-timeout: false
```

### Timeout Demo Profile
```yaml
spring:
  profiles:
    active: timeout-demo
saga:
  payment:
    processing-delay: PT10S  # 10 seconds delay
    timeout-threshold: PT8S  # 8 seconds timeout
    enable-timeout: true
```

### Failure Demo Profile
```yaml
spring:
  profiles:
    active: failure-demo
saga:
  payment:
    failure-rate: 0.5  # 50% failure rate
    processing-delay: PT1S
```

### Concurrency Test Profile
```yaml
spring:
  profiles:
    active: concurrency-test
logging:
  level:
    org.springframework.transaction: DEBUG
    org.hibernate.SQL: DEBUG
```

## Learning Outcomes

After working with this project, you will understand:

1. **Choreography Pattern** - Decentralized saga coordination through events
2. **Compensation Patterns** - Implementing reliable rollback chains
3. **Idempotency** - Safe retry handling in distributed systems
4. **Timeout Management** - Dealing with slow services and network issues
5. **Concurrency Control** - Solving dirty reads, lost updates, fuzzy reads
6. **Testing Strategies** - Unit, integration, and concurrency testing
7. **Event-Driven Architecture** - Asynchronous message-based coordination

## Implementation Notes

### **Constructor Injection**
All services use constructor injection for better testability and immutability.

### **Lombok Integration**
Reduces boilerplate code with `@Data`, `@RequiredArgsConstructor`, etc.

### **Error Handling**
Comprehensive exception handling with specific error types and recovery strategies.

### **Transaction Management**
- `@Transactional` with appropriate isolation levels
- Rollback strategies for compensation scenarios
- Pessimistic and optimistic locking where needed

### **Async Processing**
- `@Async` for event handlers to prevent blocking
- `CompletableFuture` for timeout handling
- Thread pool management for concurrent processing

## Monitoring and Debugging

### **H2 Console Access**
```
URL: http://localhost:8080/h2-console
JDBC URL: jdbc:h2:file:./saga-choreography-db
Username: sa
Password: (empty)
```

### **Key Tables to Monitor**
- `orders` - Order lifecycle and status
- `payments` - Payment processing and failures
- `inventory` - Stock levels and reservations
- `processed_events` - Idempotency tracking`