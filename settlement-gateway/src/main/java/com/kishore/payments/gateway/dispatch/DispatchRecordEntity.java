package com.kishore.payments.gateway.dispatch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Mirrors core.dispatch_record. One row per dispatch attempt: written as
 * {@link DispatchState#PENDING} and committed before the network call it
 * describes, then updated in place once the call resolves. Implements
 * {@link Persistable} for the same reason {@code PaymentInstructionEntity}
 * does -- dispatch_id is assigned by application code, not the database.
 */
@Entity
@Table(name = "dispatch_record", schema = "core")
public class DispatchRecordEntity implements Persistable<UUID> {

    @Id
    @Column(name = "dispatch_id")
    private UUID dispatchId;

    @Column(name = "instruction_id", nullable = false)
    private UUID instructionId;

    @Column(name = "uetr", nullable = false)
    private UUID uetr;

    @Column(name = "rail", nullable = false)
    private String rail;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "dispatch_state", nullable = false)
    private DispatchState dispatchState;

    @Column(name = "request_payload", nullable = false)
    private byte[] requestPayload;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Transient
    private boolean isNew = true;

    protected DispatchRecordEntity() {
        // JPA
    }

    public DispatchRecordEntity(
            UUID dispatchId, UUID instructionId, UUID uetr, String rail, int attemptNo, byte[] requestPayload, OffsetDateTime sentAt) {
        this.dispatchId = dispatchId;
        this.instructionId = instructionId;
        this.uetr = uetr;
        this.rail = rail;
        this.attemptNo = attemptNo;
        this.dispatchState = DispatchState.PENDING;
        this.requestPayload = requestPayload;
        this.sentAt = sentAt;
    }

    @Override
    public UUID getId() {
        return dispatchId;
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

    public UUID getDispatchId() {
        return dispatchId;
    }

    public UUID getInstructionId() {
        return instructionId;
    }

    public UUID getUetr() {
        return uetr;
    }

    public String getRail() {
        return rail;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public DispatchState getDispatchState() {
        return dispatchState;
    }

    public byte[] getRequestPayload() {
        return requestPayload;
    }

    public OffsetDateTime getSentAt() {
        return sentAt;
    }

    public OffsetDateTime getRespondedAt() {
        return respondedAt;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public void markResolved(DispatchState dispatchState, OffsetDateTime respondedAt, Integer responseStatus) {
        this.dispatchState = dispatchState;
        this.respondedAt = respondedAt;
        this.responseStatus = responseStatus;
    }
}
