package com.kishore.payments.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.event.InstructionExceptionEvent;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.exception.api.CaseSummaryResponse;
import com.kishore.payments.exception.api.MeController.MeResponse;
import com.kishore.payments.exception.api.RepairActionResponse;
import com.kishore.payments.exception.cases.ExceptionCaseEntity;
import com.kishore.payments.exception.repair.FieldChange;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The four backend gaps building the ops-dashboard (Phase 9) exposed in the
 * read API, each addressed as a small additive change rather than a client
 * workaround -- see PHASE-9-REPORT.md §5 for why each one was judged a
 * genuine API gap rather than something the client should paper over.
 */
class Phase9ApiGapsTest extends AbstractExceptionServiceIntegrationTest {

    @Test
    void caseSummaryCarriesInstructionFieldsForTheQueueTable() {
        PaymentInstructionEntity instruction = seedInstructionAtException("INVALID-IBAN");
        publishExceptionEvent(
                instruction.getInstructionId(), instruction.getUetr(), instruction.getEndToEndId(), 2, FailureStage.VALIDATION,
                new InstructionExceptionEvent.Detail("AC01", Repairability.REPAIRABLE, "creditorAccount", "invalid IBAN checksum"));
        awaitCondition(Duration.ofSeconds(5), () -> !cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).isEmpty());

        // The single-case endpoint exercises CaseSummaryResponse.of(entity, instruction) directly.
        ExceptionCaseEntity opened = cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).get(0);
        ResponseEntity<com.kishore.payments.exception.api.CaseDetailResponse> detail = asUser("viewer")
                .getForEntity(url("/v1/cases/" + opened.getCaseId()), com.kishore.payments.exception.api.CaseDetailResponse.class);

        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        CaseSummaryResponse summary = detail.getBody().exceptionCase();
        assertThat(summary.endToEndId()).isEqualTo(instruction.getEndToEndId());
        assertThat(summary.amount()).isEqualByComparingTo(instruction.getAmount());
        assertThat(summary.currency()).isEqualTo(instruction.getCurrency());
        assertThat(summary.creditorName()).isEqualTo(instruction.getCreditorName());

        // The list endpoint exercises the batched (findAllById) enrichment path -- Page<T>'s own JSON
        // shape ({"content": [...], ...}) is asserted textually rather than deserialised into a page
        // type this test doesn't otherwise need.
        ResponseEntity<String> list = asUser("viewer").getForEntity(url("/v1/cases?size=200"), String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).contains(instruction.getEndToEndId(), instruction.getCreditorName());
    }

    @Test
    void caseListFiltersOnRepairability() {
        PaymentInstructionEntity instruction = seedInstructionAtException("INVALID-IBAN-2");
        publishExceptionEvent(
                instruction.getInstructionId(), instruction.getUetr(), instruction.getEndToEndId(), 2, FailureStage.VALIDATION,
                new InstructionExceptionEvent.Detail("AC01", Repairability.REPAIRABLE, "creditorAccount", "invalid IBAN checksum"));
        awaitCondition(Duration.ofSeconds(5), () -> !cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).isEmpty());

        ResponseEntity<String> matching =
                asUser("viewer").getForEntity(url("/v1/cases?repairability=REPAIRABLE&size=200"), String.class);
        ResponseEntity<String> notMatching =
                asUser("viewer").getForEntity(url("/v1/cases?repairability=UNREPAIRABLE&size=200"), String.class);

        assertThat(matching.getBody()).contains(instruction.getInstructionId().toString());
        assertThat(notMatching.getBody()).doesNotContain(instruction.getInstructionId().toString());
    }

    @Test
    void pendingRepairsListsOnlyUnapprovedActionsWithTheDiff() {
        PaymentInstructionEntity instruction = seedInstructionAtException("INVALID-IBAN-3");
        publishExceptionEvent(
                instruction.getInstructionId(), instruction.getUetr(), instruction.getEndToEndId(), 2, FailureStage.VALIDATION,
                new InstructionExceptionEvent.Detail("AC01", Repairability.REPAIRABLE, "creditorAccount", "invalid IBAN checksum"));
        awaitCondition(Duration.ofSeconds(5), () -> !cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).isEmpty());
        ExceptionCaseEntity opened = cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).get(0);

        asUser("maker1").postForEntity(
                url("/v1/cases/" + opened.getCaseId() + "/repairs"),
                List.of(new FieldChange("creditorName", "Corrected SARL")), String.class);

        ResponseEntity<RepairActionResponse[]> pending =
                asUser("checker1").getForEntity(url("/v1/repairs/pending"), RepairActionResponse[].class);

        assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pending.getBody())
                .extracting(RepairActionResponse::caseId, RepairActionResponse::fieldPath, RepairActionResponse::newValue,
                        RepairActionResponse::proposedBy)
                .contains(tuple(opened.getCaseId(), "creditorName", "Corrected SARL", "maker1"));
    }

    @Test
    void timelineInterleavesInvestigationConfirmations() {
        PaymentInstructionEntity instruction = seedInstructionAtInvestigation();
        publishExceptionEvent(
                instruction.getInstructionId(), instruction.getUetr(), instruction.getEndToEndId(), 2, FailureStage.RECONCILIATION,
                new InstructionExceptionEvent.Detail(null, Repairability.TRANSIENT, null, "inconclusive"));
        awaitCondition(Duration.ofSeconds(5), () -> !cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).isEmpty());
        ExceptionCaseEntity opened = cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).get(0);

        ResponseEntity<com.kishore.payments.exception.api.InvestigationConfirmationResponse> proposed = asUser("maker1").postForEntity(
                url("/v1/cases/" + opened.getCaseId() + "/investigation/confirm-sent"),
                new com.kishore.payments.exception.api.JustificationRequest("confirmed with the rail's ops desk by phone"),
                com.kishore.payments.exception.api.InvestigationConfirmationResponse.class);
        asUser("checker1").postForEntity(
                url("/v1/investigation-confirmations/" + proposed.getBody().confirmationId() + "/approve"), null, String.class);

        ResponseEntity<String> timeline = asUser("viewer").getForEntity(url("/v1/instructions/" + instruction.getInstructionId() + "/timeline"), String.class);

        assertThat(timeline.getBody()).contains("CONFIRMATION_PROPOSED", "CONFIRMATION_APPROVED", "confirmed with the rail's ops desk by phone");
    }

    @Test
    void caseDetailCarriesTheInstructionAndAnyPendingInvestigationConfirmation() {
        PaymentInstructionEntity instruction = seedInstructionAtInvestigation();
        publishExceptionEvent(
                instruction.getInstructionId(), instruction.getUetr(), instruction.getEndToEndId(), 2, FailureStage.RECONCILIATION,
                new InstructionExceptionEvent.Detail(null, Repairability.TRANSIENT, null, "inconclusive"));
        awaitCondition(Duration.ofSeconds(5), () -> !cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).isEmpty());
        ExceptionCaseEntity opened = cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).get(0);
        asUser("maker1").postForEntity(
                url("/v1/cases/" + opened.getCaseId() + "/investigation/confirm-sent"),
                new com.kishore.payments.exception.api.JustificationRequest("confirmed out of band"), String.class);

        ResponseEntity<com.kishore.payments.exception.api.CaseDetailResponse> detail = asUser("viewer")
                .getForEntity(url("/v1/cases/" + opened.getCaseId()), com.kishore.payments.exception.api.CaseDetailResponse.class);

        assertThat(detail.getBody().instruction().creditorName()).isEqualTo(instruction.getCreditorName());
        assertThat(detail.getBody().investigationConfirmations()).hasSize(1);
        assertThat(detail.getBody().investigationConfirmations().get(0).proposedBy()).isEqualTo("maker1");
    }

    @Test
    void meReturnsTheAuthenticatedUsersRoles() {
        ResponseEntity<MeResponse> checker = asUser("checker1").getForEntity(url("/v1/me"), MeResponse.class);
        ResponseEntity<MeResponse> dual = asUser("dual1").getForEntity(url("/v1/me"), MeResponse.class);

        assertThat(checker.getBody().username()).isEqualTo("checker1");
        assertThat(checker.getBody().roles()).containsExactly("CHECKER");
        assertThat(dual.getBody().roles()).containsExactlyInAnyOrder("MAKER", "CHECKER");
    }

    @Test
    void unauthenticatedRequestGetsAJsonBodyNotABlankResponse() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/v1/cases"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"error\"", "UNAUTHORIZED");
    }

    @Test
    void repairableFieldsListsExactlyTheFiveFieldAllowlist() {
        ResponseEntity<com.kishore.payments.exception.api.RepairableFieldsController.RepairableFieldResponse[]> response =
                asUser("maker1").getForEntity(
                        url("/v1/repairable-fields"), com.kishore.payments.exception.api.RepairableFieldsController.RepairableFieldResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .extracting(com.kishore.payments.exception.api.RepairableFieldsController.RepairableFieldResponse::fieldPath)
                .containsExactlyInAnyOrder("creditorAccount", "creditorAgentBic", "creditorName", "chargeBearer", "requestedExecutionDate");
    }

    @Test
    void wrongRoleGetsAJsonForbiddenBodyNotABlankResponse() {
        ResponseEntity<String> response = asUser("viewer").postForEntity(url("/v1/cases/" + java.util.UUID.randomUUID() + "/reject"), null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"error\"", "FORBIDDEN");
    }
}
