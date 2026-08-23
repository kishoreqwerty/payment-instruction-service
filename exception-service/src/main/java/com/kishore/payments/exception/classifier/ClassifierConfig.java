package com.kishore.payments.exception.classifier;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A dedicated, small executor for classifier calls -- not the shared common pool, and not the
 * Kafka listener's own thread. The classifier is explicitly not on the critical path
 * (.notes/ARCHITECTURE.md section 10.4): giving it its own bounded pool means a slow or
 * unavailable model can never starve any other async work this service does, and vice versa.
 * Two threads, not one: a case open and a classifier call for an earlier case can overlap without
 * queueing behind each other, but this is deliberately small -- this is advisory, off-path work,
 * not something to scale for throughput.
 */
@Configuration
public class ClassifierConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService classifierExecutor() {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory namedDaemonThreads = runnable -> {
            Thread thread = new Thread(runnable, "classifier-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(2, namedDaemonThreads);
    }
}
