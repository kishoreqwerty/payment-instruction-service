package com.kishore.payments.core.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The Jackson configuration every outbox event and header uses: snake_case
 * field names, ISO-8601 timestamps with offset (not epoch millis). Kept
 * separate from whatever general-purpose ObjectMapper a hosting service
 * configures for its own REST API, which may reasonably use different
 * conventions (camelCase, for instance -- see IntakeController's responses).
 */
public final class EventJson {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private EventJson() {
    }
}
