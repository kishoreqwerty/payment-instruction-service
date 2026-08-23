package com.kishore.payments.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.event.InstructionExceptionEvent;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.exception.cases.CaseStatus;
import com.kishore.payments.exception.cases.ExceptionCaseEntity;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Acceptance criterion 7: classifier unavailability leaves case creation unaffected. This test
 * environment never sets ANTHROPIC_API_KEY (see {@code ClassifierClient}'s own unit tests for the
 * client-level guarantee this exercises end to end here), so every case opened in this whole
 * module's test suite already goes through this exact path -- this test just makes the acceptance
 * criterion an explicit, named assertion rather than an implicit side effect of every other test
 * happening to pass.
 */
class ClassifierCaseOpeningIntegrationTest extends AbstractExceptionServiceIntegrationTest {

    @Test
    void aCaseOpensNormallyWhenTheClassifierHasNoApiKeyConfigured() {
        PaymentInstructionEntity instruction = seedInstructionAtException("INVALID-IBAN");
        publishExceptionEvent(
                instruction.getInstructionId(), instruction.getUetr(), instruction.getEndToEndId(), 2, FailureStage.VALIDATION,
                new InstructionExceptionEvent.Detail("AC01", Repairability.REPAIRABLE, "creditorAccount", "invalid IBAN checksum"));

        awaitCondition(Duration.ofSeconds(10), () -> !cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).isEmpty());
        ExceptionCaseEntity opened = cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).get(0);

        assertThat(opened.getStatus()).isEqualTo(CaseStatus.OPEN);
        assertThat(opened.getReasonCode()).isEqualTo("AC01");
        // No classifier ran (no API key in this test environment), so nothing ever wrote these --
        // the point being that the case opened successfully and promptly regardless.
        assertThat(opened.getClassifierCode()).isNull();
        assertThat(opened.getClassifierConf()).isNull();
        assertThat(opened.getClassifierAccepted()).isNull();
    }
}
