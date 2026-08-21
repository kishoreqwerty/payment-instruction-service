package com.kishore.payments.gateway.dispatch;

import com.kishore.payments.core.domain.ActorType;
import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import com.kishore.payments.core.outbox.OutboxHeaders;
import com.kishore.payments.core.outbox.OutboxMessage;
import com.kishore.payments.core.outbox.OutboxWriter;
import com.kishore.payments.core.state.ConcurrentTransitionException;
import com.kishore.payments.core.state.IllegalTransitionException;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.core.state.InstructionStateWriter;
import com.kishore.payments.core.state.TransitionResult;
import com.kishore.payments.gateway.GatewayMetrics;
import com.kishore.payments.gateway.GatewayProperties;
import com.kishore.payments.gateway.event.InstructionDispatchEvent;
import com.kishore.payments.core.event.InstructionExceptionEvent;
import com.kishore.payments.gateway.failure.BusinessFailureException;
import com.kishore.payments.gateway.failure.FailureDetail;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Assembles, dispatches, and (when the rail returns a 5xx) retries a single
 * ROUTED instruction, one Kafka message's worth of work per call. Every
 * attempt is its own pair of transactions around one uncommitted network
 * call: T1 commits the {@code PENDING} dispatch_record, then the HTTP POST
 * happens with no open transaction, then T2 resolves the outcome. That
 * ordering -- write and commit, then call, never the other way and never
 * merged into one transaction -- is the phase's central guarantee: if the
 * process dies between T1 and the call, the evidence that a dispatch was
 * attempted already survived it, and nothing here retries automatically on
 * restart (see .notes/reports/PHASE-6-REPORT.md section 4's durability
 * test).
 */
@Component
public class DispatchOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DispatchOrchestrator.class);
    private static final String ACTOR_ID = "settlement-gateway:dispatch";
    private static final String SENT_TOPIC = "payments.sent";
    private static final String EXCEPTIONS_TOPIC = "payments.exceptions";

    private final PaymentInstructionRepository instructions;
    private final DispatchRecordRepository dispatchRecords;
    private final Pacs008Assembler assembler;
    private final RailDispatcher railDispatcher;
    private final InstructionStateWriter stateWriter;
    private final OutboxWriter outboxWriter;
    private final GatewayMetrics metrics;
    private final GatewayProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public DispatchOrchestrator(
            PaymentInstructionRepository instructions,
            DispatchRecordRepository dispatchRecords,
            Pacs008Assembler assembler,
            RailDispatcher railDispatcher,
            InstructionStateWriter stateWriter,
            OutboxWriter outboxWriter,
            GatewayMetrics metrics,
            GatewayProperties properties,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.instructions = instructions;
        this.dispatchRecords = dispatchRecords;
        this.assembler = assembler;
        this.railDispatcher = railDispatcher;
        this.stateWriter = stateWriter;
        this.outboxWriter = outboxWriter;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void dispatch(UUID instructionId) {
        PaymentInstructionEntity instruction = instructions
                .findById(instructionId)
                .orElseThrow(() -> new NoSuchElementException("No payment instruction: " + instructionId));
        String rail = instruction.getSelectedRail();

        byte[] pacs008Xml;
        try {
            pacs008Xml = assembler.assemble(instruction);
        } catch (BusinessFailureException e) {
            runDiscardingSupersededTransitions(() -> handleBusinessFailure(instruction, e), instruction.getInstructionId());
            return;
        }

        int maxAttempts = properties.dispatchRetry().maxAttempts();
        Duration backoff = properties.dispatchRetry().initialBackoff();

        // attempt_no continues from whatever this UETR's dispatch_record
        // history already holds, rather than always starting at 1: this
        // dispatch() call might be a Phase 7 redispatch (the same UETR
        // re-entering ROUTED after SENT_UNCONFIRMED), which has to produce
        // attempt_no 2, 3, ... to satisfy dispatch_record's UNIQUE (uetr,
        // attempt_no) constraint rather than colliding with the original
        // attempt's row. For a first-time dispatch this is a no-op --
        // maxAttemptNo is 0, so numbering still starts at 1.
        int startingAttempt = dispatchRecords.maxAttemptNo(instruction.getUetr()) + 1;

        for (int offset = 0; offset < maxAttempts; offset++) {
            int attempt = startingAttempt + offset;
            byte[] payload = pacs008Xml;
            int attemptNo = attempt;
            DispatchRecordEntity record = transactionTemplate.execute(status -> writePendingRecord(instruction, rail, payload, attemptNo));

            Instant startedAt = clock.instant();
            DispatchOutcome outcome = railDispatcher.dispatch(rail, payload);
            metrics.recordDispatchDuration(rail, Duration.between(startedAt, clock.instant()));

            if (outcome instanceof DispatchOutcome.Acknowledged acknowledged) {
                metrics.recordDispatchOutcome(rail, "acknowledged");
                runDiscardingSupersededTransitions(() -> handleAcknowledged(instruction, record, acknowledged), instruction.getInstructionId());
                return;
            } else if (outcome instanceof DispatchOutcome.Rejected rejected) {
                metrics.recordDispatchOutcome(rail, "rejected");
                runDiscardingSupersededTransitions(() -> handleRejected(instruction, record, rejected), instruction.getInstructionId());
                return;
            } else if (outcome instanceof DispatchOutcome.Ambiguous ambiguous) {
                metrics.recordDispatchOutcome(rail, "timeout");
                metrics.recordDispatchAmbiguous(rail);
                runDiscardingSupersededTransitions(() -> handleAmbiguous(instruction, record, ambiguous), instruction.getInstructionId());
                return;
            } else if (outcome instanceof DispatchOutcome.ServerError serverError) {
                metrics.recordDispatchOutcome(rail, "failed");
                transactionTemplate.executeWithoutResult(status -> markFailed(record, serverError.httpStatus()));

                if (offset == maxAttempts - 1) {
                    int attemptsMade = offset + 1;
                    runDiscardingSupersededTransitions(
                            () -> handleServerErrorExhausted(instruction, serverError, attemptsMade), instruction.getInstructionId());
                    return;
                }
                log.info("Rail {} returned {} for {}, attempt {}, retrying in {}", rail, serverError.httpStatus(),
                        instruction.getInstructionId(), attempt, backoff);
                sleep(backoff);
                backoff = Duration.ofMillis((long) (backoff.toMillis() * properties.dispatchRetry().backoffMultiplier()));
            }
        }
    }

    /**
     * Runs one of the terminal per-outcome handlers in its own transaction,
     * tolerating a transition that has become stale or outright illegal by
     * the time this attempt resolves. Phase 7 means dispatch() is no longer
     * the only writer that can move an instruction off {@code ROUTED} or
     * {@code SENT_UNCONFIRMED}: {@code AmbiguityResolver} can independently
     * redispatch, resolve via reconciliation, or move a stuck {@code
     * PENDING} attempt to {@code INVESTIGATION} while this exact attempt is
     * still in flight. Catching here, outside the transaction, is required
     * -- catching inside the handler (where {@code stateWriter.transition}
     * itself runs) would try to commit a transaction Spring has already
     * marked rollback-only once that call throws, failing with {@code
     * UnexpectedRollbackException} instead of the clean discard this is.
     */
    private void runDiscardingSupersededTransitions(Runnable handler, UUID instructionId) {
        try {
            transactionTemplate.executeWithoutResult(status -> handler.run());
        } catch (ConcurrentTransitionException e) {
            log.info("Concurrent transition detected for {}, discarding as duplicate", instructionId);
        } catch (IllegalTransitionException e) {
            log.info("Illegal transition ({} -> {}) for {}, another writer already moved it elsewhere; discarding as superseded",
                    e.from(), e.to(), instructionId);
        }
    }

    private DispatchRecordEntity writePendingRecord(PaymentInstructionEntity instruction, String rail, byte[] payload, int attemptNo) {
        DispatchRecordEntity record = new DispatchRecordEntity(
                UUID.randomUUID(), instruction.getInstructionId(), instruction.getUetr(), rail, attemptNo, payload, OffsetDateTime.now(clock));
        return dispatchRecords.save(record);
    }

    private void markFailed(DispatchRecordEntity record, int httpStatus) {
        record.markResolved(DispatchState.FAILED, OffsetDateTime.now(clock), httpStatus);
        dispatchRecords.save(record);
    }

    private void handleAcknowledged(PaymentInstructionEntity instruction, DispatchRecordEntity record, DispatchOutcome.Acknowledged outcome) {
        record.markResolved(DispatchState.ACKNOWLEDGED, OffsetDateTime.now(clock), outcome.httpStatus());
        dispatchRecords.save(record);
        transitionAndPublishSent(instruction, record, InstructionState.SENT);
    }

    private void handleAmbiguous(PaymentInstructionEntity instruction, DispatchRecordEntity record, DispatchOutcome.Ambiguous outcome) {
        record.markResolved(DispatchState.TIMED_OUT, null, null);
        dispatchRecords.save(record);
        transitionAndPublishSent(instruction, record, InstructionState.SENT_UNCONFIRMED);
    }

    private void transitionAndPublishSent(PaymentInstructionEntity instruction, DispatchRecordEntity record, InstructionState to) {
        // Deliberately no try/catch here: stateWriter.transition is itself
        // @Transactional and joins whichever transaction the caller opened
        // (one of the transactionTemplate.executeWithoutResult(...) calls in
        // dispatch()), so once it throws, that transaction is already marked
        // rollback-only by Spring's interceptor -- catching here and
        // returning normally would just fail again with
        // UnexpectedRollbackException when the caller's transaction tries to
        // commit. Letting it propagate rolls back cleanly; dispatch()'s call
        // sites are where this is actually handled, outside the transaction.
        TransitionResult result =
                stateWriter.transition(instruction.getInstructionId(), to, ActorType.SYSTEM, ACTOR_ID, null, null);
        outboxWriter.write(toDispatchMessage(instruction, record, to, result));
    }

    private void handleRejected(PaymentInstructionEntity instruction, DispatchRecordEntity record, DispatchOutcome.Rejected outcome) {
        record.markResolved(DispatchState.FAILED, OffsetDateTime.now(clock), outcome.httpStatus());
        dispatchRecords.save(record);
        FailureDetail detail = new FailureDetail(
                null, Repairability.REPAIRABLE, null,
                "Rail " + record.getRail() + " rejected the dispatch outright with HTTP " + outcome.httpStatus() + ": " + outcome.detail());
        toException(instruction, detail);
    }

    private void handleServerErrorExhausted(PaymentInstructionEntity instruction, DispatchOutcome.ServerError outcome, int attempts) {
        FailureDetail detail = new FailureDetail(
                null, Repairability.TRANSIENT, null,
                "Rail returned HTTP " + outcome.httpStatus() + " on every attempt (" + attempts + "), last detail: " + outcome.detail());
        toException(instruction, detail);
    }

    private void handleBusinessFailure(PaymentInstructionEntity instruction, BusinessFailureException e) {
        for (FailureDetail detail : e.details()) {
            toException(instruction, detail);
            return;
        }
    }

    private void toException(PaymentInstructionEntity instruction, FailureDetail detail) {
        // Same reasoning as transitionAndPublishSent: no try/catch here,
        // for the same rollback-only reason.
        TransitionResult result = stateWriter.transition(
                instruction.getInstructionId(), InstructionState.EXCEPTION, ActorType.SYSTEM, ACTOR_ID, detail.reasonCode(), detail.detail());
        outboxWriter.write(toExceptionMessage(instruction, result, detail));
    }

    private OutboxMessage toDispatchMessage(PaymentInstructionEntity instruction, DispatchRecordEntity record, InstructionState to, TransitionResult result) {
        OffsetDateTime occurredAt = OffsetDateTime.now(clock);
        InstructionDispatchEvent event = new InstructionDispatchEvent(
                instruction.getInstructionId(),
                instruction.getUetr(),
                instruction.getEndToEndId(),
                to,
                result.sequenceNo(),
                occurredAt,
                record.getRail(),
                record.getDispatchId(),
                record.getAttemptNo(),
                InstructionDispatchEvent.CURRENT_VERSION);
        return new OutboxMessage(
                instruction.getInstructionId(),
                SENT_TOPIC,
                instruction.getInstructionId().toString(),
                OutboxHeaders.of("InstructionDispatch", InstructionDispatchEvent.CURRENT_VERSION, occurredAt),
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
                FailureStage.DISPATCH,
                List.of(new InstructionExceptionEvent.Detail(detail.reasonCode(), detail.repairability(), detail.field(), detail.detail())),
                InstructionExceptionEvent.CURRENT_VERSION);
        return new OutboxMessage(
                instruction.getInstructionId(),
                EXCEPTIONS_TOPIC,
                instruction.getInstructionId().toString(),
                OutboxHeaders.of("InstructionException", InstructionExceptionEvent.CURRENT_VERSION, occurredAt),
                event);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off before a dispatch retry", e);
        }
    }
}
