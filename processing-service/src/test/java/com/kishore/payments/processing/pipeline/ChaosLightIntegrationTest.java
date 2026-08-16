package com.kishore.payments.processing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.processing.AbstractProcessingIntegrationTest;
import com.kishore.payments.processing.enrichment.ScreeningProvider;
import com.kishore.payments.processing.enrichment.ScreeningResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Simulates a consumer dying mid-transaction: a collaborator inside the
 * enrichment transaction throws an unexpected (non-business) exception on
 * the first attempt for one instruction, exactly like a process that was
 * killed before its transaction committed would leave nothing behind --
 * the whole transaction rolls back, the message is never acknowledged
 * (rollback happens before {@code ack.acknowledge()} is reached), and the
 * container's retry redelivers the same record. "Restart" here is that
 * redelivery, not a literal process kill -- the observable guarantee under
 * test (no torn transaction, no stranded instruction, no duplicate event)
 * is the same either way, since it depends only on the transaction boundary
 * and the idempotency check, neither of which know or care whether the
 * previous attempt died from a real crash or a thrown exception.
 */
@Import(ChaosLightIntegrationTest.FlakyScreeningConfig.class)
class ChaosLightIntegrationTest extends AbstractProcessingIntegrationTest {

    @Test
    void aTransactionThatDiesMidwayLeavesNoStrandedInstructionAndRetryProducesNoDuplicateEvents() {
        PaymentInstructionEntity instruction = seedReceivedEurInstruction(new BigDecimal("500.00"), LocalDate.now(clock));
        FlakyScreeningProvider.crashOnceFor(instruction.getInstructionId());

        outboxPublisher.publishBatch();

        InstructionState finalState = awaitState(instruction.getInstructionId(), Duration.ofSeconds(45));
        assertThat(finalState).as("the instruction must still reach ROUTED after the retry recovers").isEqualTo(InstructionState.ROUTED);

        // Not stranded between states: exactly one instruction_event per
        // transition (seed RECEIVED + VALIDATED + ENRICHED + ROUTED), none
        // duplicated by the crashed-and-retried enrichment attempt.
        Integer eventCount = jdbc.queryForObject(
                "SELECT count(*) FROM core.instruction_event WHERE instruction_id = ?", Integer.class, instruction.getInstructionId());
        assertThat(eventCount).isEqualTo(4);

        Integer enrichedEventCount = jdbc.queryForObject(
                "SELECT count(*) FROM core.instruction_event WHERE instruction_id = ? AND to_state = 'ENRICHED'::core.instruction_state",
                Integer.class,
                instruction.getInstructionId());
        assertThat(enrichedEventCount).as("the rolled-back attempt must not have left a partial ENRICHED event").isEqualTo(1);

        Integer enrichedOutboxRows = jdbc.queryForObject(
                "SELECT count(*) FROM core.outbox WHERE aggregate_id = ? AND topic = 'payments.enriched'",
                Integer.class,
                instruction.getInstructionId());
        assertThat(enrichedOutboxRows).as("no duplicate payments.enriched outbox row from the crashed attempt").isEqualTo(1);
    }

    @TestConfiguration
    static class FlakyScreeningConfig {

        @Bean
        @Primary
        FlakyScreeningProvider flakyScreeningProvider() {
            return new FlakyScreeningProvider();
        }
    }

    static class FlakyScreeningProvider implements ScreeningProvider {

        private static final Set<UUID> shouldCrashOnce = ConcurrentHashMap.newKeySet();
        private static final Set<UUID> alreadyCrashed = ConcurrentHashMap.newKeySet();

        static void crashOnceFor(UUID instructionId) {
            shouldCrashOnce.add(instructionId);
        }

        @Override
        public ScreeningResult screen(PaymentInstructionEntity instruction) {
            UUID id = instruction.getInstructionId();
            if (shouldCrashOnce.contains(id) && alreadyCrashed.add(id)) {
                throw new IllegalStateException("simulated crash mid-transaction for " + id);
            }
            return ScreeningResult.CLEAR;
        }
    }
}
