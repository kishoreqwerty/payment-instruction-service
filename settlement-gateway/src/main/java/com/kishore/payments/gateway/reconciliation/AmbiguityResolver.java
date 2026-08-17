package com.kishore.payments.gateway.reconciliation;

import com.kishore.payments.core.domain.ActorType;
import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.event.InstructionRoutedEvent;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import com.kishore.payments.core.outbox.OutboxHeaders;
import com.kishore.payments.core.outbox.OutboxMessage;
import com.kishore.payments.core.outbox.OutboxWriter;
import com.kishore.payments.core.state.ConcurrentTransitionException;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.core.state.InstructionStateWriter;
import com.kishore.payments.core.state.TransitionResult;
import com.kishore.payments.gateway.GatewayMetrics;
import com.kishore.payments.gateway.GatewayProperties;
import com.kishore.payments.gateway.dispatch.DispatchRecordRepository;
import com.kishore.payments.gateway.dispatch.DispatchState;
import com.kishore.payments.gateway.event.InstructionExceptionEvent;
import com.kishore.payments.gateway.event.InstructionSettlementEvent;
import com.kishore.payments.gateway.failure.FailureDetail;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves a {@code SENT_UNCONFIRMED} instruction somewhere else, or determines
 * that a {@code ROUTED} instruction's dispatch attempt never happened at
 * all. The central rule everything here follows: no path may redispatch
 * without positive evidence the rail does not have the payment
 * (.notes/ARCHITECTURE.md §6.4) -- one {@code UNKNOWN} is not that evidence,
 * which is why {@link #applyUnknown} requires two consecutive ones.
 *
 * <p><b>Cross-replica claiming.</b> Multiple gateway replicas run this on
 * the same schedule. Candidate instruction ids are listed with a plain,
 * unlocked read (cheap, and a stale list just means a wasted lock attempt
 * later, not a correctness problem), then each candidate is processed in
 * its own transaction that opens by calling {@code
 * pg_try_advisory_xact_lock(namespace, hashtext(instruction_id))} -- a
 * two-key advisory lock, deliberately not {@code FOR UPDATE SKIP LOCKED} on
 * the row and deliberately not the single-key form {@link
 * com.kishore.payments.core.outbox.OutboxPublisher} uses (Postgres keeps
 * the two forms in separate lock spaces, so this can never collide with
 * outbox claiming for the same instruction). A failed {@code
 * pg_try_advisory_xact_lock} means another replica already holds this
 * instruction; this one skips it and moves on rather than blocking. The
 * lock, and only the lock, is held for the rest of that one instruction's
 * transaction -- including the HTTP status query -- which is what makes
 * holding a transaction open across a network call safe here in a way it
 * is not in {@link com.kishore.payments.gateway.dispatch.DispatchOrchestrator}:
 * an advisory lock does not block an ordinary {@code UPDATE} from a
 * concurrent {@link com.kishore.payments.gateway.callback.CallbackCorrelationService}
 * transaction on the same {@code payment_instruction} row (only another
 * advisory-lock attempt on the same key), so a pacs.002 landing mid-
 * reconciliation is not blocked by this lock, only raced against it --
 * whichever transaction's optimistic-locked update commits first wins, and
 * the loser gets a state it no longer recognises and discards its own
 * update as a duplicate.
 */
@Component
public class AmbiguityResolver {

    private static final Logger log = LoggerFactory.getLogger(AmbiguityResolver.class);

    private static final String ACTOR_ID = "settlement-gateway:reconciliation";
    private static final String SETTLED_TOPIC = "payments.settled";
    private static final String EXCEPTIONS_TOPIC = "payments.exceptions";
    private static final String ROUTED_TOPIC = "payments.routed";

    /**
     * Arbitrary namespace for this resolver's two-key advisory locks
     * (Postgres: {@code pg_advisory_lock(int, int)}). Any constant works;
     * what matters is that it is fixed and not reused for an unrelated
     * purpose, so this resolver's lock space never collides with anything
     * else in this service.
     */
    private static final int LOCK_NAMESPACE = 0x50374152; // "P7AR" -- Phase 7 AmbiguityResolver

    private static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_xact_lock(?, hashtext(?))";

    // Ordered by reconciliation_state.last_checked_at (never-checked first),
    // not by payment_instruction.updated_at: an observation that doesn't
    // trigger a state transition -- the common case, since UNKNOWN and
    // inconclusive outcomes usually just increment a counter -- never
    // touches updated_at. Ordering on that column alone would let the same
    // subset win every sweep forever whenever there are more eligible
    // instructions than batchSize, starving the rest. last_checked_at
    // updates on every observation regardless of outcome, so each sweep
    // naturally rotates to whichever candidates have gone longest unchecked.
    private static final String AMBIGUOUS_CANDIDATES_SQL = "SELECT pi.instruction_id FROM core.payment_instruction pi "
            + "LEFT JOIN core.reconciliation_state rs ON rs.instruction_id = pi.instruction_id "
            + "WHERE pi.state = 'SENT_UNCONFIRMED' AND pi.updated_at < ? "
            + "ORDER BY COALESCE(rs.last_checked_at, TIMESTAMP '-infinity') LIMIT ?";

    private static final String STUCK_PENDING_CANDIDATES_SQL = "SELECT DISTINCT pi.instruction_id FROM core.payment_instruction pi "
            + "JOIN core.dispatch_record dr ON dr.instruction_id = pi.instruction_id "
            + "WHERE pi.state = 'ROUTED' AND dr.dispatch_state = 'PENDING' AND dr.sent_at < ? "
            + "ORDER BY pi.instruction_id LIMIT ?";

    private final JdbcTemplate jdbc;
    private final PaymentInstructionRepository instructions;
    private final ReconciliationStateRepository reconciliationStates;
    private final DispatchRecordRepository dispatchRecords;
    private final RailStatusClient railStatusClient;
    private final InstructionStateWriter stateWriter;
    private final OutboxWriter outboxWriter;
    private final GatewayMetrics metrics;
    private final GatewayProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public AmbiguityResolver(
            JdbcTemplate jdbc,
            PaymentInstructionRepository instructions,
            ReconciliationStateRepository reconciliationStates,
            DispatchRecordRepository dispatchRecords,
            RailStatusClient railStatusClient,
            InstructionStateWriter stateWriter,
            OutboxWriter outboxWriter,
            GatewayMetrics metrics,
            GatewayProperties properties,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.instructions = instructions;
        this.reconciliationStates = reconciliationStates;
        this.dispatchRecords = dispatchRecords;
        this.railStatusClient = railStatusClient;
        this.stateWriter = stateWriter;
        this.outboxWriter = outboxWriter;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Public and callable outside the schedule -- like {@code
     * OutboxPublisher.publishBatch()}, tests invoke this directly for
     * deterministic timing rather than waiting on the real interval.
     */
    @Scheduled(fixedDelayString = "${payments.gateway.reconciliation.interval:PT2M}")
    public void reconcile() {
        reconcileAmbiguousDispatches();
        reconcileStuckPendingDispatches();
    }

    private void reconcileAmbiguousDispatches() {
        GatewayProperties.Reconciliation config = properties.reconciliation();
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(config.gracePeriod());
        List<UUID> candidates = jdbc.query(
                AMBIGUOUS_CANDIDATES_SQL, (rs, rowNum) -> (UUID) rs.getObject("instruction_id"), cutoff, config.batchSize());
        for (UUID instructionId : candidates) {
            reconcileOneAmbiguous(instructionId, cutoff);
        }
    }

    private void reconcileOneAmbiguous(UUID instructionId, OffsetDateTime cutoff) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (!tryLock(instructionId)) {
                    return;
                }
                PaymentInstructionEntity instruction = instructions.findById(instructionId).orElse(null);
                if (instruction == null || instruction.getState() != InstructionState.SENT_UNCONFIRMED) {
                    // Already resolved -- by a callback, by another cycle
                    // before this one acquired the lock, or (in principle) by
                    // an operator. Nothing to do.
                    return;
                }
                if (instruction.getUpdatedAt() != null && instruction.getUpdatedAt().isAfter(cutoff)) {
                    // Raced the grace period: became SENT_UNCONFIRMED after
                    // this cycle's candidate list was computed. Leave it for
                    // the next cycle rather than reconciling early.
                    return;
                }

                ReconciliationStateEntity recon = reconciliationStates
                        .findById(instructionId)
                        .orElseGet(() -> new ReconciliationStateEntity(instructionId, OffsetDateTime.now(clock)));

                String rail = instruction.getSelectedRail();
                Instant startedAt = clock.instant();
                RailStatusOutcome outcome = railStatusClient.query(rail, instruction.getUetr());
                metrics.recordReconciliationDuration(rail, Duration.between(startedAt, clock.instant()));

                if (outcome instanceof RailStatusOutcome.Known known) {
                    metrics.recordReconciliationAttempt(rail, "known");
                    applyKnown(instruction, recon, known);
                } else if (outcome instanceof RailStatusOutcome.Unknown) {
                    metrics.recordReconciliationAttempt(rail, "unknown");
                    applyUnknown(instruction, recon);
                } else if (outcome instanceof RailStatusOutcome.QueryFailed failed) {
                    metrics.recordReconciliationAttempt(rail, "inconclusive");
                    applyInconclusive(instruction, recon, failed);
                }

                reconciliationStates.save(recon);
            });
        } catch (ConcurrentTransitionException e) {
            // stateWriter.transition is itself @Transactional; once it
            // throws, the transaction it joined (this method's own) is
            // marked rollback-only by Spring's transaction interceptor
            // regardless of whether the exception is caught afterward, so
            // catching it inside the lambda and trying to still commit
            // (e.g. to save recon) would fail with UnexpectedRollbackException.
            // Letting the whole transaction roll back here instead is
            // correct and cheap: the advisory lock only ever serialises this
            // resolver against itself, never against a concurrent
            // CallbackCorrelationService transaction on the same row (see
            // this class's javadoc) -- whichever of the two committed first
            // wins, and the loser's reconciliation counters for this one
            // cycle are simply not persisted, which is safe because the
            // instruction is no longer SENT_UNCONFIRMED and will not be a
            // candidate again.
            log.info("Concurrent transition detected for {} during reconciliation, discarding as duplicate", instructionId);
        }
    }

    /** Package-private, not private: exercised directly by unit tests against the branch table, with every collaborator mocked -- see AmbiguityResolverBranchTableTest. */
    void applyKnown(PaymentInstructionEntity instruction, ReconciliationStateEntity recon, RailStatusOutcome.Known known) {
        recon.recordKnown(OffsetDateTime.now(clock));
        String rail = instruction.getSelectedRail();
        String railStatus = known.railStatus();

        // Proof of receipt resolves the ambiguity outright, regardless of
        // what happens next: the message got through, only the response was
        // lost. No outbox event for this transition alone -- same precedent
        // as CallbackCorrelationService.handleStatus's own SENT_UNCONFIRMED
        // -> SENT step, since SENT_UNCONFIRMED and SENT are both "not yet
        // done" from a downstream consumer's perspective.
        stateWriter.transition(instruction.getInstructionId(), InstructionState.SENT, ActorType.SYSTEM, ACTOR_ID, null, null);

        if (railStatus == null || "ACSP".equals(railStatus)) {
            metrics.recordAmbiguityResolution(rail, "sent");
            return;
        }

        PaymentInstructionEntity refreshed = instructions.findById(instruction.getInstructionId()).orElseThrow();
        if ("ACSC".equals(railStatus)) {
            TransitionResult result = stateWriter.transition(
                    refreshed.getInstructionId(), InstructionState.SETTLED, ActorType.SYSTEM, ACTOR_ID, null, null);
            outboxWriter.write(toSettlementMessage(refreshed, result, rail, null));
            metrics.recordAmbiguityResolution(rail, "settled");
        } else if ("RJCT".equals(railStatus)) {
            FailureDetail detail = new FailureDetail(
                    known.reasonCode(), Repairability.REPAIRABLE, null,
                    "Rail " + rail + " rejected the payment with reason " + known.reasonCode() + ", discovered via reconciliation");
            TransitionResult result = stateWriter.transition(
                    refreshed.getInstructionId(), InstructionState.EXCEPTION, ActorType.SYSTEM, ACTOR_ID, known.reasonCode(), detail.detail());
            outboxWriter.write(toExceptionMessage(refreshed, result, detail));
            metrics.recordAmbiguityResolution(rail, "rejected");
        } else {
            log.warn("Unrecognised railStatus '{}' from {} for {} via reconciliation; leaving at SENT",
                    railStatus, rail, refreshed.getInstructionId());
            metrics.recordAmbiguityResolution(rail, "sent");
        }
    }

    void applyUnknown(PaymentInstructionEntity instruction, ReconciliationStateEntity recon) {
        recon.recordUnknown(OffsetDateTime.now(clock));
        String rail = instruction.getSelectedRail();
        GatewayProperties.Reconciliation config = properties.reconciliation();

        if (recon.getConsecutiveUnknownCount() < config.consecutiveUnknownThreshold()) {
            // First (or, with a configured threshold above two, an
            // intermediate) UNKNOWN observation: not yet the positive
            // evidence redispatch requires. Wait for the next cycle.
            return;
        }

        if (recon.getRedispatchCount() >= config.maxRedispatchAttempts()) {
            enterInvestigation(instruction, "redispatch cap of " + config.maxRedispatchAttempts()
                    + " reached after " + recon.getConsecutiveUnknownCount() + " consecutive UNKNOWN reconciliation observations");
            metrics.recordAmbiguityResolution(rail, "investigation");
            return;
        }

        redispatch(instruction, recon);
        metrics.recordAmbiguityResolution(rail, "redispatched");
    }

    void applyInconclusive(PaymentInstructionEntity instruction, ReconciliationStateEntity recon, RailStatusOutcome.QueryFailed failed) {
        recon.recordInconclusive(OffsetDateTime.now(clock));
        int window = properties.reconciliation().inconclusiveWindow();
        if (recon.getConsecutiveInconclusiveCount() >= window) {
            enterInvestigation(instruction, "inconclusive reconciliation window of " + window
                    + " cycles exhausted; last query failure: " + failed.detail());
            metrics.recordAmbiguityResolution(instruction.getSelectedRail(), "investigation");
        }
    }

    /**
     * The redispatch carries the same UETR as the original -- re-entering
     * {@code ROUTED} and publishing the same {@link InstructionRoutedEvent}
     * shape a first-time routing decision would, so {@code RoutedConsumer}
     * -> {@code DispatchOrchestrator} handles it with no special-casing.
     * {@code attempt_no} is whatever {@code dispatch_record} says comes
     * next for this UETR, not a number this class invents, so it lines up
     * exactly with the row {@code DispatchOrchestrator} is about to write.
     */
    private void redispatch(PaymentInstructionEntity instruction, ReconciliationStateEntity recon) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        recon.recordRedispatch(now);
        TransitionResult result =
                stateWriter.transition(instruction.getInstructionId(), InstructionState.ROUTED, ActorType.SYSTEM, ACTOR_ID, null, null);
        outboxWriter.write(toRoutedMessage(instruction, result));

        int nextAttemptNo = dispatchRecords.maxAttemptNo(instruction.getUetr()) + 1;
        metrics.recordRedispatch(instruction.getSelectedRail(), nextAttemptNo);
    }

    private void enterInvestigation(PaymentInstructionEntity instruction, String reason) {
        TransitionResult result = stateWriter.transition(
                instruction.getInstructionId(), InstructionState.INVESTIGATION, ActorType.SYSTEM, ACTOR_ID, null, reason);
        FailureDetail detail = new FailureDetail(null, Repairability.REPAIRABLE, null, reason);
        outboxWriter.write(toExceptionMessage(instruction, result, detail));
    }

    private void reconcileStuckPendingDispatches() {
        GatewayProperties.Reconciliation config = properties.reconciliation();
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(config.pendingThreshold());
        List<UUID> candidates = jdbc.query(
                STUCK_PENDING_CANDIDATES_SQL, (rs, rowNum) -> (UUID) rs.getObject("instruction_id"), cutoff, config.batchSize());
        for (UUID instructionId : candidates) {
            reconcileOneStuckPending(instructionId, cutoff);
        }
    }

    /**
     * A {@code PENDING} dispatch_record older than the threshold means the
     * process died between committing that row and making the network call
     * -- the Phase 6 kill scenario. Unlike the SENT_UNCONFIRMED path, there
     * is no rail to query: the rail was never called, so a status query
     * would truthfully answer UNKNOWN forever and this would never resolve
     * on its own. Straight to INVESTIGATION -- a human has to decide
     * whether this attempt actually reached the rail before it can be
     * either confirmed or redispatched.
     */
    private void reconcileOneStuckPending(UUID instructionId, OffsetDateTime cutoff) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (!tryLock(instructionId)) {
                    return;
                }
                PaymentInstructionEntity instruction = instructions.findById(instructionId).orElse(null);
                if (instruction == null || instruction.getState() != InstructionState.ROUTED) {
                    return;
                }
                boolean stillStuck = dispatchRecords.findByUetrOrderByAttemptNo(instruction.getUetr()).stream()
                        .anyMatch(record -> record.getDispatchState() == DispatchState.PENDING
                                && record.getSentAt() != null && record.getSentAt().isBefore(cutoff));
                if (!stillStuck) {
                    return;
                }
                enterInvestigation(instruction, "a dispatch_record has been PENDING past the configured threshold -- "
                        + "the process most likely died between committing that attempt and calling the rail (Phase 6 kill scenario)");
                metrics.recordAmbiguityResolution(instruction.getSelectedRail(), "pending");
            });
        } catch (ConcurrentTransitionException e) {
            // Same reasoning as reconcileOneAmbiguous's catch: the whole
            // transaction has to roll back, not just this exception.
            log.info("Concurrent transition detected for {} during stuck-PENDING reconciliation, discarding as duplicate", instructionId);
        }
    }

    private boolean tryLock(UUID instructionId) {
        Boolean locked = jdbc.queryForObject(TRY_LOCK_SQL, Boolean.class, LOCK_NAMESPACE, instructionId.toString());
        return Boolean.TRUE.equals(locked);
    }

    private OutboxMessage toSettlementMessage(
            PaymentInstructionEntity instruction, TransitionResult result, String railId, String railReasonCode) {
        OffsetDateTime occurredAt = OffsetDateTime.now(clock);
        InstructionSettlementEvent event = new InstructionSettlementEvent(
                instruction.getInstructionId(),
                instruction.getUetr(),
                instruction.getEndToEndId(),
                InstructionState.SETTLED,
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
                FailureStage.RECONCILIATION,
                List.of(new InstructionExceptionEvent.Detail(detail.reasonCode(), detail.repairability(), detail.field(), detail.detail())),
                InstructionExceptionEvent.CURRENT_VERSION);
        return new OutboxMessage(
                instruction.getInstructionId(),
                EXCEPTIONS_TOPIC,
                instruction.getInstructionId().toString(),
                OutboxHeaders.of("InstructionException", InstructionExceptionEvent.CURRENT_VERSION, occurredAt, null),
                event);
    }

    private OutboxMessage toRoutedMessage(PaymentInstructionEntity instruction, TransitionResult result) {
        OffsetDateTime occurredAt = OffsetDateTime.now(clock);
        InstructionRoutedEvent event = new InstructionRoutedEvent(
                instruction.getInstructionId(),
                instruction.getUetr(),
                instruction.getEndToEndId(),
                result.sequenceNo(),
                occurredAt,
                instruction.getAmount(),
                instruction.getCurrency(),
                instruction.getSelectedRail(),
                instruction.getCorrespondentBic(),
                instruction.getNostroAccount(),
                instruction.getSettlementDate(),
                InstructionRoutedEvent.CURRENT_VERSION);
        return new OutboxMessage(
                instruction.getInstructionId(),
                ROUTED_TOPIC,
                instruction.getInstructionId().toString(),
                OutboxHeaders.of("InstructionRouted", InstructionRoutedEvent.CURRENT_VERSION, occurredAt, null),
                event);
    }
}
