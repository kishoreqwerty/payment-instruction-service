package com.kishore.payments.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.event.InstructionExceptionEvent;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.exception.api.InvestigationConfirmationResponse;
import com.kishore.payments.exception.api.JustificationRequest;
import com.kishore.payments.exception.cases.ExceptionCaseEntity;
import com.kishore.payments.exception.repair.FieldChange;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Acceptance criterion 8: every mutating endpoint returns 403 for the wrong role, not merely 200 for the right one. */
class SecurityAuthorizationTest extends AbstractExceptionServiceIntegrationTest {

    @Test
    void unauthenticatedRequestIsRejected() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/v1/cases"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void viewerCannotProposeApproveRetryOrResolve() {
        ExceptionCaseEntity businessCase = openBusinessFailureCase();
        ExceptionCaseEntity investigationCase = openInvestigationCase();

        assertThat(asUser("viewer")
                .postForEntity(url("/v1/cases/" + businessCase.getCaseId() + "/repairs"),
                        List.of(new FieldChange("creditorName", "New Name")), String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(asUser("viewer").postForEntity(url("/v1/repairs/" + java.util.UUID.randomUUID() + "/approve"), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(asUser("viewer").postForEntity(url("/v1/cases/" + businessCase.getCaseId() + "/reject"), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(asUser("viewer")
                .postForEntity(url("/v1/cases/" + investigationCase.getCaseId() + "/investigation/confirm-sent"),
                        new JustificationRequest("nope"), String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(asUser("viewer")
                .postForEntity(url("/v1/investigation-confirmations/" + java.util.UUID.randomUUID() + "/approve"), null, String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(asUser("viewer")
                .postForEntity(url("/v1/cases/" + investigationCase.getCaseId() + "/investigation/reject"),
                        new JustificationRequest("nope"), String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * confirm-sent's maker-checker split, from the role side: MAKER can
     * propose it (it is the maker half of the pair, unlike before this
     * became maker-checker) but cannot approve its own proposal's
     * confirmation resource, cannot approve a repair action, and cannot
     * reject -- reject stays CHECKER-only.
     */
    @Test
    void makerCanProposeConfirmSentButCannotApproveOrReject() {
        ExceptionCaseEntity investigationCase = openInvestigationCase();

        ResponseEntity<InvestigationConfirmationResponse> proposed = asUser("maker1").postForEntity(
                url("/v1/cases/" + investigationCase.getCaseId() + "/investigation/confirm-sent"),
                new JustificationRequest("confirmed out of band"), InvestigationConfirmationResponse.class);
        assertThat(proposed.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(asUser("maker1").postForEntity(url("/v1/repairs/" + java.util.UUID.randomUUID() + "/approve"), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(asUser("maker1")
                .postForEntity(url("/v1/investigation-confirmations/" + proposed.getBody().confirmationId() + "/approve"), null, String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(asUser("maker1")
                .postForEntity(url("/v1/cases/" + investigationCase.getCaseId() + "/investigation/reject"),
                        new JustificationRequest("nope"), String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void everyRoleCanRead() {
        ExceptionCaseEntity businessCase = openBusinessFailureCase();
        for (String user : List.of("viewer", "maker1", "checker1")) {
            assertThat(asUser(user).getForEntity(url("/v1/cases"), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(asUser(user).getForEntity(url("/v1/cases/" + businessCase.getCaseId()), String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    private ExceptionCaseEntity openBusinessFailureCase() {
        PaymentInstructionEntity instruction = seedInstructionAtException("INVALID-IBAN");
        publishExceptionEvent(
                instruction.getInstructionId(), instruction.getUetr(), instruction.getEndToEndId(), 2, FailureStage.VALIDATION,
                new InstructionExceptionEvent.Detail("AC01", Repairability.REPAIRABLE, "creditorAccount", "invalid IBAN checksum"));
        awaitCondition(Duration.ofSeconds(5), () -> !cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).isEmpty());
        return cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).get(0);
    }

    private ExceptionCaseEntity openInvestigationCase() {
        PaymentInstructionEntity instruction = seedInstructionAtInvestigation();
        publishExceptionEvent(
                instruction.getInstructionId(), instruction.getUetr(), instruction.getEndToEndId(), 2, FailureStage.RECONCILIATION,
                new InstructionExceptionEvent.Detail(null, Repairability.TRANSIENT, null, "inconclusive"));
        awaitCondition(Duration.ofSeconds(5), () -> !cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).isEmpty());
        return cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).get(0);
    }
}
