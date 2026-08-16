package com.kishore.payments.railsim.state;

import com.kishore.payments.railsim.dispatch.InboundPayment;
import java.time.Instant;

/**
 * One payment this rail has on file, stored as the value in a {@code
 * ConcurrentHashMap} keyed by UETR (see RailState) -- immutable, so a status
 * change is a {@code map.put} of a new instance via {@link #withRailStatus},
 * not a mutation, and thread-safety comes from the map itself. {@code
 * railStatus} starts null (accepted for processing, disposition not yet
 * decided) and is set once a confirmation is generated.
 */
public record RecordedPayment(InboundPayment payment, Instant receivedAt, String railStatus) {

    public RecordedPayment(InboundPayment payment, Instant receivedAt) {
        this(payment, receivedAt, null);
    }

    public RecordedPayment withRailStatus(String railStatus) {
        return new RecordedPayment(payment, receivedAt, railStatus);
    }
}
