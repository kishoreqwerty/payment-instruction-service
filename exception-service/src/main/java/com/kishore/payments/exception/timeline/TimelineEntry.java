package com.kishore.payments.exception.timeline;

import com.kishore.payments.core.instruction.InstructionEventEntity;
import com.kishore.payments.exception.repair.RepairActionEntity;
import java.time.OffsetDateTime;

/**
 * One moment in an instruction's history: either a state transition
 * ({@code core.instruction_event}) or a repair action's proposal or
 * approval ({@code exceptions.repair_action}), normalised to one shape so
 * {@link TimelineService} can sort and return them interleaved, in the
 * order they actually happened -- what the phase brief calls "the endpoint
 * an operator lives in."
 */
public record TimelineEntry(
        String type,
        OffsetDateTime occurredAt,
        String actor,
        String fromState,
        String toState,
        String reasonCode,
        String reasonDetail,
        String fieldPath,
        String oldValue,
        String newValue) {

    public static TimelineEntry ofTransition(InstructionEventEntity event) {
        return new TimelineEntry(
                "STATE_TRANSITION",
                event.getOccurredAt(),
                event.getActorType() + ":" + event.getActorId(),
                event.getFromState() == null ? null : event.getFromState().name(),
                event.getToState().name(),
                event.getReasonCode(),
                event.getReasonDetail(),
                null,
                null,
                null);
    }

    public static TimelineEntry ofRepairProposed(RepairActionEntity action) {
        return new TimelineEntry(
                "REPAIR_PROPOSED", action.getProposedAt(), action.getProposedBy(), null, null, null, null,
                action.getFieldPath(), action.getOldValue(), action.getNewValue());
    }

    public static TimelineEntry ofRepairApproved(RepairActionEntity action) {
        return new TimelineEntry(
                "REPAIR_APPROVED", action.getApprovedAt(), action.getApprovedBy(), null, null, null, null,
                action.getFieldPath(), action.getOldValue(), action.getNewValue());
    }
}
