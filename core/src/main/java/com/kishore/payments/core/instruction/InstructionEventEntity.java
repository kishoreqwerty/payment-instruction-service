package com.kishore.payments.core.instruction;

import com.kishore.payments.core.domain.ActorType;
import com.kishore.payments.core.state.InstructionState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Mirrors core.instruction_event. Append-only: never updated after insert. */
@Entity
@Table(name = "instruction_event", schema = "core")
public class InstructionEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "instruction_id", nullable = false)
    private UUID instructionId;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state")
    private InstructionState fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false)
    private InstructionState toState;

    @Column(name = "occurred_at", insertable = false, updatable = false)
    private OffsetDateTime occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    private ActorType actorType;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "reason_code")
    private String reasonCode;

    @Column(name = "reason_detail")
    private String reasonDetail;

    protected InstructionEventEntity() {
        // JPA
    }

    public InstructionEventEntity(
            UUID instructionId,
            int sequenceNo,
            InstructionState fromState,
            InstructionState toState,
            ActorType actorType,
            String actorId,
            String reasonCode,
            String reasonDetail) {
        this.instructionId = instructionId;
        this.sequenceNo = sequenceNo;
        this.fromState = fromState;
        this.toState = toState;
        this.actorType = actorType;
        this.actorId = actorId;
        this.reasonCode = reasonCode;
        this.reasonDetail = reasonDetail;
    }

    public Long getEventId() {
        return eventId;
    }

    public UUID getInstructionId() {
        return instructionId;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public InstructionState getFromState() {
        return fromState;
    }

    public InstructionState getToState() {
        return toState;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }

    public ActorType getActorType() {
        return actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getReasonDetail() {
        return reasonDetail;
    }
}
