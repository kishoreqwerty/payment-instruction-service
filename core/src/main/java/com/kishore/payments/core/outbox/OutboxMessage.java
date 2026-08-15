package com.kishore.payments.core.outbox;

import java.util.Map;
import java.util.UUID;

/**
 * What {@link OutboxWriter} inserts into core.outbox. {@code payload} is
 * whatever event object the caller wants serialized (e.g.
 * {@link com.kishore.payments.core.event.InstructionReceivedEvent}); this
 * type doesn't know or care about the shape of any particular event.
 */
public record OutboxMessage(UUID aggregateId, String topic, String partitionKey, Map<String, Object> headers, Object payload) {
}
