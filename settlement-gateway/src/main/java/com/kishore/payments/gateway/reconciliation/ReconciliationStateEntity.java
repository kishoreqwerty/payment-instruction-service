package com.kishore.payments.gateway.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Mirrors core.reconciliation_state. One row per instruction that has ever
 * become ambiguous, kept for the instruction's lifetime rather than deleted
 * when one ambiguity episode resolves -- {@link #redispatchCount} is a
 * running total across every episode, checked against the redispatch cap
 * before each new redispatch. The two "consecutive" counters instead reset
 * whenever an observation breaks their streak: see {@link #recordUnknown},
 * {@link #recordInconclusive} and {@link #recordKnown} for exactly what
 * resets what, which is the two-consecutive-UNKNOWN rule's actual
 * implementation (.notes/reports/PHASE-7-REPORT.md).
 */
@Entity
@Table(name = "reconciliation_state", schema = "core")
public class ReconciliationStateEntity implements Persistable<UUID> {

    @Id
    @Column(name = "instruction_id")
    private UUID instructionId;

    @Column(name = "consecutive_unknown_count", nullable = false)
    private int consecutiveUnknownCount;

    @Column(name = "consecutive_inconclusive_count", nullable = false)
    private int consecutiveInconclusiveCount;

    @Column(name = "redispatch_count", nullable = false)
    private int redispatchCount;

    @Column(name = "last_outcome")
    private String lastOutcome;

    @Column(name = "last_checked_at")
    private OffsetDateTime lastCheckedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Transient
    private boolean isNew = true;

    protected ReconciliationStateEntity() {
        // JPA
    }

    public ReconciliationStateEntity(UUID instructionId, OffsetDateTime now) {
        this.instructionId = instructionId;
        this.consecutiveUnknownCount = 0;
        this.consecutiveInconclusiveCount = 0;
        this.redispatchCount = 0;
        this.updatedAt = now;
    }

    @Override
    public UUID getId() {
        return instructionId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    public UUID getInstructionId() {
        return instructionId;
    }

    public int getConsecutiveUnknownCount() {
        return consecutiveUnknownCount;
    }

    public int getConsecutiveInconclusiveCount() {
        return consecutiveInconclusiveCount;
    }

    public int getRedispatchCount() {
        return redispatchCount;
    }

    public String getLastOutcome() {
        return lastOutcome;
    }

    public OffsetDateTime getLastCheckedAt() {
        return lastCheckedAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** A rail answered KNOWN: the ambiguity this episode was tracking is resolved, so both consecutive streaks reset. */
    public void recordKnown(OffsetDateTime now) {
        this.consecutiveUnknownCount = 0;
        this.consecutiveInconclusiveCount = 0;
        this.lastOutcome = "KNOWN";
        this.lastCheckedAt = now;
        this.updatedAt = now;
    }

    /** A rail answered UNKNOWN: extends the UNKNOWN streak, and breaks the inconclusive one -- an UNKNOWN is a successful query. */
    public void recordUnknown(OffsetDateTime now) {
        this.consecutiveUnknownCount++;
        this.consecutiveInconclusiveCount = 0;
        this.lastOutcome = "UNKNOWN";
        this.lastCheckedAt = now;
        this.updatedAt = now;
    }

    /** The query itself failed or timed out: extends the inconclusive streak, and breaks the UNKNOWN one -- non-consecutive UNKNOWN does not count. */
    public void recordInconclusive(OffsetDateTime now) {
        this.consecutiveInconclusiveCount++;
        this.consecutiveUnknownCount = 0;
        this.lastOutcome = "INCONCLUSIVE";
        this.lastCheckedAt = now;
        this.updatedAt = now;
    }

    /** A redispatch was just issued: bumps the lifetime total and starts a fresh UNKNOWN streak for the new episode. */
    public void recordRedispatch(OffsetDateTime now) {
        this.redispatchCount++;
        this.consecutiveUnknownCount = 0;
        this.updatedAt = now;
    }
}
