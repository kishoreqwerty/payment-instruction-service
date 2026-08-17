package com.kishore.payments.gateway.callback;

import com.kishore.payments.core.domain.ActorType;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import com.kishore.payments.core.outbox.OutboxWriter;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.core.state.InstructionStateWriter;
import com.kishore.payments.core.state.TransitionResult;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kishore.payments.gateway.failure.FailureDetail;

/**
 * The transactional half of applying a pacs.002 status, split out of {@link
 * CallbackCorrelationService} so {@link #applyStatus} runs behind Spring's
 * real transactional proxy. Calling an {@code @Transactional} method via
 * {@code this.} from another method on the *same* bean bypasses that proxy
 * entirely (Spring AOP self-invocation) -- a separate bean is what makes
 * {@code CallbackCorrelationService}'s retry-with-a-fresh-transaction-per-attempt
 * (see its {@code handleStatus} javadoc) actually open and close a distinct
 * transaction on every attempt, rather than silently running the whole loop
 * with no transaction at all.
 */
@Component
class CallbackStatusApplier {

    private static final Logger log = LoggerFactory.getLogger(CallbackStatusApplier.class);

    private final PaymentInstructionRepository instructions;
    private final InstructionStateWriter stateWriter;
    private final OutboxWriter outboxWriter;
    private final Clock clock;

    CallbackStatusApplier(
            PaymentInstructionRepository instructions, InstructionStateWriter stateWriter, OutboxWriter outboxWriter, Clock clock) {
        this.instructions = instructions;
        this.stateWriter = stateWriter;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
    }

    @Transactional
    void applyStatus(UUID instructionId, InboundConfirmation confirmation, String railId) {
        PaymentInstructionEntity instruction = instructions.findById(instructionId).orElseThrow();
        String actorId = "rail:" + railId;

        InstructionState current = instruction.getState();
        if (current == InstructionState.SENT_UNCONFIRMED) {
            // Proof of receipt: the message got through, only the response was
            // lost. Resolve the ambiguity before applying the actual status.
            // Phase 7: this can race AmbiguityResolver's own resolution of
            // the same instruction (a redispatch cap reached, moving it to
            // INVESTIGATION, or reconciliation itself reaching a KNOWN
            // outcome first). Deliberately not caught here: this method is
            // itself @Transactional, and stateWriter.transition joins that
            // same transaction, so catching and continuing (e.g. to still
            // return normally) would try to commit a transaction Spring has
            // already marked rollback-only, failing with
            // UnexpectedRollbackException instead. Letting it propagate
            // rolls back cleanly; RailCallbackController is where this is
            // actually handled, outside any transaction.
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

    private void applySettlement(PaymentInstructionEntity instruction, InstructionState current, String railId, String actorId) {
        if (current == InstructionState.SETTLED) {
            log.debug("Duplicate ACSC for already-settled {}, suppressing", instruction.getInstructionId());
            return;
        }
        if (current == InstructionState.ROUTED) {
            throw new AckPendingException(instruction.getInstructionId());
        }
        if (current != InstructionState.SENT) {
            log.warn("ACSC for {} which is at {}, not SENT; ignoring rather than forcing an illegal transition",
                    instruction.getInstructionId(), current);
            return;
        }
        TransitionResult result =
                stateWriter.transition(instruction.getInstructionId(), InstructionState.SETTLED, ActorType.RAIL, actorId, null, null);
        outboxWriter.write(CallbackOutboxMessages.toSettlementMessage(instruction, result, InstructionState.SETTLED, railId, null, clock));
    }

    private void applyRejection(PaymentInstructionEntity instruction, InstructionState current, String railId, String actorId, String reasonCode) {
        if (current == InstructionState.EXCEPTION) {
            log.debug("Duplicate RJCT for already-exceptioned {}, suppressing", instruction.getInstructionId());
            return;
        }
        if (current == InstructionState.ROUTED) {
            throw new AckPendingException(instruction.getInstructionId());
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
        outboxWriter.write(CallbackOutboxMessages.toExceptionMessage(instruction, result, detail, clock));
    }
}
