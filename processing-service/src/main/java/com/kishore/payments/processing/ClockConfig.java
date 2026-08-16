package com.kishore.payments.processing;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One injectable Clock, so validation's "not in the past" rule, enrichment's cutoff link, and RailRouter's urgency check all agree on "now" -- and so tests can substitute a fixed one. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
