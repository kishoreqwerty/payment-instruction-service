package com.kishore.payments.exception.cases;

import com.kishore.payments.core.event.InstructionExceptionEvent;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.exception.config.ExceptionMetrics;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of case-opening, split out of {@link
 * ExceptionCaseService} for the same reason {@code CallbackStatusApplier}
 * was split out of {@code CallbackCorrelationService} in Phase 7: a Postgres
 * transaction that hits a constraint violation is aborted outright --
 * "current transaction is aborted, commands ignored until end of
 * transaction block" -- for every statement after the violation, not just
 * the one that caused it. Catching {@link DataIntegrityViolationException}
 * and then trying to re-read inside the *same* transaction fails with
 * exactly that error; retrying only works if the retry opens a genuinely
 * new transaction, which requires going back through Spring's transactional
 * proxy from a different bean (a same-bean {@code this.} call would skip
 * the proxy entirely and silently run without a transaction at all).
 *
 * <p>{@link #openOrAppend} is safe to call again wholesale on a caught
 * collision, not just a narrower "now go append" step: it re-checks for an
 * open case from scratch, and by the time a retry's fresh transaction
 * starts, the winner of the original race has already committed and is
 * visible, so the retry deterministically takes the append branch --
 * however many callers raced the first attempt, one retry each is enough,
 * since retries do not contend with each other the way the original
 * concurrent inserts did.
 */
@Component
class ExceptionCaseOpener {

    private static final List<CaseStatus> TERMINAL_STATUSES = List.of(CaseStatus.RESOLVED, CaseStatus.REJECTED);

    private final ExceptionCaseRepository cases;
    private final PaymentInstructionRepository instructions;
    private final ExceptionMetrics metrics;

    ExceptionCaseOpener(ExceptionCaseRepository cases, PaymentInstructionRepository instructions, ExceptionMetrics metrics) {
        this.cases = cases;
        this.instructions = instructions;
        this.metrics = metrics;
    }

    void handle(InstructionExceptionEvent event) {
        try {
            openOrAppend(event);
        } catch (DataIntegrityViolationException e) {
            if (!isOpenCaseCollision(e)) {
                throw e;
            }
            openOrAppend(event);
        }
    }

    @Transactional
    void openOrAppend(InstructionExceptionEvent event) {
        PaymentInstructionEntity instruction = instructions
                .findById(event.instructionId())
                .orElseThrow(() -> new NoSuchElementException("No payment instruction: " + event.instructionId()));
        InstructionExceptionEvent.Detail detail = primaryDetail(event);
        CaseType caseType = instruction.getState() == InstructionState.INVESTIGATION ? CaseType.INVESTIGATION : CaseType.BUSINESS_FAILURE;

        Optional<ExceptionCaseEntity> existingOpen = cases.findByInstructionIdAndStatusNotIn(event.instructionId(), TERMINAL_STATUSES);
        if (existingOpen.isPresent()) {
            appendToCase(existingOpen.get(), event, detail);
        } else {
            openNewCase(event.instructionId(), caseType, event, detail);
        }
    }

    private void openNewCase(UUID instructionId, CaseType caseType, InstructionExceptionEvent event, InstructionExceptionEvent.Detail detail) {
        int startingAttempts = cases.findByInstructionIdOrderByOpenedAtDesc(instructionId).stream()
                .findFirst()
                .map(ExceptionCaseEntity::getRepairAttempts)
                .orElse(0);
        ExceptionCaseEntity newCase = new ExceptionCaseEntity(
                UUID.randomUUID(), instructionId, caseType, event.failureStage(), detail.reasonCode(), detail.detail(), detail.repairability(),
                startingAttempts);
        // Flushed now, inside this method's own transaction, so a
        // constraint violation surfaces here and this transaction rolls
        // back cleanly, rather than at some later flush point outside this
        // method's control.
        cases.saveAndFlush(newCase);
        metrics.recordCaseOpened(event.failureStage(), detail.reasonCode(), detail.repairability());
    }

    private void appendToCase(ExceptionCaseEntity existing, InstructionExceptionEvent event, InstructionExceptionEvent.Detail detail) {
        existing.setFailureStage(event.failureStage());
        existing.setReasonCode(detail.reasonCode());
        existing.setReasonDetail(detail.detail());
        existing.setRepairability(detail.repairability());
        cases.save(existing);
        // No metrics.recordCaseOpened here: same case, not a new one.
    }

    private static InstructionExceptionEvent.Detail primaryDetail(InstructionExceptionEvent event) {
        if (event.details().isEmpty()) {
            return new InstructionExceptionEvent.Detail(null, com.kishore.payments.core.domain.Repairability.UNREPAIRABLE, null,
                    "no failure detail provided");
        }
        return event.details().get(0);
    }

    private static boolean isOpenCaseCollision(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        return cause.getMessage() != null && cause.getMessage().contains("uq_one_open_case_per_instruction");
    }
}
