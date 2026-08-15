package com.kishore.payments.intake;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// scanBasePackages is explicit about com.kishore.payments.core:
// @SpringBootApplication's default scan is limited to this class's own
// package and sub-packages, which does not reach core's sibling package
// tree -- core.outbox.OutboxWriter and OutboxPublisher would otherwise never
// become beans. Every service that depends on core needs this same explicit
// scan. (A second, direct @ComponentScan alongside @SpringBootApplication
// isn't an option -- Spring rejects the annotation being present twice on
// one class -- so this uses @SpringBootApplication's own scanBasePackages
// attribute instead.)
//
// @EnableScheduling activates OutboxPublisher's @Scheduled poll loop; Spring
// Boot does not enable @Scheduled processing on its own.
@SpringBootApplication(scanBasePackages = {"com.kishore.payments.intake", "com.kishore.payments.core"})
@EnableScheduling
public class IntakeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntakeServiceApplication.class, args);
    }
}
