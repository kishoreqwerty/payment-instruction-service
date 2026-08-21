package com.kishore.payments.railsim;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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

    /**
     * Replaces Boot's auto-configured {@link RestTemplateBuilder} with one pinned to the
     * JDK-backed {@link SimpleClientHttpRequestFactory} (Phase 10): Micrometer Tracing's OTLP
     * exporter pulls in OkHttp as a transitive runtime dependency for its own trace-export calls,
     * and without this, Boot's classpath auto-detection would silently switch every {@code
     * RestTemplate} built from the default builder -- including this class's own {@link
     * #railCallbackRestTemplate} and, in tests, {@code TestRestTemplate} -- to OkHttp, which
     * retries once on a connection failure by default. That is exactly the behavior {@code
     * ConnectionDropper}'s DROP scenario exists to defeat: a forcibly reset connection would be
     * silently retried and succeed, so a test asserting the client sees the drop as a raw
     * transport failure would stop seeing one. See {@code DispatchConfig#railRestTemplate} in
     * settlement-gateway for the same fix on the other side of this simulator's HTTP surface.
     */
    @Bean
    public RestTemplateBuilder restTemplateBuilder() {
        return new RestTemplateBuilder().requestFactory(SimpleClientHttpRequestFactory::new);
    }
}
