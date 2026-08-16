package com.kishore.payments.gateway.dispatch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kishore.payments.core.domain.ActorType;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import com.kishore.payments.core.outbox.OutboxWriter;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.core.state.InstructionStateWriter;
import com.kishore.payments.core.state.TransitionResult;
import com.kishore.payments.gateway.GatewayMetrics;
import com.kishore.payments.gateway.GatewayProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * The retry/no-retry decision per {@link DispatchOutcome} class, in
 * isolation from any real database or HTTP call: RailDispatcher and every
 * repository are mocked, so this asserts purely on how many times each is
 * invoked for a given sequence of outcomes.
 */
class DispatchOrchestratorRetryTest {

    private PaymentInstructionRepository instructions;
    private DispatchRecordRepository dispatchRecords;
    private Pacs008Assembler assembler;
    private RailDispatcher railDispatcher;
    private InstructionStateWriter stateWriter;
    private OutboxWriter outboxWriter;
    private GatewayMetrics metrics;
    private DispatchOrchestrator orchestrator;
    private PaymentInstructionEntity instruction;

    @BeforeEach
    void setUp() {
        instructions = mock(PaymentInstructionRepository.class);
        dispatchRecords = mock(DispatchRecordRepository.class);
        assembler = mock(Pacs008Assembler.class);
        railDispatcher = mock(RailDispatcher.class);
        stateWriter = mock(InstructionStateWriter.class);
        outboxWriter = mock(OutboxWriter.class);
        metrics = mock(GatewayMetrics.class);

        instruction = routedInstruction();
        when(instructions.findById(instruction.getInstructionId())).thenReturn(Optional.of(instruction));
        when(assembler.assemble(instruction)).thenReturn("<pacs008/>".getBytes());
        when(dispatchRecords.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(dispatchRecords.maxAttemptNo(any())).thenReturn(0);
        when(stateWriter.transition(any(), any(), any(), any(), any(), any())).thenReturn(new TransitionResult(InstructionState.SENT, 5));

        GatewayProperties properties = new GatewayProperties(
                Map.of("FEDWIRE", "http://localhost:1"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10),
                new GatewayProperties.DispatchRetry(3, Duration.ofMillis(1), 2.0));

        PlatformTransactionManager transactionManager = noOpTransactionManager();
        Clock clock = Clock.fixed(java.time.Instant.now(), ZoneOffset.UTC);

        orchestrator = new DispatchOrchestrator(
                instructions, dispatchRecords, assembler, railDispatcher, stateWriter, outboxWriter, metrics, properties, clock, transactionManager);
    }

    @Test
    void acknowledgedDispatchesExactlyOnceAndTransitionsToSent() {
        when(railDispatcher.dispatch(any(), any())).thenReturn(new DispatchOutcome.Acknowledged(202));

        orchestrator.dispatch(instruction.getInstructionId());

        verify(railDispatcher, times(1)).dispatch(any(), any());
        verify(stateWriter, times(1)).transition(eq(instruction.getInstructionId()), eq(InstructionState.SENT), eq(ActorType.SYSTEM), any(), any(), any());
        verify(outboxWriter, times(1)).write(any());
    }

    @Test
    void ambiguousDispatchesExactlyOnceAndNeverRetries() {
        when(railDispatcher.dispatch(any(), any())).thenReturn(new DispatchOutcome.Ambiguous("read timed out"));

        orchestrator.dispatch(instruction.getInstructionId());

        verify(railDispatcher, times(1)).dispatch(any(), any());
        verify(stateWriter, times(1))
                .transition(eq(instruction.getInstructionId()), eq(InstructionState.SENT_UNCONFIRMED), eq(ActorType.SYSTEM), any(), any(), any());
        verify(metrics, times(1)).recordDispatchAmbiguous("FEDWIRE");
    }

    @Test
    void rejectedDispatchesExactlyOnceAndGoesStraightToException() {
        when(railDispatcher.dispatch(any(), any())).thenReturn(new DispatchOutcome.Rejected(400, "malformed"));

        orchestrator.dispatch(instruction.getInstructionId());

        verify(railDispatcher, times(1)).dispatch(any(), any());
        verify(stateWriter, times(1)).transition(eq(instruction.getInstructionId()), eq(InstructionState.EXCEPTION), eq(ActorType.SYSTEM), any(), any(), any());
    }

    @Test
    void serverErrorRetriesUpToMaxAttemptsThenGoesToException() {
        when(railDispatcher.dispatch(any(), any())).thenReturn(new DispatchOutcome.ServerError(500, "boom"));

        orchestrator.dispatch(instruction.getInstructionId());

        // maxAttempts is 3 in this test's GatewayProperties.
        verify(railDispatcher, times(3)).dispatch(any(), any());
        verify(stateWriter, times(1)).transition(eq(instruction.getInstructionId()), eq(InstructionState.EXCEPTION), eq(ActorType.SYSTEM), any(), any(), any());
    }

    @Test
    void serverErrorThatRecoversStopsRetryingAndDoesNotReachException() {
        when(railDispatcher.dispatch(any(), any()))
                .thenReturn(new DispatchOutcome.ServerError(500, "boom"))
                .thenReturn(new DispatchOutcome.Acknowledged(202));

        orchestrator.dispatch(instruction.getInstructionId());

        verify(railDispatcher, times(2)).dispatch(any(), any());
        verify(stateWriter, times(1)).transition(eq(instruction.getInstructionId()), eq(InstructionState.SENT), eq(ActorType.SYSTEM), any(), any(), any());
        verify(stateWriter, never()).transition(any(), eq(InstructionState.EXCEPTION), any(), any(), any(), any());
    }

    private static PaymentInstructionEntity routedInstruction() {
        PaymentInstructionEntity instruction = new PaymentInstructionEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "E2E-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                "Alice",
                "DE89370400440532013000",
                "CHASUS33XXX",
                "Bob",
                "ACCT-1",
                "DEUTDEFFXXX",
                new BigDecimal("100.00"),
                "USD",
                "SHAR",
                LocalDate.now());
        instruction.setSelectedRail("FEDWIRE");
        return instruction;
    }

    private static PlatformTransactionManager noOpTransactionManager() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status);
        return transactionManager;
    }
}
