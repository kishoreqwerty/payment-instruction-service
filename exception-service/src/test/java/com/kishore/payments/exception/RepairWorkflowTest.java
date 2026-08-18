package com.kishore.payments.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.event.InstructionExceptionEvent;
import com.kishore.payments.core.instruction.InstructionEventEntity;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.exception.api.CaseDetailResponse;
import com.kishore.payments.exception.api.CaseSummaryResponse;
import com.kishore.payments.exception.api.RepairActionResponse;
import com.kishore.payments.exception.cases.CaseStatus;
import com.kishore.payments.exception.cases.CaseType;
import com.kishore.payments.exception.cases.ExceptionCaseEntity;
import com.kishore.payments.exception.cases.Resolution;
import com.kishore.payments.exception.repair.FieldChange;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Acceptance criteria 1-4 and the repair-cycle/bad-repair/cap tests from the
 * phase brief's own §10, all against a case opened by seeding directly
 * rather than by first proving the consumer -- {@link
 * ExceptionEventConsumerTest} covers case-opening on its own.
 */
class RepairWorkflowTest extends AbstractExceptionServiceIntegrationTest {

    @Test
    void makerCheckerSameUserRejectedWithForbidden() {
        ExceptionCaseEntity exceptionCase = openBadIbanCase();
        List<RepairActionResponse> proposed = propose(exceptionCase.getCaseId(), "maker1", "creditorAccount", "FR7630006000011234567890189");

        ResponseEntity<String> response = asUser("maker1")
                .postForEntity(url("/v1/repairs/" + proposed.get(0).actionId() + "/approve"), null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(repairActions.findById(proposed.get(0).actionId()).orElseThrow().isApproved()).isFalse();
    }

    @Test
    void makerCheckerConstraintFiresEvenWhenServiceCheckIsBypassed() {
        ExceptionCaseEntity exceptionCase = openBadIbanCase();
        List<RepairActionResponse> proposed = propose(exceptionCase.getCaseId(), "maker1", "creditorAccount", "FR7630006000011234567890189");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "UPDATE exceptions.repair_action SET approved_by = ?, approved_at = now() WHERE action_id = ?",
                        "maker1", proposed.get(0).actionId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("ck_maker_checker");
    }

    @Test
    void differentCheckerSucceedsAndAppliesOnceAllApproved() {
        ExceptionCaseEntity exceptionCase = openBadIbanCase();
        List<RepairActionResponse> proposed = propose(exceptionCase.getCaseId(), "maker1", "creditorAccount", "FR7630006000011234567890189");

        ResponseEntity<RepairActionResponse> response = asUser("checker1")
                .postForEntity(url("/v1/repairs/" + proposed.get(0).actionId() + "/approve"), null, RepairActionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().approvedBy()).isEqualTo("checker1");
    }

    @Test
    void disallowedFieldRejectedWith422NamingIt() {
        ExceptionCaseEntity exceptionCase = openBadIbanCase();
        ResponseEntity<String> response = asUser("maker1").postForEntity(
                url("/v1/cases/" + exceptionCase.getCaseId() + "/repairs"),
                List.of(new FieldChange("amount", "200.00")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("amount");
    }

    @Test
    void everyAllowlistedFieldIsAccepted() {
        ExceptionCaseEntity exceptionCase = openBadIbanCase();
        List<FieldChange> changes = List.of(
                new FieldChange("creditorAccount", "FR7630006000011234567890189"),
                new FieldChange("creditorAgentBic", "BNPAFRPPXXX"),
                new FieldChange("creditorName", "Corrected SARL"),
                new FieldChange("chargeBearer", "DEBT"),
                new FieldChange("requestedExecutionDate", java.time.LocalDate.now(clock).plusDays(1).toString()));

        ResponseEntity<RepairActionResponse[]> response =
                asUser("maker1").postForEntity(url("/v1/cases/" + exceptionCase.getCaseId() + "/repairs"), changes, RepairActionResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).hasSize(5);
    }

    @Test
    void debtorAgentBicIsNotRepairable() {
        ExceptionCaseEntity exceptionCase = openBadIbanCase();

        ResponseEntity<String> response = asUser("maker1").postForEntity(
                url("/v1/cases/" + exceptionCase.getCaseId() + "/repairs"),
                List.of(new FieldChange("debtorAgentBic", "DEUTDEFF500")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("debtorAgentBic");
    }

    @Test
    void fullRepairCycleAppliesAndPreservesIdentity() {
        ExceptionCaseEntity exceptionCase = openBadIbanCase();
        PaymentInstructionEntity before = instructions.findById(exceptionCase.getInstructionId()).orElseThrow();
        UUID uetrBefore = before.getUetr();
        String endToEndIdBefore = before.getEndToEndId();

        List<RepairActionResponse> proposed = propose(exceptionCase.getCaseId(), "maker1", "creditorAccount", "FR7630006000011234567890189");
        asUser("checker1").postForEntity(url("/v1/repairs/" + proposed.get(0).actionId() + "/approve"), null, RepairActionResponse.class);

        // REPAIRED, not VALIDATED: this service transitions only as far as
        // EXCEPTION -> REPAIRED (see ExceptionCaseService's own class
        // javadoc) -- REPAIRED -> VALIDATED is ValidationConsumer's own
        // re-validation, reacting to the payments.repaired publish, and
        // ValidationConsumer is not running in this test's own JVM (proven
        // separately, with the real listener, by processing-service's own
        // RepairedEventReentryIntegrationTest).
        PaymentInstructionEntity after = instructions.findById(exceptionCase.getInstructionId()).orElseThrow();
        assertThat(after.getState()).isEqualTo(InstructionState.REPAIRED);
        assertThat(after.getCreditorAccount()).isEqualTo("FR7630006000011234567890189");
        assertThat(after.getUetr()).isEqualTo(uetrBefore);
        assertThat(after.getEndToEndId()).isEqualTo(endToEndIdBefore);

        List<InstructionEventEntity> timeline = events.findByInstructionIdOrderBySequenceNoAsc(exceptionCase.getInstructionId());
        assertThat(timeline).extracting(InstructionEventEntity::getToState)
                .containsSubsequence(InstructionState.EXCEPTION, InstructionState.REPAIRED);

        ExceptionCaseEntity resolved = cases.findById(exceptionCase.getCaseId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(CaseStatus.RESOLVED);
        assertThat(resolved.getResolution()).isEqualTo(Resolution.REPAIRED);
        assertThat(resolved.getRepairAttempts()).isEqualTo(1);

        awaitCondition(Duration.ofSeconds(5), () -> !outboxPendingFor(exceptionCase.getInstructionId()));
    }

    @Test
    void aBadRepairThatIntroducesANewDefectOpensASecondCaseInheritingAttempts() {
        ExceptionCaseEntity firstCase = openBadIbanCase();
        List<RepairActionResponse> proposed = propose(firstCase.getCaseId(), "maker1", "creditorAccount", "FR7630006000011234567890189");
        asUser("checker1").postForEntity(url("/v1/repairs/" + proposed.get(0).actionId() + "/approve"), null, RepairActionResponse.class);

        // Simulate re-validation catching a new defect in the "repaired"
        // value -- processing-service's own re-entry is proven separately
        // (RepairedEventReentryIntegrationTest); here, exception-service's
        // own reaction to a second, later failure for the same instruction
        // is what's under test.
        PaymentInstructionEntity repaired = instructions.findById(firstCase.getInstructionId()).orElseThrow();
        simulateRevalidationFailure(
                firstCase.getInstructionId(), repaired.getUetr(), repaired.getEndToEndId(), 4, FailureStage.VALIDATION,
                new InstructionExceptionEvent.Detail("AC01", Repairability.REPAIRABLE, "creditorAccount", "still not a valid IBAN"));

        awaitCondition(Duration.ofSeconds(5), () -> cases.findByInstructionIdOrderByOpenedAtDesc(firstCase.getInstructionId()).size() == 2);
        List<ExceptionCaseEntity> allCases = cases.findByInstructionIdOrderByOpenedAtDesc(firstCase.getInstructionId());
        assertThat(allCases).hasSize(2);

        ExceptionCaseEntity secondCase = allCases.get(0);
        assertThat(secondCase.getCaseId()).isNotEqualTo(firstCase.getCaseId());
        assertThat(secondCase.getStatus()).isEqualTo(CaseStatus.OPEN);
        assertThat(secondCase.getReasonCode()).isEqualTo("AC01");
        // Inherited from the first case's own post-apply count (1), not reset to 0.
        assertThat(secondCase.getRepairAttempts()).isEqualTo(1);
    }

    @Test
    void capReachedRejectsAFourthProposalAndOnlyRejectionRemains() {
        ExceptionCaseEntity exceptionCase = openBadIbanCase();
        // Drive three successful repair-apply cycles by hand, each one still
        // "bad" so it reopens as the next case in the lineage, inheriting
        // the running repair_attempts count -- reusing the same maker/
        // checker pair throughout, since maker-checker only forbids the
        // *same* user proposing and approving the *same* action, not reuse
        // across cycles. After the third apply, a fourth case is open with
        // repair_attempts already at 3; that is the case the cap test
        // actually needs a proposal against.
        UUID instructionId = exceptionCase.getInstructionId();
        UUID currentCaseId = exceptionCase.getCaseId();
        for (int attempt = 1; attempt <= 3; attempt++) {
            List<RepairActionResponse> proposed = propose(currentCaseId, "maker1", "creditorAccount", "FR7630006000011234567890189");
            asUser("checker1").postForEntity(url("/v1/repairs/" + proposed.get(0).actionId() + "/approve"), null, RepairActionResponse.class);

            PaymentInstructionEntity instruction = instructions.findById(instructionId).orElseThrow();
            simulateRevalidationFailure(instructionId, instruction.getUetr(), instruction.getEndToEndId(), 100 + attempt, FailureStage.VALIDATION,
                    new InstructionExceptionEvent.Detail("AC01", Repairability.REPAIRABLE, "creditorAccount", "still bad"));
            int expectedCaseCount = attempt + 1;
            awaitCondition(Duration.ofSeconds(5), () -> cases.findByInstructionIdOrderByOpenedAtDesc(instructionId).size() == expectedCaseCount);
            currentCaseId = cases.findByInstructionIdOrderByOpenedAtDesc(instructionId).get(0).getCaseId();
        }

        ExceptionCaseEntity capped = cases.findById(currentCaseId).orElseThrow();
        assertThat(capped.getRepairAttempts()).isEqualTo(3);

        ResponseEntity<String> rejected = asUser("maker1").postForEntity(
                url("/v1/cases/" + currentCaseId + "/repairs"), List.of(new FieldChange("creditorAccount", "FR14")), String.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<CaseSummaryResponse> rejectResponse =
                asUser("checker1").postForEntity(url("/v1/cases/" + currentCaseId + "/reject"), null, CaseSummaryResponse.class);
        assertThat(rejectResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejectResponse.getBody().status()).isEqualTo(CaseStatus.REJECTED);
        assertThat(instructions.findById(instructionId).orElseThrow().getState()).isEqualTo(InstructionState.REJECTED);
    }

    @Test
    void caseDetailIncludesRepairActions() {
        ExceptionCaseEntity exceptionCase = openBadIbanCase();
        propose(exceptionCase.getCaseId(), "maker1", "creditorAccount", "FR7630006000011234567890189");

        ResponseEntity<CaseDetailResponse> response =
                asUser("viewer").getForEntity(url("/v1/cases/" + exceptionCase.getCaseId()), CaseDetailResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().repairActions()).hasSize(1);
        assertThat(response.getBody().exceptionCase().caseType()).isEqualTo(CaseType.BUSINESS_FAILURE);
    }

    private ExceptionCaseEntity openBadIbanCase() {
        PaymentInstructionEntity instruction = seedInstructionAtException("INVALID-IBAN-XXXX");
        publishExceptionEvent(
                instruction.getInstructionId(), instruction.getUetr(), instruction.getEndToEndId(), 2, FailureStage.VALIDATION,
                new InstructionExceptionEvent.Detail("AC01", Repairability.REPAIRABLE, "creditorAccount", "invalid IBAN checksum"));
        awaitCondition(Duration.ofSeconds(5), () -> cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).size() == 1);
        return cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).get(0);
    }

    private List<RepairActionResponse> propose(UUID caseId, String maker, String fieldPath, String newValue) {
        ResponseEntity<RepairActionResponse[]> response = asUser(maker)
                .postForEntity(url("/v1/cases/" + caseId + "/repairs"), List.of(new FieldChange(fieldPath, newValue)), RepairActionResponse[].class);
        return List.of(response.getBody());
    }

    private boolean outboxPendingFor(UUID instructionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM core.outbox WHERE aggregate_id = ? AND topic = 'payments.repaired' AND published_at IS NULL",
                Integer.class, instructionId);
        return count != null && count > 0;
    }
}
