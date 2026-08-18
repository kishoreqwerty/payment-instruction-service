package com.kishore.payments.railsim;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.boot.web.client.RestTemplateBuilder;
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

    /**
     * Connect/read timeouts, not a bare {@code new RestTemplate()}: a
     * scheduled confirmation or return runs on {@link
     * #railSimulatorScheduler}, a fixed 8-thread pool, and an unbounded
     * HTTP client backing it means one callback delivery that never gets a
     * response -- a dead receiver, a connection reset mid-response,
     * anything -- can pin one of only 8 threads for the rest of the
     * process's life. In the test suite specifically, {@code
     * AbstractRailSimulatorTest} caches one Spring context (and so one
     * scheduler instance) across every test class with no {@code
     * @DirtiesContext}, so a single stuck thread from an early test's
     * callback silently reduces capacity for every later test in the same
     * run -- symptoms that look exactly like an unrelated later test's
     * callback simply never arriving, since by then there may be no free
     * thread left to run it. {@code settlement-gateway}'s own outbound
     * client to this simulator (DispatchConfig) already gets this
     * treatment; this is the same fix on the callback direction.
     */
    @Bean
    public RestTemplate railCallbackRestTemplate(RestTemplateBuilder builder) {
        return builder.setConnectTimeout(Duration.ofSeconds(2)).setReadTimeout(Duration.ofSeconds(5)).build();
    }
}
