package com.kishore.payments.gateway.dispatch;

import com.kishore.payments.gateway.GatewayProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class DispatchConfig {

    /**
     * Explicitly pinned to the JDK-backed {@link SimpleClientHttpRequestFactory} rather than left
     * to {@link RestTemplateBuilder}'s classpath auto-detection (Phase 10): Micrometer Tracing's
     * OTLP exporter pulls in OkHttp as a transitive runtime dependency (for its own trace-export
     * HTTP calls, unrelated to this client), and Boot's auto-detection would otherwise silently
     * switch this bean to OkHttp too -- which defaults to retrying once on a connection failure.
     * That would race with, and partially duplicate, the deliberate 5-attempt backoff policy this
     * client already implements at the application level (payments.gateway.dispatch-retry) with
     * a transport-level retry it never asked for and can't see. Pinning keeps this bean's behavior
     * a function of this file, not of whatever else happens to be on the classpath.
     */
    @Bean
    public RestTemplate railRestTemplate(RestTemplateBuilder builder, GatewayProperties properties) {
        return builder
                .requestFactory(SimpleClientHttpRequestFactory::new)
                .setConnectTimeout(properties.connectTimeout())
                .setReadTimeout(properties.readTimeout())
                .build();
    }
}
