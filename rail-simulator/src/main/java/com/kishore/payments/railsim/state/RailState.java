package com.kishore.payments.railsim.state;

import com.kishore.payments.railsim.scenario.ScenarioConfig;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledFuture;
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
    }

    /** The 1-based ordinal of the request that just arrived, for everyNth matching. */
    public long nextRequestOrdinal() {
        return requestOrdinal.incrementAndGet();
    }

    public void record(RecordedPayment payment) {
        recorded.put(payment.payment().uetr(), payment);
    }

    public void updateStatus(String uetr, String railStatus) {
        recorded.computeIfPresent(uetr, (id, existing) -> existing.withRailStatus(railStatus));
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
