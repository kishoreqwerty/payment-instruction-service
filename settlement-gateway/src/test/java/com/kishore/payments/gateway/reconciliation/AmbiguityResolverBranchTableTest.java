package com.kishore.payments.gateway.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.kishore.payments.gateway.dispatch.DispatchRecordRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * The branch table from .notes/ARCHITECTURE.md §6.4 / the Phase 7 brief,
 * exercised directly against {@link AmbiguityResolver#applyKnown},
 * {@link AmbiguityResolver#applyUnknown} and {@link
 * AmbiguityResolver#applyInconclusive} -- every collaborator mocked except
 * {@link ReconciliationStateEntity} itself, which is real, so its counters'
 * actual reset/accumulate behaviour is what's under test, not a mock's
 * memory of what it was told. No database, no JDBC claiming: that half
 * (cross-replica locking, candidate selection) is covered by the real-
 * simulator integration tests instead.
 */
class AmbiguityResolverBranchTableTest {

    private PaymentInstructionRepository instructions;
    private ReconciliationStateRepository reconciliationStates;
    private DispatchRecordRepository dispatchRecords;
    private InstructionStateWriter stateWriter;
    private OutboxWriter outboxWriter;
    private GatewayMetrics metrics;
    private GatewayProperties properties;
    private AmbiguityResolver resolver;
    private PaymentInstructionEntity instruction;

    @BeforeEach
    void setUp() {
        instructions = mock(PaymentInstructionRepository.class);
        reconciliationStates = mock(ReconciliationStateRepository.class);
        dispatchRecords = mock(DispatchRecordRepository.class);
        RailStatusClient railStatusClient = mock(RailStatusClient.class);
        stateWriter = mock(InstructionStateWriter.class);
        outboxWriter = mock(OutboxWriter.class);
        metrics = mock(GatewayMetrics.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        instruction = sentUnconfirmedInstruction();
        when(instructions.findById(instruction.getInstructionId())).thenReturn(Optional.of(instruction));
        when(stateWriter.transition(any(), any(), any(), any(), any(), any())).thenReturn(new TransitionResult(InstructionState.SENT, 6));
        when(dispatchRecords.maxAttemptNo(any())).thenReturn(1);

        properties = new GatewayProperties(
                java.util.Map.of("FEDWIRE", "http://localhost:1"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10),
                new GatewayProperties.DispatchRetry(3, Duration.ofMillis(1), 2.0),
                new GatewayProperties.Reconciliation(Duration.ofMinutes(2), Duration.ofSeconds(30), 50, 2, 10, 3, Duration.ofMinutes(5)));

        Clock clock = Clock.fixed(java.time.Instant.now(), ZoneOffset.UTC);
        PlatformTransactionManager transactionManager = noOpTransactionManager();

        resolver = new AmbiguityResolver(
                jdbc, instructions, reconciliationStates, dispatchRecords, railStatusClient,
                stateWriter, outboxWriter, metrics, properties, clock, transactionManager);
    }

    @Test
    void knownNullRailStatusTransitionsToSentOnly() {
        ReconciliationStateEntity recon = freshRecon();

        resolver.applyKnown(instruction, recon, new RailStatusOutcome.Known(null, null));

        verify(stateWriter, times(1)).transition(eq(instruction.getInstructionId()), eq(InstructionState.SENT), eq(ActorType.SYSTEM), any(), any(), any());
        verify(stateWriter, never()).transition(any(), eq(InstructionState.SETTLED), any(), any(), any(), any());
        verify(stateWriter, never()).transition(any(), eq(InstructionState.EXCEPTION), any(), any(), any(), any());
        verify(outboxWriter, never()).write(any());
        verify(metrics).recordAmbiguityResolution("FEDWIRE", "sent");
    }

    @Test
    void knownAcspTransitionsToSentOnly() {
        ReconciliationStateEntity recon = freshRecon();

        resolver.applyKnown(instruction, recon, new RailStatusOutcome.Known("ACSP", null));

        verify(stateWriter, times(1)).transition(eq(instruction.getInstructionId()), eq(InstructionState.SENT), eq(ActorType.SYSTEM), any(), any(), any());
        verify(stateWriter, never()).transition(any(), eq(InstructionState.SETTLED), any(), any(), any(), any());
        verify(outboxWriter, never()).write(any());
        verify(metrics).recordAmbiguityResolution("FEDWIRE", "sent");
    }

    @Test
    void knownAcscTransitionsToSentThenSettledAndWritesOneEvent() {
        ReconciliationStateEntity recon = freshRecon();
        when(instructions.findById(instruction.getInstructionId())).thenReturn(Optional.of(instruction), Optional.of(instruction));

        resolver.applyKnown(instruction, recon, new RailStatusOutcome.Known("ACSC", null));

        verify(stateWriter, times(1)).transition(eq(instruction.getInstructionId()), eq(InstructionState.SENT), eq(ActorType.SYSTEM), any(), any(), any());
        verify(stateWriter, times(1)).transition(eq(instruction.getInstructionId()), eq(InstructionState.SETTLED), eq(ActorType.SYSTEM), any(), any(), any());
        verify(outboxWriter, times(1)).write(any());
        verify(metrics).recordAmbiguityResolution("FEDWIRE", "settled");
    }

    @Test
    void knownRjctTransitionsToSentThenExceptionCarryingReasonCode() {
        ReconciliationStateEntity recon = freshRecon();
        when(instructions.findById(instruction.getInstructionId())).thenReturn(Optional.of(instruction), Optional.of(instruction));

        resolver.applyKnown(instruction, recon, new RailStatusOutcome.Known("RJCT", "AC04"));

        verify(stateWriter, times(1)).transition(eq(instruction.getInstructionId()), eq(InstructionState.SENT), eq(ActorType.SYSTEM), any(), any(), any());
        verify(stateWriter, times(1))
                .transition(eq(instruction.getInstructionId()), eq(InstructionState.EXCEPTION), eq(ActorType.SYSTEM), any(), eq("AC04"), any());
        verify(outboxWriter, times(1)).write(any());
        verify(metrics).recordAmbiguityResolution("FEDWIRE", "rejected");
    }

    @Test
    void firstUnknownObservationDoesNotRedispatch() {
        ReconciliationStateEntity recon = freshRecon();

        resolver.applyUnknown(instruction, recon);

        assertThat(recon.getConsecutiveUnknownCount()).isEqualTo(1);
        verify(stateWriter, never()).transition(any(), any(), any(), any(), any(), any());
        verify(metrics, never()).recordAmbiguityResolution(any(), any());
    }

    @Test
    void secondConsecutiveUnknownObservationRedispatchesWithSameUetr() {
        ReconciliationStateEntity recon = freshRecon();

        resolver.applyUnknown(instruction, recon);
        resolver.applyUnknown(instruction, recon);

        assertThat(recon.getConsecutiveUnknownCount()).isZero();
        assertThat(recon.getRedispatchCount()).isEqualTo(1);
        verify(stateWriter, times(1)).transition(eq(instruction.getInstructionId()), eq(InstructionState.ROUTED), eq(ActorType.SYSTEM), any(), any(), any());
        verify(outboxWriter, times(1)).write(any());
        verify(metrics).recordAmbiguityResolution("FEDWIRE", "redispatched");
        verify(metrics).recordRedispatch("FEDWIRE", 2);
    }

    /** This is the test that fails under a single-observation implementation and is the reason the two-consecutive rule exists. */
    @Test
    void singleUnknownNeverRedispatches() {
        ReconciliationStateEntity recon = freshRecon();

        resolver.applyUnknown(instruction, recon);

        verify(stateWriter, never()).transition(any(), eq(InstructionState.ROUTED), any(), any(), any(), any());
        verify(metrics, never()).recordRedispatch(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void nonConsecutiveUnknownResetsTheCount() {
        ReconciliationStateEntity recon = freshRecon();

        resolver.applyUnknown(instruction, recon);
        resolver.applyInconclusive(instruction, recon, new RailStatusOutcome.QueryFailed("connection reset"));
        resolver.applyUnknown(instruction, recon);

        // UNKNOWN, then inconclusive, then UNKNOWN is not two consecutive
        // observations -- the inconclusive in between must have reset the
        // streak back to 1, not left it at 2.
        assertThat(recon.getConsecutiveUnknownCount()).isEqualTo(1);
        verify(stateWriter, never()).transition(any(), eq(InstructionState.ROUTED), any(), any(), any(), any());

        resolver.applyUnknown(instruction, recon);

        assertThat(recon.getConsecutiveUnknownCount()).isZero();
        verify(stateWriter, times(1)).transition(eq(instruction.getInstructionId()), eq(InstructionState.ROUTED), eq(ActorType.SYSTEM), any(), any(), any());
    }

    @Test
    void redispatchCapReachedGoesToInvestigationInsteadOfRedispatchingAgain() {
        ReconciliationStateEntity recon = freshRecon();
        // Exhaust the cap (3, from setUp's properties) via three separate
        // two-consecutive-UNKNOWN episodes.
        for (int i = 0; i < 3; i++) {
            resolver.applyUnknown(instruction, recon);
            resolver.applyUnknown(instruction, recon);
        }
        assertThat(recon.getRedispatchCount()).isEqualTo(3);
        verify(stateWriter, times(3)).transition(eq(instruction.getInstructionId()), eq(InstructionState.ROUTED), eq(ActorType.SYSTEM), any(), any(), any());

        // A fourth episode's second consecutive UNKNOWN must not redispatch again.
        resolver.applyUnknown(instruction, recon);
        resolver.applyUnknown(instruction, recon);

        verify(stateWriter, times(3)).transition(any(), eq(InstructionState.ROUTED), any(), any(), any(), any());
        verify(stateWriter, times(1)).transition(eq(instruction.getInstructionId()), eq(InstructionState.INVESTIGATION), eq(ActorType.SYSTEM), any(), any(), any());
        verify(metrics).recordAmbiguityResolution("FEDWIRE", "investigation");
    }

    @Test
    void inconclusiveDoesNotActBeforeWindowExhausted() {
        ReconciliationStateEntity recon = freshRecon();

        for (int i = 0; i < 9; i++) {
            resolver.applyInconclusive(instruction, recon, new RailStatusOutcome.QueryFailed("timeout"));
        }

        assertThat(recon.getConsecutiveInconclusiveCount()).isEqualTo(9);
        verify(stateWriter, never()).transition(any(), eq(InstructionState.INVESTIGATION), any(), any(), any(), any());
    }

    @Test
    void inconclusiveWindowExhaustedGoesToInvestigation() {
        ReconciliationStateEntity recon = freshRecon();

        for (int i = 0; i < 10; i++) {
            resolver.applyInconclusive(instruction, recon, new RailStatusOutcome.QueryFailed("timeout"));
        }

        verify(stateWriter, times(1))
                .transition(eq(instruction.getInstructionId()), eq(InstructionState.INVESTIGATION), eq(ActorType.SYSTEM), any(), any(), any());
        verify(outboxWriter, times(1)).write(any());
        verify(metrics).recordAmbiguityResolution("FEDWIRE", "investigation");
    }

    private ReconciliationStateEntity freshRecon() {
        return new ReconciliationStateEntity(instruction.getInstructionId(), OffsetDateTime.now());
    }

    private static PaymentInstructionEntity sentUnconfirmedInstruction() {
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
        instruction.setState(InstructionState.SENT_UNCONFIRMED);
        return instruction;
    }

    private static PlatformTransactionManager noOpTransactionManager() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status);
        return transactionManager;
    }
}
