package com.kishore.payments.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.event.InstructionExceptionEvent;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.exception.api.CaseSummaryResponse;
import com.kishore.payments.exception.api.InvestigationConfirmationResponse;
import com.kishore.payments.exception.api.JustificationRequest;
import com.kishore.payments.exception.cases.CaseStatus;
import com.kishore.payments.exception.cases.CaseType;
import com.kishore.payments.exception.cases.ExceptionCaseEntity;
import com.kishore.payments.exception.cases.Resolution;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Acceptance criterion 6: INVESTIGATION resolution requires CHECKER (reject)
 * or maker-checker (confirm-sent) and stores a justification. confirm-sent
 * is maker-checker, not checker-only, per the phase brief's own revision:
 * unlike a field repair, nothing downstream re-validates a wrong
 * confirm-sent, so it gets the same two-person control a field repair does
 * -- see ExceptionCaseService's own comment on why reject, the recoverable
 * direction, does not need it.
 */
class InvestigationResolutionTest extends AbstractExceptionServiceIntegrationTest {

    @Test
    void confirmSentIsMakerCheckerAndMovesInvestigationToSentOnceApproved() {
        ExceptionCaseEntity exceptionCase = openInvestigationCase();

        ResponseEntity<InvestigationConfirmationResponse> proposed = asUser("maker1").postForEntity(
                url("/v1/cases/" + exceptionCase.getCaseId() + "/investigation/confirm-sent"),
                new JustificationRequest("Confirmed with rail ops via phone at 14:02 UTC, UETR is on their books."),
                InvestigationConfirmationResponse.class);
        assertThat(proposed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(cases.findById(exceptionCase.getCaseId()).orElseThrow().getStatus()).isEqualTo(CaseStatus.PENDING_APPROVAL);
        // Not yet applied: the instruction stays at INVESTIGATION until a
        // second person approves, exactly like an unapproved repair action
        // never applies a field change.
        assertThat(instructions.findById(exceptionCase.getInstructionId()).orElseThrow().getState()).isEqualTo(InstructionState.INVESTIGATION);

        ResponseEntity<InvestigationConfirmationResponse> approved = asUser("checker1").postForEntity(
                url("/v1/investigation-confirmations/" + proposed.getBody().confirmationId() + "/approve"), null,
                InvestigationConfirmationResponse.class);

        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody().approvedBy()).isEqualTo("checker1");
        assertThat(instructions.findById(exceptionCase.getInstructionId()).orElseThrow().getState()).isEqualTo(InstructionState.SENT);

        ExceptionCaseEntity resolved = cases.findById(exceptionCase.getCaseId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(CaseStatus.RESOLVED);
        assertThat(resolved.getResolution()).isEqualTo(Resolution.CONFIRMED_SENT);
        assertThat(resolved.getJustification()).contains("rail ops");
    }

    @Test
    void confirmSentSameUserCannotProposeAndApprove() {
        ExceptionCaseEntity exceptionCase = openInvestigationCase();
        ResponseEntity<InvestigationConfirmationResponse> proposed = asUser("maker1").postForEntity(
                url("/v1/cases/" + exceptionCase.getCaseId() + "/investigation/confirm-sent"),
                new JustificationRequest("confirmed out of band"), InvestigationConfirmationResponse.class);

        ResponseEntity<String> response = asUser("maker1")
                .postForEntity(url("/v1/investigation-confirmations/" + proposed.getBody().confirmationId() + "/approve"), null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(instructions.findById(exceptionCase.getInstructionId()).orElseThrow().getState()).isEqualTo(InstructionState.INVESTIGATION);
    }

    @Test
    void confirmSentMakerCheckerConstraintFiresEvenWhenServiceCheckIsBypassed() {
        ExceptionCaseEntity exceptionCase = openInvestigationCase();
        ResponseEntity<InvestigationConfirmationResponse> proposed = asUser("maker1").postForEntity(
                url("/v1/cases/" + exceptionCase.getCaseId() + "/investigation/confirm-sent"),
                new JustificationRequest("confirmed out of band"), InvestigationConfirmationResponse.class);
        UUID confirmationId = proposed.getBody().confirmationId();

        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE exceptions.investigation_confirmation SET approved_by = ?, approved_at = now() WHERE confirmation_id = ?",
                        "maker1", confirmationId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_investigation_maker_checker");
    }

    @Test
    void aDifferentCheckerSucceeds() {
        ExceptionCaseEntity exceptionCase = openInvestigationCase();
        ResponseEntity<InvestigationConfirmationResponse> proposed = asUser("maker1").postForEntity(
                url("/v1/cases/" + exceptionCase.getCaseId() + "/investigation/confirm-sent"),
                new JustificationRequest("confirmed out of band"), InvestigationConfirmationResponse.class);

        ResponseEntity<InvestigationConfirmationResponse> response = asUser("checker2").postForEntity(
                url("/v1/investigation-confirmations/" + proposed.getBody().confirmationId() + "/approve"), null,
                InvestigationConfirmationResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().approvedBy()).isEqualTo("checker2");
    }

    @Test
    void rejectMovesInvestigationToExceptionThenRejected() {
        ExceptionCaseEntity exceptionCase = openInvestigationCase();

        ResponseEntity<CaseSummaryResponse> response = asUser("checker1").postForEntity(
                url("/v1/cases/" + exceptionCase.getCaseId() + "/investigation/reject"),
                new JustificationRequest("Rail confirms this UETR was never received; will not be retried."),
                CaseSummaryResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(CaseStatus.REJECTED);
        assertThat(instructions.findById(exceptionCase.getInstructionId()).orElseThrow().getState()).isEqualTo(InstructionState.REJECTED);

        var timeline = events.findByInstructionIdOrderBySequenceNoAsc(exceptionCase.getInstructionId());
        assertThat(timeline).extracting(e -> e.getToState())
                .containsSubsequence(InstructionState.INVESTIGATION, InstructionState.EXCEPTION, InstructionState.REJECTED);
    }

    @Test
    void viewerCannotProposeOrApproveConfirmSentOrReject() {
        ExceptionCaseEntity exceptionCase = openInvestigationCase();

        ResponseEntity<String> proposeResponse = asUser("viewer").postForEntity(
                url("/v1/cases/" + exceptionCase.getCaseId() + "/investigation/confirm-sent"),
                new JustificationRequest("trying anyway"), String.class);
        assertThat(proposeResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> rejectResponse = asUser("viewer").postForEntity(
                url("/v1/cases/" + exceptionCase.getCaseId() + "/investigation/reject"),
                new JustificationRequest("trying anyway"), String.class);
        assertThat(rejectResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void proposingARepairAgainstAnInvestigationCaseIsRejected() {
        ExceptionCaseEntity exceptionCase = openInvestigationCase();

        ResponseEntity<String> response = asUser("maker1").postForEntity(
                url("/v1/cases/" + exceptionCase.getCaseId() + "/repairs"),
                java.util.List.of(new com.kishore.payments.exception.repair.FieldChange("creditorName", "New Name")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private ExceptionCaseEntity openInvestigationCase() {
        PaymentInstructionEntity instruction = seedInstructionAtInvestigation();
        publishExceptionEvent(
                instruction.getInstructionId(), instruction.getUetr(), instruction.getEndToEndId(), 2, FailureStage.RECONCILIATION,
                new InstructionExceptionEvent.Detail(null, Repairability.TRANSIENT, null, "inconclusive reconciliation window exhausted"));
        awaitCondition(Duration.ofSeconds(5), () -> cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).size() == 1);
        ExceptionCaseEntity opened = cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).get(0);
        assertThat(opened.getCaseType()).isEqualTo(CaseType.INVESTIGATION);
        return opened;
    }
}
