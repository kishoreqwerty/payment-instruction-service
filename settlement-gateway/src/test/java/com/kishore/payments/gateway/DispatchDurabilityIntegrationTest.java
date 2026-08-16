package com.kishore.payments.gateway;

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
 * The phase's central claim: the dispatch_record row is written and
 * committed before the network call, not after and not in the same
 * transaction. {@link RailDispatcher} is replaced with a mock that throws
 * the instant it would make that call -- standing in for the process dying
 * at that exact point. If {@code DispatchOrchestrator} ever changed to
 * write the PENDING row in the same transaction as everything that follows
 * the call, or after the call instead of before, this test would fail: the
 * thrown exception would roll back that transaction and the row would
 * never be found.
 */
class DispatchDurabilityIntegrationTest extends AbstractGatewayIntegrationTest {

    @MockBean
    private RailDispatcher railDispatcher;

    @Test
    void dispatchRecordSurvivesAsPendingAndInstructionStaysRoutedWhenTheCallItselfNeverCompletes() {
        when(railDispatcher.dispatch(any(), any())).thenThrow(new RuntimeException("simulated process death mid-call"));

        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("1500.00"), "USD", "FEDWIRE");
        outboxPublisher.publishBatch();

        awaitCondition(Duration.ofSeconds(10), () -> !dispatchRecords.findByUetrOrderByAttemptNo(instruction.getUetr()).isEmpty());

        List<DispatchRecordEntity> records = dispatchRecords.findByUetrOrderByAttemptNo(instruction.getUetr());
        assertThat(records).isNotEmpty();
        assertThat(records.get(0).getDispatchState()).isEqualTo(DispatchState.PENDING);
        assertThat(records.get(0).getRequestPayload()).isNotEmpty();

        // The instruction itself was never touched: the transition step never
        // ran, because the exception was thrown before DispatchOrchestrator
        // ever got to it.
        assertThat(instructions.findById(instruction.getInstructionId()).orElseThrow().getState()).isEqualTo(InstructionState.ROUTED);
    }
}
