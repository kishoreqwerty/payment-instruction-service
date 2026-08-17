package com.kishore.payments.gateway.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.gateway.dispatch.DispatchRecordEntity;
import com.kishore.payments.gateway.dispatch.DispatchState;
import com.kishore.payments.gateway.dispatch.RailDispatcher;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * §4(c) of the Phase 7 brief: a {@code PENDING} dispatch_record with no
 * resolution means the process died between committing that row and making
 * the network call -- the exact scenario {@code
 * DispatchDurabilityIntegrationTest} proves survives a restart. This test
 * proves the other half: {@link AmbiguityResolver} is what eventually
 * notices that stuck row and moves it to {@code INVESTIGATION}, since there
 * is no rail to query for an attempt that was never actually made.
 */
class StuckPendingDispatchInvestigationTest extends AbstractAmbiguityResolverIntegrationTest {

    @MockBean
    private RailDispatcher railDispatcher;

    @Test
    void pendingDispatchRecordOlderThanThresholdMovesRoutedInstructionToInvestigation() {
        when(railDispatcher.dispatch(any(), any())).thenThrow(new RuntimeException("simulated process death mid-call"));

        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("900.00"), "USD", "FEDWIRE");
        outboxPublisher.publishBatch();

        awaitCondition(Duration.ofSeconds(10), () -> !dispatchRecordsFor(instruction.getUetr()).isEmpty());
        List<DispatchRecordEntity> before = dispatchRecordsFor(instruction.getUetr());
        assertThat(before).hasSize(1);
        assertThat(before.get(0).getDispatchState()).isEqualTo(DispatchState.PENDING);
        assertThat(instructions.findById(instruction.getInstructionId()).orElseThrow().getState()).isEqualTo(InstructionState.ROUTED);

        // Deterministic regardless of the configured pending-threshold value.
        backdateDispatchRecordSentAt(instruction.getUetr(), Duration.ofHours(1));

        ambiguityResolver.reconcile();

        assertThat(instructions.findById(instruction.getInstructionId()).orElseThrow().getState()).isEqualTo(InstructionState.INVESTIGATION);
        // Still exactly the one stuck record -- reaching INVESTIGATION this
        // way must never itself attempt a dispatch.
        assertThat(dispatchRecordsFor(instruction.getUetr())).hasSize(1);
    }
}
