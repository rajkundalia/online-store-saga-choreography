package org.example.common.util;

import java.util.UUID;

public class EventIdGenerator {

    public static String generateEventId() {
        return UUID.randomUUID().toString();
    }

    public static String generateSagaId() {
        return "saga-" + UUID.randomUUID();
    }

    public static String generateCorrelationId() {
        return "corr-" + UUID.randomUUID();
    }
}