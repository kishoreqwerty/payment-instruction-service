package com.kishore.payments.railsim;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * One shared scheduler for everything that happens "later": an accept delay,
 * a TIMEOUT hold, a confirmation or return callback. A dedicated pool
 * (not the Tomcat request-handling threads) so a 30-second TIMEOUT hold on
 * one payment never reduces the container's capacity to accept the next
 * request.
 */
@Configuration
public class SchedulingConfig {

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService railSimulatorScheduler() {
        return Executors.newScheduledThreadPool(8);
    }

    @Bean
    public RestTemplate railCallbackRestTemplate() {
        return new RestTemplate();
    }
}
