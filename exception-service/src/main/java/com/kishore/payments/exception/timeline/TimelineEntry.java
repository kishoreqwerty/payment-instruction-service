package com.kishore.payments.exception.timeline;

import com.kishore.payments.core.instruction.InstructionEventEntity;
import com.kishore.payments.exception.cases.InvestigationConfirmationEntity;
import com.kishore.payments.exception.repair.RepairActionEntity;
import java.time.OffsetDateTime;

/**
 * One moment in an instruction's history: a state transition ({@code
 * core.instruction_event}), a repair action's proposal or approval, or an
 * investigation confirm-sent proposal or approval ({@code
 * exceptions.repair_action} / {@code exceptions.investigation_confirmation}),
 * normalised to one shape so {@link TimelineService} can sort and return
 * them interleaved, in the order they actually happened -- what the phase
 * brief calls "the endpoint an operator lives in."
 *
 * <p>The confirmation variants are a Phase 9 addition: Phase 8 built the
 * maker-checker confirm-sent flow but never wired it into this timeline, so
 * an INVESTIGATION case's timeline showed every state transition except the
 * one action that actually resolved it -- see PHASE-9-REPORT.md §5. A
 * confirmation's justification is carried in {@code reasonDetail}, the same
 * slot a transition's own reason detail uses, since both are "the human
 * explanation for what happened here" and a confirmation has no
 * field/old/new value to report.
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

    public static TimelineEntry ofConfirmationProposed(InvestigationConfirmationEntity confirmation) {
        return new TimelineEntry(
                "CONFIRMATION_PROPOSED", confirmation.getProposedAt(), confirmation.getProposedBy(), null, null, null,
                confirmation.getJustification(), null, null, null);
    }

    public static TimelineEntry ofConfirmationApproved(InvestigationConfirmationEntity confirmation) {
        return new TimelineEntry(
                "CONFIRMATION_APPROVED", confirmation.getApprovedAt(), confirmation.getApprovedBy(), null, null, null,
                confirmation.getJustification(), null, null, null);
    }
}
