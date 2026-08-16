package com.kishore.payments.processing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.processing.AbstractProcessingIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A redelivered payments.received event for an already-validated
 * instruction must be discarded: the suppression counter increments, and no
 * second VALIDATED instruction_event is written -- the (instruction_id,
 * sequence_no) uniqueness constraint would reject a naive double-write
 * anyway, but the idempotency check means it never gets that far.
 */
class RedeliveryIdempotencyIntegrationTest extends AbstractProcessingIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void redeliveredReceivedEventIsDiscardedWithSuppressionCounterIncrementedAndNoDuplicateEvent() {
        PaymentInstructionEntity instruction = seedReceivedEurInstruction(new BigDecimal("500.00"), LocalDate.now(clock));

        outboxPublisher.publishBatch();
        awaitState(instruction.getInstructionId(), Duration.ofSeconds(30));
        assertThat(instructions.findById(instruction.getInstructionId()).orElseThrow().getState())
                .isNotEqualTo(InstructionState.RECEIVED);

        int eventCountBeforeRedelivery = eventCountFor(instruction.getInstructionId());
        double suppressedBefore = suppressedCount();

        // Simulate redelivery: the same payments.received payload reaches
        // the consumer a second time (a real producer retry, or a consumer
        // crash between commit and ack, both already proven to duplicate
        // rather than lose -- see core's OutboxPublisherTest).
        reinsertReceivedOutboxRow(instruction);
        outboxPublisher.publishBatch();

        awaitCondition(Duration.ofSeconds(15), () -> suppressedCount() > suppressedBefore);

        assertThat(suppressedCount()).as("payment_duplicates_suppressed_total{stage=VALIDATION} must increment").isGreaterThan(suppressedBefore);
        assertThat(eventCountFor(instruction.getInstructionId()))
                .as("no second instruction_event should have been written for the redelivered message")
                .isEqualTo(eventCountBeforeRedelivery);
    }

    private void reinsertReceivedOutboxRow(PaymentInstructionEntity instruction) {
        jdbc.update(
                "INSERT INTO core.outbox (aggregate_id, topic, partition_key, headers, payload) "
                        + "SELECT aggregate_id, topic, partition_key, headers, payload FROM core.outbox "
                        + "WHERE aggregate_id = ? AND topic = 'payments.received' ORDER BY outbox_id LIMIT 1",
                instruction.getInstructionId());
    }

    private int eventCountFor(java.util.UUID instructionId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM core.instruction_event WHERE instruction_id = ?", Integer.class, instructionId);
        return count == null ? 0 : count;
    }

    private double suppressedCount() {
        var counter = meterRegistry.find("payment_duplicates_suppressed_total").tag("stage", "VALIDATION").counter();
        return counter == null ? 0.0 : counter.count();
    }
}
