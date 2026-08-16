package com.kishore.payments.gateway;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One injectable Clock, so timestamps written by this service and tests substituting a fixed one always agree. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
