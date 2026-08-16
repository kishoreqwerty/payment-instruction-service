package com.kishore.payments.processing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.instruction.InstructionEventEntity;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.processing.AbstractProcessingIntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The golden path: a RECEIVED instruction traverses every stage to ROUTED,
 * accumulating exactly one instruction_event per hop (the seed RECEIVED
 * event plus one per stage), in sequence order.
 */
class FullPipelineIntegrationTest extends AbstractProcessingIntegrationTest {

    @Test
    void receivedInstructionTraversesToRoutedWithFourAuditEventsInSequence() {
        PaymentInstructionEntity seeded = seedReceivedEurInstruction(new BigDecimal("500.00"), LocalDate.now(clock));

        outboxPublisher.publishBatch();
        InstructionState finalState = awaitState(seeded.getInstructionId(), Duration.ofSeconds(30));

        assertThat(finalState).isEqualTo(InstructionState.ROUTED);

        PaymentInstructionEntity reloaded =
                instructions.findById(seeded.getInstructionId()).orElseThrow();
        assertThat(reloaded.getSelectedRail()).isEqualTo("SEPA");
        assertThat(reloaded.getCorrespondentBic()).isEqualTo("DEUTDEFFXXX");
        assertThat(reloaded.getNostroAccount()).isNotBlank();
        assertThat(reloaded.getChargeBearer()).isEqualTo("SLEV");
        assertThat(reloaded.getRefdataVersion()).isEqualTo(1L);

        List<InstructionEventEntity> instructionEvents = eventsFor(seeded.getInstructionId());
        assertThat(instructionEvents).hasSize(4);
        assertThat(instructionEvents.stream().map(InstructionEventEntity::getSequenceNo).toList()).containsExactly(1, 2, 3, 4);
        assertThat(instructionEvents.stream().map(InstructionEventEntity::getToState).toList())
                .containsExactly(
                        InstructionState.RECEIVED, InstructionState.VALIDATED, InstructionState.ENRICHED, InstructionState.ROUTED);
    }

    private List<InstructionEventEntity> eventsFor(java.util.UUID instructionId) {
        return events.findAll().stream()
                .filter(e -> e.getInstructionId().equals(instructionId))
                .sorted(Comparator.comparingInt(InstructionEventEntity::getSequenceNo))
                .toList();
    }
}
