package com.kishore.payments.exception.cases;

import com.kishore.payments.core.domain.ActorType;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.event.InstructionExceptionEvent;
import com.kishore.payments.core.event.InstructionRepairedEvent;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import com.kishore.payments.core.outbox.OutboxHeaders;
import com.kishore.payments.core.outbox.OutboxMessage;
import com.kishore.payments.core.outbox.OutboxWriter;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.core.state.InstructionStateWriter;
import com.kishore.payments.core.state.TransitionResult;
import com.kishore.payments.exception.classifier.ClassifierInvoker;
import com.kishore.payments.exception.config.ExceptionMetrics;
import com.kishore.payments.exception.config.ExceptionServiceProperties;
import com.kishore.payments.exception.repair.FieldChange;
import com.kishore.payments.exception.repair.FieldNotRepairableException;
import com.kishore.payments.exception.repair.MakerCheckerViolationException;
import com.kishore.payments.exception.repair.RepairActionEntity;
import com.kishore.payments.exception.repair.RepairActionNotFoundException;
import com.kishore.payments.exception.repair.RepairActionRepository;
import com.kishore.payments.exception.repair.RepairableField;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The whole exception-case lifecycle: opening/appending on a new failure,
 * the maker-checker repair cycle, static-data retry, and investigation
 * resolution. One class rather than one per workflow because every one of
 * them ends the same way -- an instruction transition, an outbox write, and
 * a case-state update in the same transaction -- and splitting that up would
 * mean re-deriving the same transactional shape four times.
 *
 * <p>{@code repair_attempts} is a lifetime counter across an instruction's
 * whole repair *lineage*, not a per-case counter reset to zero when a bad
 * repair opens a new case: see {@link ExceptionCaseOpener} inheriting it
 * from the immediately-preceding case. Phase 7's report flagged exactly this
 * kind of question for {@code reconciliation_state.redispatch_count} and
 * left it for this phase to decide explicitly -- this is that decision.
 * Without inheriting it, the three-attempt cap in the phase brief's own cap
 * test ("three repair attempts, then a fourth proposal is rejected") would
 * be trivially unenforceable: every bad repair opening a fresh case would
 * also reset the count that caps it.
 *
 * <p>A static-data retry deliberately does not close the case the way a
 * repair apply does (see {@link #retryStaticData}): a retry doesn't claim
 * anything about the instruction was fixed, only that reference data might
 * now be different, so re-failure should append to the *same* still-open
 * case (the phase brief's own static-data test: "retry with data still
 * missing -> stays open, attempts increments") rather than opening a new
 * one the way a bad field repair does.
 *
 * <p>Neither {@link #applyRepair} nor {@link #retryStaticData} transitions
 * the instruction past {@code REPAIRED}. {@code REPAIRED -> VALIDATED} is
 * ValidationConsumer's own re-validation (processing-service, Phase 4/8),
 * triggered by the {@code payments.repaired} publish each of those methods
 * makes -- the same transition that consumer already performs for a
 * first-time {@code RECEIVED -> VALIDATED} delivery, just reached from a
 * different predecessor state. Performing it here too would both duplicate
 * a transition another service owns and -- concretely, not just as a
 * layering concern -- leave that service's own re-validation attempting an
 * illegal {@code VALIDATED -> VALIDATED} self-transition when it ran,
 * silently discarded as a benign race by {@code InstructionStateWriter}'s
 * own reclassification and stalling the instruction at {@code VALIDATED}
 * forever. Found by {@code RepairedEventReentryIntegrationTest}
 * (processing-service) actually driving {@code payments.repaired} through
 * the real listener rather than asserting only up to this service's own
 * boundary.
 */
@Service
public class ExceptionCaseService {

    private static final String REPAIRED_TOPIC = "payments.repaired";

    private final ExceptionCaseRepository cases;
    private final RepairActionRepository repairActions;
    private final InvestigationConfirmationRepository confirmations;
    private final PaymentInstructionRepository instructions;
    private final InstructionStateWriter stateWriter;
    private final OutboxWriter outboxWriter;
    private final ExceptionMetrics metrics;
    private final ExceptionServiceProperties properties;
    private final Clock clock;
    private final ExceptionCaseOpener caseOpener;
    private final ClassifierInvoker classifierInvoker;

    public ExceptionCaseService(
            ExceptionCaseRepository cases,
            RepairActionRepository repairActions,
            InvestigationConfirmationRepository confirmations,
            PaymentInstructionRepository instructions,
            InstructionStateWriter stateWriter,
            OutboxWriter outboxWriter,
            ExceptionMetrics metrics,
            ExceptionServiceProperties properties,
            Clock clock,
            ExceptionCaseOpener caseOpener,
            ClassifierInvoker classifierInvoker) {
        this.cases = cases;
        this.repairActions = repairActions;
        this.confirmations = confirmations;
        this.instructions = instructions;
        this.stateWriter = stateWriter;
        this.outboxWriter = outboxWriter;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
        this.caseOpener = caseOpener;
        this.classifierInvoker = classifierInvoker;
    }

    // ------------------------------------------------------------------
    // Case opening: payments.exceptions -> open or append.
    //
    // Deliberately not @Transactional here, and delegated to a separate
    // bean ({@link ExceptionCaseOpener}): a Postgres transaction that hits
    // the partial-unique-index collision is aborted outright for every
    // statement after the violation, not just the one that caused it, so
    // retrying has to open a genuinely new transaction -- which requires
    // going back through Spring's transactional proxy from a different
    // bean, not a same-bean {@code this.} call. See that class's own
    // javadoc for the full account, including the load-bearing bug this
    // was found fixing (the first version of this method retried inside
    // the same transaction and failed with "current transaction is aborted,
    // commands ignored until end of transaction block" under real
    // concurrent load -- exactly the class of bug the phase brief's own
    // concurrency test exists to catch).
    // ------------------------------------------------------------------

    public void handleExceptionEvent(InstructionExceptionEvent event) {
        // Phase 11: the classifier only ever runs on a freshly-opened case (see
        // ExceptionCaseOpener.NewCase's own javadoc), and only after this method's own case-open
        // transaction has committed -- classifierInvoker.invokeAsync hands off to a dedicated
        // executor and returns immediately, so a slow or unavailable classifier can never delay
        // this listener's own message processing.
        caseOpener.handle(event).ifPresent(newCase -> classifierInvoker.invokeAsync(
                newCase.exceptionCase().getCaseId(), newCase.exceptionCase().getFailureStage(), newCase.instruction(), newCase.detail(),
                newCase.exceptionCase().getRepairAttempts()));
    }

    // ------------------------------------------------------------------
    // Field repair: propose (MAKER) -> approve (CHECKER) -> apply.
    // ------------------------------------------------------------------

    @Transactional
    public List<RepairActionEntity> proposeRepair(UUID caseId, List<FieldChange> changes, String proposedBy) {
        ExceptionCaseEntity exceptionCase = requireCase(caseId);
        if (exceptionCase.getCaseType() != CaseType.BUSINESS_FAILURE) {
            throw new IllegalCaseActionException(caseId, "not a business-failure case; investigation cases use confirm-sent/reject");
        }
        if (exceptionCase.getStatus() != CaseStatus.OPEN) {
            throw new IllegalCaseActionException(caseId, "not OPEN (" + exceptionCase.getStatus() + ")");
        }
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("At least one field change is required");
        }
        if (exceptionCase.getRepairAttempts() >= properties.maxRepairAttempts()) {
            metrics.recordRepairRejected("cap_reached");
            throw new IllegalCaseActionException(
                    caseId, "repair cap of " + properties.maxRepairAttempts() + " already reached; rejection is the only remaining action");
        }

        List<String> disallowed = changes.stream().map(FieldChange::fieldPath).filter(fp -> RepairableField.byFieldPath(fp).isEmpty()).toList();
        if (!disallowed.isEmpty()) {
            metrics.recordRepairRejected("field_not_repairable");
            throw new FieldNotRepairableException(disallowed);
        }

        PaymentInstructionEntity instruction = instructions
                .findById(exceptionCase.getInstructionId())
                .orElseThrow(() -> new NoSuchElementException("No payment instruction: " + exceptionCase.getInstructionId()));

        List<RepairActionEntity> actions = new ArrayList<>(changes.size());
        for (FieldChange change : changes) {
            RepairableField field = RepairableField.byFieldPath(change.fieldPath()).orElseThrow();
            String oldValue = field.read(instruction);
            actions.add(repairActions.save(new RepairActionEntity(UUID.randomUUID(), caseId, change.fieldPath(), oldValue, change.newValue(), proposedBy)));
        }

        exceptionCase.setStatus(CaseStatus.PENDING_APPROVAL);
        if (exceptionCase.getAssignedTo() == null) {
            exceptionCase.setAssignedTo(proposedBy);
        }
        cases.save(exceptionCase);
        metrics.recordRepairProposed("accepted");
        return actions;
    }

    @Transactional
    public RepairActionEntity approveRepair(UUID actionId, String approvedBy) {
        RepairActionEntity action = repairActions.findById(actionId).orElseThrow(() -> new RepairActionNotFoundException(actionId));
        if (action.isApproved()) {
            throw new IllegalCaseActionException(action.getCaseId(), "repair action " + actionId + " is already approved");
        }
        if (action.getProposedBy().equals(approvedBy)) {
            metrics.recordRepairRejected("maker_checker");
            throw new MakerCheckerViolationException(actionId, approvedBy);
        }

        action.approve(approvedBy, OffsetDateTime.now(clock));
        repairActions.save(action);

        ExceptionCaseEntity exceptionCase = requireCase(action.getCaseId());
        List<RepairActionEntity> allActions = repairActions.findByCaseIdOrderByProposedAtAsc(exceptionCase.getCaseId());
        if (allActions.stream().allMatch(RepairActionEntity::isApproved)) {
            applyRepair(exceptionCase, allActions, approvedBy);
        }
        return action;
    }

    /**
     * REPAIRED is not skippable: this service transitions only as far as
     * {@code EXCEPTION -> REPAIRED} (one instruction_event row, {@link
     * ActorType#OPERATOR}/{@code approvedBy}, since approving is what
     * causes it), so the audit trail shows a repair occurred as its own
     * distinct fact -- not merged into, or overwritten by, the {@code
     * REPAIRED -> VALIDATED} re-validation that follows once
     * ValidationConsumer picks up the payments.repaired publish below. See
     * this class's own javadoc.
     */
    private void applyRepair(ExceptionCaseEntity exceptionCase, List<RepairActionEntity> actions, String actor) {
        PaymentInstructionEntity instruction = instructions
                .findById(exceptionCase.getInstructionId())
                .orElseThrow(() -> new NoSuchElementException("No payment instruction: " + exceptionCase.getInstructionId()));

        for (RepairActionEntity action : actions) {
            RepairableField field = RepairableField.byFieldPath(action.getFieldPath())
                    .orElseThrow(() -> new IllegalStateException("Proposed field no longer on the allowlist: " + action.getFieldPath()));
            field.write(instruction, action.getNewValue());
        }
        instructions.save(instruction);

        // Only as far as REPAIRED, deliberately not VALIDATED too:
        // REPAIRED -> VALIDATED is ValidationConsumer's own re-validation,
        // triggered by the payments.repaired publish below, the same
        // transition it already performs for a first-time RECEIVED ->
        // VALIDATED delivery. Doing it here as well would be this service
        // reaching into a transition another service owns -- and, worse,
        // would leave ValidationConsumer's own re-validation attempting an
        // illegal VALIDATED -> VALIDATED self-transition when it ran.
        TransitionResult repaired = stateWriter.transition(instruction.getInstructionId(), InstructionState.REPAIRED, ActorType.OPERATOR,
                actor, null, "repair applied for case " + exceptionCase.getCaseId());
        outboxWriter.write(toRepairedMessage(instruction, exceptionCase, repaired));

        exceptionCase.setRepairAttempts(exceptionCase.getRepairAttempts() + 1);
        closeCase(exceptionCase, CaseStatus.RESOLVED, Resolution.REPAIRED);
        metrics.recordRepairApplied();
    }

    // ------------------------------------------------------------------
    // Static-data retry: MAKER, no checker approval -- nothing about the
    // payment itself changed.
    // ------------------------------------------------------------------

    @Transactional
    public ExceptionCaseEntity retryStaticData(UUID caseId, String actor) {
        ExceptionCaseEntity exceptionCase = requireCase(caseId);
        if (exceptionCase.getCaseType() != CaseType.BUSINESS_FAILURE) {
            throw new IllegalCaseActionException(caseId, "not a business-failure case");
        }
        if (exceptionCase.getRepairability() != Repairability.STATIC_DATA) {
            throw new IllegalCaseActionException(caseId, "not a STATIC_DATA case (" + exceptionCase.getRepairability() + ")");
        }
        if (exceptionCase.getStatus().isTerminal()) {
            throw new IllegalCaseActionException(caseId, "already " + exceptionCase.getStatus());
        }
        if (exceptionCase.getRepairAttempts() >= properties.maxRepairAttempts()) {
            metrics.recordRepairRejected("cap_reached");
            throw new IllegalCaseActionException(
                    caseId, "repair cap of " + properties.maxRepairAttempts() + " already reached; rejection is the only remaining action");
        }

        PaymentInstructionEntity instruction = instructions
                .findById(exceptionCase.getInstructionId())
                .orElseThrow(() -> new NoSuchElementException("No payment instruction: " + exceptionCase.getInstructionId()));

        // Only as far as REPAIRED -- see applyRepair's own comment on why
        // REPAIRED -> VALIDATED is ValidationConsumer's transition to make,
        // not this service's.
        TransitionResult repaired = stateWriter.transition(instruction.getInstructionId(), InstructionState.REPAIRED, ActorType.OPERATOR,
                actor, null, "static-data retry for case " + caseId);
        outboxWriter.write(toRepairedMessage(instruction, exceptionCase, repaired));

        exceptionCase.setRepairAttempts(exceptionCase.getRepairAttempts() + 1);
        if (exceptionCase.getAssignedTo() == null) {
            exceptionCase.setAssignedTo(actor);
        }
        // Deliberately not closeCase: see class javadoc.
        cases.save(exceptionCase);
        metrics.recordRepairApplied();
        return exceptionCase;
    }

    // ------------------------------------------------------------------
    // Investigation resolution. confirm-sent is maker-checker, the same
    // shape as a field repair: it asserts a payment reached the rail on
    // out-of-band evidence alone, and unlike a field repair, nothing
    // downstream re-validates that claim -- a wrong field repair is caught
    // by re-validation within the same cycle; a wrong confirm-sent puts the
    // instruction at SENT, the same state a genuinely-dispatched payment
    // reaches, with nothing left in the system to ever question it again.
    // A wrongly-rejected payment is recoverable (re-origination by the
    // sender); a wrongly-confirmed one is not -- so reject stays
    // CHECKER-only, the conservative direction a bad call degrades
    // gracefully from, while confirm-sent gets the stronger control. See
    // .notes/reports/PHASE-8-REPORT.md section 5.
    // ------------------------------------------------------------------

    @Transactional
    public InvestigationConfirmationEntity proposeConfirmSent(UUID caseId, String justification, String proposedBy) {
        ExceptionCaseEntity exceptionCase = requireInvestigationCase(caseId);
        if (exceptionCase.getStatus() != CaseStatus.OPEN) {
            throw new IllegalCaseActionException(caseId, "not OPEN (" + exceptionCase.getStatus() + ")");
        }

        InvestigationConfirmationEntity confirmation =
                confirmations.save(new InvestigationConfirmationEntity(UUID.randomUUID(), caseId, justification, proposedBy));

        exceptionCase.setStatus(CaseStatus.PENDING_APPROVAL);
        if (exceptionCase.getAssignedTo() == null) {
            exceptionCase.setAssignedTo(proposedBy);
        }
        cases.save(exceptionCase);
        return confirmation;
    }

    @Transactional
    public InvestigationConfirmationEntity approveConfirmSent(UUID confirmationId, String approvedBy) {
        InvestigationConfirmationEntity confirmation =
                confirmations.findById(confirmationId).orElseThrow(() -> new InvestigationConfirmationNotFoundException(confirmationId));
        if (confirmation.isApproved()) {
            throw new IllegalCaseActionException(confirmation.getCaseId(), "confirmation " + confirmationId + " is already approved");
        }
        if (confirmation.getProposedBy().equals(approvedBy)) {
            metrics.recordRepairRejected("maker_checker");
            throw new MakerCheckerViolationException(confirmationId, approvedBy);
        }

        confirmation.approve(approvedBy, OffsetDateTime.now(clock));
        confirmations.save(confirmation);

        ExceptionCaseEntity exceptionCase = requireCase(confirmation.getCaseId());
        PaymentInstructionEntity instruction = instructions
                .findById(exceptionCase.getInstructionId())
                .orElseThrow(() -> new NoSuchElementException("No payment instruction: " + exceptionCase.getInstructionId()));

        stateWriter.transition(instruction.getInstructionId(), InstructionState.SENT, ActorType.OPERATOR, approvedBy, null,
                confirmation.getJustification());

        exceptionCase.setJustification(confirmation.getJustification());
        closeCase(exceptionCase, CaseStatus.RESOLVED, Resolution.CONFIRMED_SENT);
        metrics.recordInvestigationResolution("confirmed_sent");
        return confirmation;
    }

    @Transactional
    public ExceptionCaseEntity rejectInvestigation(UUID caseId, String justification, String actor) {
        ExceptionCaseEntity exceptionCase = requireInvestigationCase(caseId);
        PaymentInstructionEntity instruction = instructions
                .findById(exceptionCase.getInstructionId())
                .orElseThrow(() -> new NoSuchElementException("No payment instruction: " + exceptionCase.getInstructionId()));

        stateWriter.transition(instruction.getInstructionId(), InstructionState.EXCEPTION, ActorType.OPERATOR, actor, null, justification);
        stateWriter.transition(instruction.getInstructionId(), InstructionState.REJECTED, ActorType.OPERATOR, actor, null,
                "investigation rejected: case " + caseId);

        exceptionCase.setJustification(justification);
        closeCase(exceptionCase, CaseStatus.REJECTED, Resolution.REJECTED);
        metrics.recordInvestigationResolution("rejected");
        return exceptionCase;
    }

    private ExceptionCaseEntity requireInvestigationCase(UUID caseId) {
        ExceptionCaseEntity exceptionCase = requireCase(caseId);
        if (exceptionCase.getCaseType() != CaseType.INVESTIGATION) {
            throw new IllegalCaseActionException(caseId, "not an investigation case");
        }
        if (exceptionCase.getStatus().isTerminal()) {
            throw new IllegalCaseActionException(caseId, "already " + exceptionCase.getStatus());
        }
        return exceptionCase;
    }

    // ------------------------------------------------------------------
    // Rejection of a business-failure case. Not named as its own endpoint
    // in the phase brief, but implied by it: the cap test's own wording
    // ("only rejection remains available") requires some mechanism to
    // actually reject a case once the repair cap is reached, and the case
    // state machine the brief itself describes (OPEN -> REJECTED) has no
    // other route to REJECTED for a BUSINESS_FAILURE case. Added as
    // POST /v1/cases/{caseId}/reject, CHECKER-gated like the investigation
    // equivalent: giving up on a payment is not a lighter decision than
    // confirming one reached the rail.
    // ------------------------------------------------------------------

    @Transactional
    public ExceptionCaseEntity rejectCase(UUID caseId, String actor) {
        ExceptionCaseEntity exceptionCase = requireCase(caseId);
        if (exceptionCase.getCaseType() != CaseType.BUSINESS_FAILURE) {
            throw new IllegalCaseActionException(caseId, "not a business-failure case; use /investigation/reject");
        }
        if (exceptionCase.getStatus().isTerminal()) {
            throw new IllegalCaseActionException(caseId, "already " + exceptionCase.getStatus());
        }

        PaymentInstructionEntity instruction = instructions
                .findById(exceptionCase.getInstructionId())
                .orElseThrow(() -> new NoSuchElementException("No payment instruction: " + exceptionCase.getInstructionId()));
        stateWriter.transition(instruction.getInstructionId(), InstructionState.REJECTED, ActorType.OPERATOR, actor, null,
                "case " + caseId + " rejected");

        closeCase(exceptionCase, CaseStatus.REJECTED, Resolution.REJECTED);
        return exceptionCase;
    }

    // ------------------------------------------------------------------
    // Classifier feedback (Phase 11): a direct signal from the operator's own UI action --
    // "Accept" or "Edit/Ignore" on the proposal panel -- not inferred after the fact by comparing
    // the eventual repair to the suggestion. See .notes/reports/PHASE-11-REPORT.md section 5 for
    // why a direct UI signal is more honest than a heuristic, and what limits it as an accuracy
    // proxy regardless. Deliberately not maker-checker gated like a repair: recording what the
    // operator thought of a suggestion changes nothing about the payment or the case's own
    // workflow, so the lighter, single-actor bar that already gates viewing a case is enough.
    // ------------------------------------------------------------------

    @Transactional
    public ExceptionCaseEntity recordClassifierFeedback(UUID caseId, boolean accepted) {
        ExceptionCaseEntity exceptionCase = requireCase(caseId);
        exceptionCase.setClassifierAccepted(accepted);
        cases.save(exceptionCase);
        return exceptionCase;
    }

    // ------------------------------------------------------------------

    private ExceptionCaseEntity requireCase(UUID caseId) {
        return cases.findById(caseId).orElseThrow(() -> new CaseNotFoundException(caseId));
    }

    private void closeCase(ExceptionCaseEntity exceptionCase, CaseStatus status, Resolution resolution) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        exceptionCase.setStatus(status);
        exceptionCase.setResolution(resolution);
        exceptionCase.setClosedAt(now);
        cases.save(exceptionCase);
        if (exceptionCase.getOpenedAt() != null) {
            metrics.recordCaseAge(Duration.between(exceptionCase.getOpenedAt(), now));
        }
    }

    private OutboxMessage toRepairedMessage(PaymentInstructionEntity instruction, ExceptionCaseEntity exceptionCase, TransitionResult result) {
        OffsetDateTime occurredAt = OffsetDateTime.now(clock);
        InstructionRepairedEvent event = new InstructionRepairedEvent(
                instruction.getInstructionId(),
                instruction.getUetr(),
                instruction.getEndToEndId(),
                exceptionCase.getCaseId(),
                result.sequenceNo(),
                occurredAt,
                InstructionRepairedEvent.CURRENT_VERSION);
        return new OutboxMessage(
                instruction.getInstructionId(),
                REPAIRED_TOPIC,
                instruction.getInstructionId().toString(),
                OutboxHeaders.of("InstructionRepaired", InstructionRepairedEvent.CURRENT_VERSION, occurredAt),
                event);
    }

}
