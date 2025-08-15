package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "org.example.orderservice",
        "org.example.paymentservice",
        "org.example.common"
})
@EnableJpaRepositories(basePackages = {
        "org.example.orderservice",
        "org.example.paymentservice",
        "org.example.common"
})
@EnableScheduling
@EnableAsync
public class SagaChoreographyApplication {

    public static void main(String[] args) {
        SpringApplication.run(SagaChoreographyApplication.class, args);
    }
}