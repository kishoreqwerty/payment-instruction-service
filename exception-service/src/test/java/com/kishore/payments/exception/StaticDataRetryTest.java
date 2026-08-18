package com.kishore.payments.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.event.InstructionExceptionEvent;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.exception.api.CaseSummaryResponse;
import com.kishore.payments.exception.cases.CaseStatus;
import com.kishore.payments.exception.cases.ExceptionCaseEntity;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** §5 and §10's static-data scenario: missing correspondent -> STATIC_DATA case -> retry (still missing) -> stays open, attempts increments. */
class StaticDataRetryTest extends AbstractExceptionServiceIntegrationTest {

    @Test
    void retryStaysOpenAndIncrementsAttemptsWhenDataIsStillMissing() {
        PaymentInstructionEntity instruction = seedInstructionAtException("FR1420041010050500013M02606");
        publishExceptionEvent(
                instruction.getInstructionId(), instruction.getUetr(), instruction.getEndToEndId(), 2, FailureStage.ENRICHMENT,
                new InstructionExceptionEvent.Detail("RC01", Repairability.STATIC_DATA, null, "no correspondent relationship for creditor agent"));
        awaitCondition(Duration.ofSeconds(5), () -> cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).size() == 1);
        ExceptionCaseEntity exceptionCase = cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).get(0);
        assertThat(exceptionCase.getRepairability()).isEqualTo(Repairability.STATIC_DATA);

        ResponseEntity<CaseSummaryResponse> response =
                asUser("maker1").postForEntity(url("/v1/cases/" + exceptionCase.getCaseId() + "/retry"), null, CaseSummaryResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(CaseStatus.OPEN);
        assertThat(response.getBody().repairAttempts()).isEqualTo(1);
        // REPAIRED, not VALIDATED: this service transitions only as far as
        // REPAIRED itself -- REPAIRED -> VALIDATED is ValidationConsumer's
        // own re-validation (see ExceptionCaseService's class javadoc).
        assertThat(instructions.findById(instruction.getInstructionId()).orElseThrow().getState()).isEqualTo(InstructionState.REPAIRED);

        // Re-fails (correspondent still missing) -- must append to the same
        // still-open case, not open a second one, per the phase brief's own
        // wording: "stays open, attempts increments."
        simulateRevalidationFailure(
                instruction.getInstructionId(), instruction.getUetr(), instruction.getEndToEndId(), 4, FailureStage.ENRICHMENT,
                new InstructionExceptionEvent.Detail("RC01", Repairability.STATIC_DATA, null, "still no correspondent relationship"));
        awaitCondition(Duration.ofSeconds(5), () -> cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).size() == 1);
        assertThat(cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId())).hasSize(1);
        assertThat(cases.findById(exceptionCase.getCaseId()).orElseThrow().getStatus()).isEqualTo(CaseStatus.OPEN);
    }

    @Test
    void retryRequiresMakerRoleAndOnlyAppliesToStaticDataCases() {
        PaymentInstructionEntity instruction = seedInstructionAtException("INVALID-IBAN");
        publishExceptionEvent(
                instruction.getInstructionId(), instruction.getUetr(), instruction.getEndToEndId(), 2, FailureStage.VALIDATION,
                new InstructionExceptionEvent.Detail("AC01", Repairability.REPAIRABLE, "creditorAccount", "bad IBAN"));
        awaitCondition(Duration.ofSeconds(5), () -> cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).size() == 1);
        ExceptionCaseEntity exceptionCase = cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).get(0);

        ResponseEntity<String> wrongType = asUser("maker1").postForEntity(url("/v1/cases/" + exceptionCase.getCaseId() + "/retry"), null, String.class);
        assertThat(wrongType.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<String> wrongRole = asUser("viewer").postForEntity(url("/v1/cases/" + exceptionCase.getCaseId() + "/retry"), null, String.class);
        assertThat(wrongRole.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
