package com.kishore.payments.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.instruction.InstructionEventEntity;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.state.InstructionState;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * pacs.004 handling, driven end-to-end through the real embedded
 * rail-simulator. This originally couldn't work: rail-simulator had one
 * {@code callbackUrl} per scenario for every message type, so the pacs.004
 * {@code RETURN_AFTER_SETTLEMENT} sends landed on this gateway's
 * {@code /status} endpoint (wrong schema, rejected, silently dropped by the
 * simulator's own non-retrying callback sender) alongside the ACSC that
 * belonged there. rail-simulator now has separate {@code statusCallbackUrl}
 * and {@code returnCallbackUrl} scenario fields (see
 * .notes/reports/PHASE-5-REPORT.md), which is what makes proving the full
 * transition possible here rather than proving its two halves separately.
 */
class ReturnIntegrationTest extends AbstractGatewayIntegrationTest {

    @Test
    void realSimulatorReturnAfterSettlementReachesSentSettledThenReturned() {
        loadRailScenario("FEDWIRE", """
                rail: settle-then-return
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: RETURN_AFTER_SETTLEMENT
                  confirmationDelayMs: 50
                  returnDelayMs: 100
                  returnReasonCode: AM04
                statusCallbackUrl: "%s"
                returnCallbackUrl: "%s"
                rules: []
                """.formatted(callbackStatusUrl("FEDWIRE"), callbackReturnUrl("FEDWIRE")));

        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("620.00"), "USD", "FEDWIRE");
        outboxPublisher.publishBatch();

        InstructionState settled = awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.SETTLED);
        assertThat(settled).isEqualTo(InstructionState.SETTLED);

        InstructionState returned = awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.RETURNED);
        assertThat(returned).isEqualTo(InstructionState.RETURNED);

        List<InstructionState> toStates = events.findAll().stream()
                .filter(e -> e.getInstructionId().equals(instruction.getInstructionId()))
                .sorted((a, b) -> Integer.compare(a.getSequenceNo(), b.getSequenceNo()))
                .map(InstructionEventEntity::getToState)
                .toList();
        assertThat(toStates).endsWith(InstructionState.SENT, InstructionState.SETTLED, InstructionState.RETURNED);
    }
}
