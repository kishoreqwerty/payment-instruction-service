package com.kishore.payments.intake;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// core's beans (OutboxWriter, OutboxPublisher, InstructionStateWriter, the
// state machine) reach this application via
// PaymentCoreAutoConfiguration -- see
// core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports --
// rather than scanBasePackages. Auto-configuration isn't scoped to this
// class's package, so no explicit scan is needed for core to be picked up.
//
// @EnableScheduling activates OutboxPublisher's @Scheduled poll loop; Spring
// Boot does not enable @Scheduled processing on its own.
@SpringBootApplication
@EnableScheduling
public class IntakeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntakeServiceApplication.class, args);
    }
}
