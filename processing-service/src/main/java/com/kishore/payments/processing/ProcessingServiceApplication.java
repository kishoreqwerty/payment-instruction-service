package com.kishore.payments.processing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

// core's beans (OutboxWriter, OutboxPublisher, OutboxRetentionCleaner,
// InstructionStateWriter, the state machine) reach this application via
// PaymentCoreAutoConfiguration -- see
// core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports --
// with no scanBasePackages needed, same as intake-service.
//
// @EnableScheduling activates OutboxPublisher's and OutboxRetentionCleaner's
// @Scheduled loops. @EnableKafka activates @KafkaListener processing for
// this service's own consumer. spring.main.web-application-type is set to
// "none" in application.yml: this service has no HTTP API, only a Kafka
// consumer and actuator endpoints.
@SpringBootApplication
@EnableScheduling
@EnableKafka
public class ProcessingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcessingServiceApplication.class, args);
    }
}
