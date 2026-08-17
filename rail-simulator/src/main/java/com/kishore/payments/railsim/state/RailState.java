package com.kishore.payments.railsim.state;

import com.kishore.payments.railsim.scenario.ScenarioConfig;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Everything this rail knows, entirely in memory -- a restart is a
 * legitimate way to reset it (§9 of the phase brief). Every field here is
 * exactly what {@link #reset} must clear: the request ordinal {@code
 * everyNth} counts against, every recorded payment, and every callback
 * scheduled but not yet delivered.
 */
public class RailState {

    private final RailId railId;
    private final AtomicReference<ScenarioConfig> scenario = new AtomicReference<>();
    private final AtomicLong requestOrdinal = new AtomicLong(0);
    private final Map<String, RecordedPayment> recorded = new ConcurrentHashMap<>();
    private final Set<ScheduledFuture<?>> pendingCallbacks = new CopyOnWriteArraySet<>();
    private final Map<String, AtomicInteger> errorAttempts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> duplicateReceiptCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> statusQueryAttempts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> deliveryAttempts = new ConcurrentHashMap<>();

    public RailState(RailId railId, ScenarioConfig initialScenario) {
        this.railId = railId;
        this.scenario.set(initialScenario);
    }

    public RailId railId() {
        return railId;
    }

    public ScenarioConfig scenario() {
        return scenario.get();
    }

    /** Loading a new scenario resets counters, recorded payments and pending callbacks -- every test starts from a known point (§7). */
    public void loadScenario(ScenarioConfig newScenario) {
        scenario.set(newScenario);
        reset();
    }

    public void reset() {
        requestOrdinal.set(0);
        recorded.clear();
        for (ScheduledFuture<?> callback : pendingCallbacks) {
            callback.cancel(false);
        }
        pendingCallbacks.clear();
        errorAttempts.clear();
        duplicateReceiptCounts.clear();
        statusQueryAttempts.clear();
        deliveryAttempts.clear();
    }

    /** The 1-based ordinal of the request that just arrived, for everyNth matching. */
    public long nextRequestOrdinal() {
        return requestOrdinal.incrementAndGet();
    }

    /**
     * First receipt for a UETR is recorded as-is; a later one for a UETR
     * already on file is a duplicate delivery -- exactly what a redispatch
     * carrying the same UETR as its original produces if the original
     * turns out to have arrived after all. The original's data (received
     * time, raw bytes) is kept rather than overwritten, and the duplicate
     * is only counted, via {@link #duplicateReceiptCount}: this is how a
     * test proves a redispatch was recognised as a duplicate rather than
     * processed as a second, distinct payment.
     */
    public void record(RecordedPayment payment) {
        String uetr = payment.payment().uetr();
        RecordedPayment previous = recorded.putIfAbsent(uetr, payment);
        if (previous != null) {
            duplicateReceiptCounts.computeIfAbsent(uetr, id -> new AtomicInteger(0)).incrementAndGet();
        }
    }

    /** How many times a payment has been received for this UETR after the first -- 0 if never or only received once. */
    public int duplicateReceiptCount(String uetr) {
        AtomicInteger count = duplicateReceiptCounts.get(uetr);
        return count == null ? 0 : count.get();
    }

    /** The 1-based count of ERROR_5XX attempts recorded so far for this UETR, after incrementing for the current one. */
    public int nextErrorAttempt(String uetr) {
        return errorAttempts.computeIfAbsent(uetr, id -> new AtomicInteger(0)).incrementAndGet();
    }

    /** The 1-based count of status queries answered so far for this UETR, after incrementing for the current one -- for UNKNOWN_THEN_KNOWN. */
    public int nextStatusQueryAttempt(String uetr) {
        return statusQueryAttempts.computeIfAbsent(uetr, id -> new AtomicInteger(0)).incrementAndGet();
    }

    /**
     * The 1-based count of times this exact UETR has been POSTed to this
     * rail, after incrementing for the current delivery -- 1 for an
     * original dispatch, 2 for its first redispatch, and so on. Computed
     * before behaviour resolution, per delivery, regardless of what that
     * behaviour turns out to be (accept, drop, or anything else) -- see
     * {@link com.kishore.payments.railsim.scenario.MatchCriteria#deliveryAttemptAtLeast}
     * for why this exists: a redispatch carries the same content as its
     * original, so this is the one dimension a scenario rule can use to
     * tell them apart that doesn't depend on how many other payments are
     * interleaving on the same rail.
     */
    public int nextDeliveryAttempt(String uetr) {
        return deliveryAttempts.computeIfAbsent(uetr, id -> new AtomicInteger(0)).incrementAndGet();
    }

    public void updateStatus(String uetr, String railStatus, String railReasonCode) {
        recorded.computeIfPresent(uetr, (id, existing) -> existing.withRailStatus(railStatus, railReasonCode));
    }

    public RecordedPayment find(String uetr) {
        return recorded.get(uetr);
    }

    public Collection<RecordedPayment> allRecorded() {
        return List.copyOf(recorded.values());
    }

    public void trackCallback(ScheduledFuture<?> future) {
        pendingCallbacks.add(future);
    }

    public void callbackCompleted(ScheduledFuture<?> future) {
        pendingCallbacks.remove(future);
    }
}
