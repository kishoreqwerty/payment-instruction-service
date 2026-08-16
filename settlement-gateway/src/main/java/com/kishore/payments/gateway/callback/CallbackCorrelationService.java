package com.kishore.payments.gateway.callback;

import com.kishore.payments.core.domain.ActorType;
import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import com.kishore.payments.core.outbox.OutboxHeaders;
import com.kishore.payments.core.outbox.OutboxMessage;
import com.kishore.payments.core.outbox.OutboxWriter;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.core.state.InstructionStateWriter;
import com.kishore.payments.core.state.TransitionResult;
import com.kishore.payments.gateway.GatewayMetrics;
import com.kishore.payments.gateway.event.InstructionExceptionEvent;
import com.kishore.payments.gateway.event.InstructionSettlementEvent;
import com.kishore.payments.gateway.failure.FailureDetail;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Correlates an inbound pacs.002/pacs.004 by UETR and applies its outcome.
 * Processed synchronously, inside one transaction per callback -- see
 * .notes/reports/PHASE-6-REPORT.md section 5 for why: the correlation
 * lookup and the resulting transition are fast (one indexed lookup, one
 * optimistic-locked update), and a synchronous response lets a failure here
 * surface to the caller immediately rather than being silently swallowed
 * behind an already-sent 202.
 */
@Service
public class CallbackCorrelationService {

    private static final Logger log = LoggerFactory.getLogger(CallbackCorrelationService.class);
    private static final String SETTLED_TOPIC = "payments.settled";
    private static final String EXCEPTIONS_TOPIC = "payments.exceptions";

    private final PaymentInstructionRepository instructions;
    private final InstructionStateWriter stateWriter;
    private final OutboxWriter outboxWriter;
    private final GatewayMetrics metrics;
    private final Clock clock;

    public CallbackCorrelationService(
            PaymentInstructionRepository instructions,
            InstructionStateWriter stateWriter,
            OutboxWriter outboxWriter,
            GatewayMetrics metrics,
            Clock clock) {
        this.instructions = instructions;
        this.stateWriter = stateWriter;
        this.outboxWriter = outboxWriter;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public void handleStatus(String railId, InboundConfirmation confirmation) {
        Optional<PaymentInstructionEntity> maybeInstruction = findByUetr(confirmation.uetr());
        if (maybeInstruction.isEmpty()) {
            metrics.recordConfirmationUncorrelated(railId);
            log.info("No instruction for UETR {} on a pacs.002 status callback from {}; a rail can report a late status "
                    + "for something reconciled by other means, this is not an error", confirmation.uetr(), railId);
            return;
        }

        PaymentInstructionEntity instruction = maybeInstruction.get();
        metrics.recordConfirmation(railId, confirmation.txStatus());
        String actorId = "rail:" + railId;

        InstructionState current = instruction.getState();
        if (current == InstructionState.SENT_UNCONFIRMED) {
            // Proof of receipt: the message got through, only the response was
            // lost. Resolve the ambiguity before applying the actual status.
            stateWriter.transition(instruction.getInstructionId(), InstructionState.SENT, ActorType.RAIL, actorId, null, null);
            current = InstructionState.SENT;
            instruction = instructions.findById(instruction.getInstructionId()).orElseThrow();
        }

        switch (confirmation.txStatus()) {
            case "ACSC" -> applySettlement(instruction, current, railId, actorId);
            case "ACSP" -> log.debug("ACSP recorded for {} from {}; rail has accepted pending settlement, no state change",
                    instruction.getInstructionId(), railId);
            case "RJCT" -> applyRejection(instruction, current, railId, actorId, confirmation.reasonCode());
            default -> log.warn("Unrecognised pacs.002 TxSts '{}' from {} for {}", confirmation.txStatus(), railId, instruction.getInstructionId());
        }
    }

    @Transactional
    public void handleReturn(String railId, InboundReturn inboundReturn) {
        Optional<PaymentInstructionEntity> maybeInstruction = findByUetr(inboundReturn.uetr());
        if (maybeInstruction.isEmpty()) {
            metrics.recordConfirmationUncorrelated(railId);
            log.info("No instruction for UETR {} on a pacs.004 return callback from {}", inboundReturn.uetr(), railId);
            return;
        }

        PaymentInstructionEntity instruction = maybeInstruction.get();
        if (instruction.getState() == InstructionState.RETURNED) {
            log.debug("Duplicate pacs.004 return for already-returned {}, suppressing", instruction.getInstructionId());
            return;
        }
        if (instruction.getState() != InstructionState.SETTLED) {
            log.warn("pacs.004 return for {} which is at {}, not SETTLED; ignoring rather than forcing an illegal transition",
                    instruction.getInstructionId(), instruction.getState());
            return;
        }

        String actorId = "rail:" + railId;
        TransitionResult result = stateWriter.transition(
                instruction.getInstructionId(), InstructionState.RETURNED, ActorType.RAIL, actorId, inboundReturn.reasonCode(), null);
        outboxWriter.write(toSettlementMessage(instruction, result, InstructionState.RETURNED, railId, inboundReturn.reasonCode()));
    }

    private void applySettlement(PaymentInstructionEntity instruction, InstructionState current, String railId, String actorId) {
        if (current == InstructionState.SETTLED) {
            log.debug("Duplicate ACSC for already-settled {}, suppressing", instruction.getInstructionId());
            return;
        }
        if (current != InstructionState.SENT) {
            log.warn("ACSC for {} which is at {}, not SENT; ignoring rather than forcing an illegal transition",
                    instruction.getInstructionId(), current);
            return;
        }
        TransitionResult result =
                stateWriter.transition(instruction.getInstructionId(), InstructionState.SETTLED, ActorType.RAIL, actorId, null, null);
        outboxWriter.write(toSettlementMessage(instruction, result, InstructionState.SETTLED, railId, null));
    }

    private void applyRejection(PaymentInstructionEntity instruction, InstructionState current, String railId, String actorId, String reasonCode) {
        if (current == InstructionState.EXCEPTION) {
            log.debug("Duplicate RJCT for already-exceptioned {}, suppressing", instruction.getInstructionId());
            return;
        }
        if (current != InstructionState.SENT) {
            log.warn("RJCT for {} which is at {}, not SENT; ignoring rather than forcing an illegal transition",
                    instruction.getInstructionId(), current);
            return;
        }
        FailureDetail detail = new FailureDetail(
                reasonCode, Repairability.REPAIRABLE, null, "Rail " + railId + " rejected the payment with reason " + reasonCode);
        TransitionResult result = stateWriter.transition(
                instruction.getInstructionId(), InstructionState.EXCEPTION, ActorType.RAIL, actorId, reasonCode, detail.detail());
        outboxWriter.write(toExceptionMessage(instruction, result, detail));
    }

    private Optional<PaymentInstructionEntity> findByUetr(String uetr) {
        return instructions.findByUetr(UUID.fromString(uetr));
    }

    private OutboxMessage toSettlementMessage(
            PaymentInstructionEntity instruction, TransitionResult result, InstructionState state, String railId, String railReasonCode) {
        OffsetDateTime occurredAt = OffsetDateTime.now(clock);
        InstructionSettlementEvent event = new InstructionSettlementEvent(
                instruction.getInstructionId(),
                instruction.getUetr(),
                instruction.getEndToEndId(),
                state,
                result.sequenceNo(),
                occurredAt,
                railId,
                railReasonCode,
                InstructionSettlementEvent.CURRENT_VERSION);
        return new OutboxMessage(
                instruction.getInstructionId(),
                SETTLED_TOPIC,
                instruction.getInstructionId().toString(),
                OutboxHeaders.of("InstructionSettlement", InstructionSettlementEvent.CURRENT_VERSION, occurredAt, null),
                event);
    }

    private OutboxMessage toExceptionMessage(PaymentInstructionEntity instruction, TransitionResult result, FailureDetail detail) {
        OffsetDateTime occurredAt = OffsetDateTime.now(clock);
        InstructionExceptionEvent event = new InstructionExceptionEvent(
                instruction.getInstructionId(),
                instruction.getUetr(),
                instruction.getEndToEndId(),
                result.sequenceNo(),
                occurredAt,
                FailureStage.CONFIRMATION,
                List.of(new InstructionExceptionEvent.Detail(detail.reasonCode(), detail.repairability(), detail.field(), detail.detail())),
                InstructionExceptionEvent.CURRENT_VERSION);
        return new OutboxMessage(
                instruction.getInstructionId(),
                EXCEPTIONS_TOPIC,
                instruction.getInstructionId().toString(),
                OutboxHeaders.of("InstructionException", InstructionExceptionEvent.CURRENT_VERSION, occurredAt, null),
                event);
    }
}
