package com.kishore.payments.gateway.callback;

import com.kishore.payments.core.domain.ActorType;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import com.kishore.payments.core.outbox.OutboxWriter;
import com.kishore.payments.core.state.ConcurrentTransitionException;
import com.kishore.payments.core.state.IllegalTransitionException;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.core.state.InstructionStateWriter;
import com.kishore.payments.core.state.TransitionResult;
import com.kishore.payments.gateway.GatewayMetrics;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Correlates an inbound pacs.002/pacs.004 by UETR and applies its outcome.
 * The pacs.004 return path (below) is a single transaction per callback --
 * see .notes/reports/PHASE-6-REPORT.md section 5 for why: the correlation
 * lookup and the resulting transition are fast (one indexed lookup, one
 * optimistic-locked update), and a synchronous response lets a failure here
 * surface to the caller immediately rather than being silently swallowed
 * behind an already-sent 202. The pacs.002 status path is not a single
 * transaction; see {@link #handleStatus}.
 */
@Service
public class CallbackCorrelationService {

    private static final Logger log = LoggerFactory.getLogger(CallbackCorrelationService.class);

    /**
     * Bounds the {@link AckPendingException} retry loop in {@link
     * #handleStatus}. A confirmation and the ACK-driven {@code
     * ROUTED->SENT} transition it presupposes both originate from the same
     * accepted dispatch, but run as two independently scheduled paths with
     * no ordering guarantee between them: {@code DispatchOrchestrator}
     * commits its transition only after the synchronous dispatch HTTP call
     * returns, while the rail schedules its confirmation independently of
     * that (see rail-simulator's {@code CallbackSender}), and can fire
     * before the caller has even finished processing the response. Each
     * retry through {@link CallbackStatusApplier} opens (and releases) its
     * own short transaction rather than holding one connection across the
     * whole wait -- polling from inside a single held transaction was tried
     * first and made things worse under a high-volume burst: hundreds of
     * threads sleeping while holding a pooled connection starved the very
     * commits they were waiting on. 500ms total, 25ms steps: comfortably
     * longer than a single local commit ever legitimately takes, short
     * enough not to mask a genuine stall as this same wait.
     */
    private static final int ACK_RACE_MAX_ATTEMPTS = 20;
    private static final long ACK_RACE_POLL_INTERVAL_MS = 25;

    private final PaymentInstructionRepository instructions;
    private final InstructionStateWriter stateWriter;
    private final OutboxWriter outboxWriter;
    private final GatewayMetrics metrics;
    private final Clock clock;
    private final CallbackStatusApplier statusApplier;

    public CallbackCorrelationService(
            PaymentInstructionRepository instructions,
            InstructionStateWriter stateWriter,
            OutboxWriter outboxWriter,
            GatewayMetrics metrics,
            Clock clock,
            CallbackStatusApplier statusApplier) {
        this.instructions = instructions;
        this.stateWriter = stateWriter;
        this.outboxWriter = outboxWriter;
        this.metrics = metrics;
        this.clock = clock;
        this.statusApplier = statusApplier;
    }

    /**
     * Deliberately not one transaction end-to-end: each retry through
     * {@link CallbackStatusApplier#applyStatus} opens its own short
     * transaction, and the sleep between retries happens here, outside all
     * of them, so a stalled instruction never holds a pooled connection
     * while waiting -- see {@link #ACK_RACE_MAX_ATTEMPTS}.
     *
     * <p>{@link ConcurrentTransitionException} and {@link
     * IllegalTransitionException} are caught here too, not left to whatever
     * called this method: this callback racing another writer that resolved
     * the same instruction first (redispatch cap reached, reconciliation
     * itself reaching a KNOWN outcome, ...) is an accepted, expected
     * outcome everywhere else in this system tolerates it (see {@code
     * DispatchOrchestrator#runDiscardingSupersededTransitions} and, until
     * this method absorbed it directly, {@code RailCallbackController}) --
     * losing this race is not this callback's problem to propagate.
     */
    public void handleStatus(String railId, InboundConfirmation confirmation) {
        Optional<PaymentInstructionEntity> maybeInstruction = findByUetr(confirmation.uetr());
        if (maybeInstruction.isEmpty()) {
            metrics.recordConfirmationUncorrelated(railId);
            log.info("No instruction for UETR {} on a pacs.002 status callback from {}; a rail can report a late status "
                    + "for something reconciled by other means, this is not an error", confirmation.uetr(), railId);
            return;
        }
        metrics.recordConfirmation(railId, confirmation.txStatus());
        UUID instructionId = maybeInstruction.get().getInstructionId();

        for (int attempt = 0; ; attempt++) {
            try {
                statusApplier.applyStatus(instructionId, confirmation, railId);
                return;
            } catch (AckPendingException e) {
                if (attempt >= ACK_RACE_MAX_ATTEMPTS) {
                    log.warn("Giving up on {} after {} retries: still not SENT", instructionId, attempt);
                    return;
                }
                sleep(ACK_RACE_POLL_INTERVAL_MS);
            } catch (ConcurrentTransitionException | IllegalTransitionException e) {
                log.info("{} correlating a pacs.002 status callback from {}; another writer already resolved the instruction, accepting anyway",
                        e.getClass().getSimpleName(), railId);
                return;
            }
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
        outboxWriter.write(CallbackOutboxMessages.toSettlementMessage(instruction, result, InstructionState.RETURNED, railId, inboundReturn.reasonCode(), clock));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Optional<PaymentInstructionEntity> findByUetr(String uetr) {
        return instructions.findByUetr(UUID.fromString(uetr));
    }
}
