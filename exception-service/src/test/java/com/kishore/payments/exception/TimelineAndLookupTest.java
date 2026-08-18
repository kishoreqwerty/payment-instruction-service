package com.kishore.payments.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.event.InstructionExceptionEvent;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.exception.api.InstructionSummaryResponse;
import com.kishore.payments.exception.api.RepairActionResponse;
import com.kishore.payments.exception.cases.ExceptionCaseEntity;
import com.kishore.payments.exception.repair.FieldChange;
import com.kishore.payments.exception.timeline.TimelineEntry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;

/** Acceptance criterion 7: the timeline endpoint returns every transition with actor, timestamp and reason code, repair actions interleaved. */
class TimelineAndLookupTest extends AbstractExceptionServiceIntegrationTest {

    @Test
    void timelineIncludesTransitionsAndRepairActionsInOrder() {
        PaymentInstructionEntity instruction = seedInstructionAtException("INVALID-IBAN");
        publishExceptionEvent(
                instruction.getInstructionId(), instruction.getUetr(), instruction.getEndToEndId(), 2, FailureStage.VALIDATION,
                new InstructionExceptionEvent.Detail("AC01", Repairability.REPAIRABLE, "creditorAccount", "invalid IBAN checksum"));
        awaitCondition(Duration.ofSeconds(5), () -> !cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).isEmpty());
        ExceptionCaseEntity exceptionCase = cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).get(0);

        ResponseEntity<RepairActionResponse[]> proposeResponse = asUser("maker1").postForEntity(
                url("/v1/cases/" + exceptionCase.getCaseId() + "/repairs"),
                List.of(new FieldChange("creditorAccount", "FR7630006000011234567890189")), RepairActionResponse[].class);
        RepairActionResponse proposed = proposeResponse.getBody()[0];
        asUser("checker1").postForEntity(url("/v1/repairs/" + proposed.actionId() + "/approve"), null, RepairActionResponse.class);

        ResponseEntity<TimelineEntry[]> timelineResponse =
                asUser("viewer").getForEntity(url("/v1/instructions/" + instruction.getInstructionId() + "/timeline"), TimelineEntry[].class);

        assertThat(timelineResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<TimelineEntry> entries = List.of(timelineResponse.getBody());
        assertThat(entries).extracting(TimelineEntry::type)
                .contains("STATE_TRANSITION", "REPAIR_PROPOSED", "REPAIR_APPROVED");
        // Chronological: the repair proposal and approval land strictly
        // after the EXCEPTION transition that opened the case.
        int exceptionIndex = indexOfTransitionTo(entries, "EXCEPTION");
        int proposedIndex = indexOfType(entries, "REPAIR_PROPOSED");
        int approvedIndex = indexOfType(entries, "REPAIR_APPROVED");
        assertThat(exceptionIndex).isLessThan(proposedIndex);
        assertThat(proposedIndex).isLessThanOrEqualTo(approvedIndex);
        assertThat(entries.stream().filter(e -> "REPAIR_PROPOSED".equals(e.type())).findFirst().orElseThrow().newValue())
                .isEqualTo("FR7630006000011234567890189");
    }

    @Test
    void lookupByUetrAndByEndToEndId() {
        PaymentInstructionEntity instruction = seedInstructionAtException("INVALID-IBAN");

        ResponseEntity<InstructionSummaryResponse[]> byUetr = asUser("viewer")
                .getForEntity(url("/v1/instructions?uetr=" + instruction.getUetr()), InstructionSummaryResponse[].class);
        assertThat(byUetr.getBody()).hasSize(1);
        assertThat(byUetr.getBody()[0].instructionId()).isEqualTo(instruction.getInstructionId());

        ResponseEntity<InstructionSummaryResponse[]> byEndToEndId = asUser("viewer")
                .getForEntity(url("/v1/instructions?endToEndId=" + instruction.getEndToEndId()), InstructionSummaryResponse[].class);
        assertThat(byEndToEndId.getBody()).hasSize(1);
        assertThat(byEndToEndId.getBody()[0].uetr()).isEqualTo(instruction.getUetr());
    }

    @Test
    void casesListIsFilterableByStatus() {
        PaymentInstructionEntity instruction = seedInstructionAtException("INVALID-IBAN");
        publishExceptionEvent(
                instruction.getInstructionId(), instruction.getUetr(), instruction.getEndToEndId(), 2, FailureStage.VALIDATION,
                new InstructionExceptionEvent.Detail("AC01", Repairability.REPAIRABLE, "creditorAccount", "invalid IBAN checksum"));
        awaitCondition(Duration.ofSeconds(5), () -> !cases.findByInstructionIdOrderByOpenedAtDesc(instruction.getInstructionId()).isEmpty());

        RequestEntity<Void> request = RequestEntity.method(HttpMethod.GET, java.net.URI.create(url("/v1/cases?status=OPEN&size=50"))).build();
        ResponseEntity<String> response = asUser("viewer").exchange(request, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(instruction.getInstructionId().toString());
    }

    private static int indexOfType(List<TimelineEntry> entries, String type) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).type().equals(type)) {
                return i;
            }
        }
        throw new AssertionError("No timeline entry of type " + type);
    }

    private static int indexOfTransitionTo(List<TimelineEntry> entries, String toState) {
        for (int i = 0; i < entries.size(); i++) {
            if ("STATE_TRANSITION".equals(entries.get(i).type()) && toState.equals(entries.get(i).toState())) {
                return i;
            }
        }
        throw new AssertionError("No transition to " + toState);
    }
}
