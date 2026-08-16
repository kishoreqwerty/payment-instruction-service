package com.kishore.payments.gateway.dispatch;

import com.kishore.payments.gateway.GatewayProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class DispatchConfig {

    @Bean
    public RestTemplate railRestTemplate(RestTemplateBuilder builder, GatewayProperties properties) {
        return builder.setConnectTimeout(properties.connectTimeout()).setReadTimeout(properties.readTimeout()).build();
    }
}
