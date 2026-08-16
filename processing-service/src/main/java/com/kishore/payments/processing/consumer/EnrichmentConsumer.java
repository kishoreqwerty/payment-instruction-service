package com.kishore.payments.processing.consumer;

import com.kishore.payments.core.domain.ActorType;
import com.kishore.payments.core.event.EventJson;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import com.kishore.payments.core.outbox.OutboxHeaders;
import com.kishore.payments.core.outbox.OutboxMessage;
import com.kishore.payments.core.outbox.OutboxWriter;
import com.kishore.payments.core.state.ConcurrentTransitionException;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.core.state.InstructionStateWriter;
import com.kishore.payments.core.state.TransitionResult;
import com.kishore.payments.processing.enrichment.EnrichmentChain;
import com.kishore.payments.processing.event.InstructionExceptionEvent;
import com.kishore.payments.processing.event.InstructionStageEvent;
import com.kishore.payments.processing.failure.BusinessFailureException;
import java.time.OffsetDateTime;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stage 2: VALIDATED -> ENRICHED (or -> EXCEPTION). Consumes
 * payments.validated, produced by {@link ValidationConsumer} -- or, once
 * Phase 8 exists, by a repair re-entry (EXCEPTION -> REPAIRED -> VALIDATED)
 * publishing the same event shape. Idempotency compares {@code sequence_no},
 * not state: see {@link ValidationConsumer}'s class comment for why state
 * alone can't tell a repair's legitimate revisit of VALIDATED apart from a
 * stale redelivery of the original one.
 */
@Component
public class EnrichmentConsumer {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentConsumer.class);
    private static final String ENRICHED_TOPIC = "payments.enriched";
    private static final String EXCEPTIONS_TOPIC = "payments.exceptions";
    private static final String ACTOR_ID = "processing-service:enrichment";

    private final PaymentInstructionRepository instructions;
    private final EnrichmentChain enrichmentChain;
    private final InstructionStateWriter stateWriter;
    private final OutboxWriter outboxWriter;
    private final ProcessingMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    public EnrichmentConsumer(
            PaymentInstructionRepository instructions,
            EnrichmentChain enrichmentChain,
            InstructionStateWriter stateWriter,
            OutboxWriter outboxWriter,
            ProcessingMetrics metrics,
            PlatformTransactionManager transactionManager) {
        this.instructions = instructions;
        this.enrichmentChain = enrichmentChain;
        this.stateWriter = stateWriter;
        this.outboxWriter = outboxWriter;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @KafkaListener(
            topics = "payments.validated",
            groupId = "${payments.kafka.consumer-group}",
            concurrency = "${payments.kafka.validated-partitions:12}")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        InstructionStageEvent event = readEvent(record.value());
        transactionTemplate.executeWithoutResult(status -> process(event.instructionId(), event.sequenceNo()));
        ack.acknowledge();
    }

    private void process(UUID instructionId, int eventSequenceNo) {
        PaymentInstructionEntity instruction = instructions
                .findById(instructionId)
                .orElseThrow(() -> new NoSuchElementException("No payment instruction: " + instructionId));

        // See ValidationConsumer.process for why this compares sequence_no
        // (via state_version, its equivalent) rather than state, and why
        // "already handled" is strictly < rather than <=.
        if (eventSequenceNo < instruction.getStateVersion()) {
            metrics.recordDuplicateSuppressed("ENRICHMENT");
            return;
        }

        try {
            enrichmentChain.enrich(instruction);
            TransitionResult result =
                    stateWriter.transition(instructionId, InstructionState.ENRICHED, ActorType.SYSTEM, ACTOR_ID, null, null);
            outboxWriter.write(toEnrichedMessage(instruction, result));
        } catch (BusinessFailureException e) {
            var first = e.details().get(0);
            TransitionResult result = stateWriter.transition(
                    instructionId, InstructionState.EXCEPTION, ActorType.SYSTEM, ACTOR_ID, first.reasonCode(), first.detail());
            outboxWriter.write(toExceptionMessage(instruction, result, e));
        } catch (ConcurrentTransitionException e) {
            log.info("Concurrent transition detected for {}, discarding as duplicate", instructionId);
            metrics.recordDuplicateSuppressed("ENRICHMENT");
        }
    }

    private OutboxMessage toEnrichedMessage(PaymentInstructionEntity instruction, TransitionResult result) {
        OffsetDateTime occurredAt = OffsetDateTime.now();
        InstructionStageEvent event = new InstructionStageEvent(
                instruction.getInstructionId(),
                instruction.getUetr(),
                instruction.getEndToEndId(),
                InstructionState.ENRICHED,
                result.sequenceNo(),
                occurredAt,
                instruction.getAmount(),
                instruction.getCurrency(),
                instruction.getDebtorAgentBic(),
                instruction.getCreditorAgentBic(),
                instruction.getRequestedExecDate(),
                InstructionStageEvent.CURRENT_VERSION);
        return new OutboxMessage(
                instruction.getInstructionId(),
                ENRICHED_TOPIC,
                instruction.getInstructionId().toString(),
                OutboxHeaders.of("InstructionEnriched", InstructionStageEvent.CURRENT_VERSION, occurredAt, null),
                event);
    }

    private OutboxMessage toExceptionMessage(PaymentInstructionEntity instruction, TransitionResult result, BusinessFailureException failure) {
        OffsetDateTime occurredAt = OffsetDateTime.now();
        InstructionExceptionEvent event = new InstructionExceptionEvent(
                instruction.getInstructionId(),
                instruction.getUetr(),
                instruction.getEndToEndId(),
                result.sequenceNo(),
                occurredAt,
                failure.stage(),
                failure.details().stream()
                        .map(v -> new InstructionExceptionEvent.Detail(v.reasonCode(), v.repairability(), v.field(), v.detail()))
                        .toList(),
                InstructionExceptionEvent.CURRENT_VERSION);
        return new OutboxMessage(
                instruction.getInstructionId(),
                EXCEPTIONS_TOPIC,
                instruction.getInstructionId().toString(),
                OutboxHeaders.of("InstructionException", InstructionExceptionEvent.CURRENT_VERSION, occurredAt, null),
                event);
    }

    private static InstructionStageEvent readEvent(String json) {
        try {
            return EventJson.MAPPER.readValue(json, InstructionStageEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed payments.validated event: " + json, e);
        }
    }
}
