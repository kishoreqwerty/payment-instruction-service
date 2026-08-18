package com.kishore.payments.processing.consumer;

import com.kishore.payments.core.domain.ActorType;
import com.kishore.payments.core.event.EventJson;
import com.kishore.payments.core.event.InstructionRoutedEvent;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import com.kishore.payments.core.outbox.OutboxHeaders;
import com.kishore.payments.core.outbox.OutboxMessage;
import com.kishore.payments.core.outbox.OutboxWriter;
import com.kishore.payments.core.state.ConcurrentTransitionException;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.core.state.InstructionStateWriter;
import com.kishore.payments.core.state.TransitionResult;
import com.kishore.payments.core.event.InstructionExceptionEvent;
import com.kishore.payments.processing.event.InstructionStageEvent;
import com.kishore.payments.processing.failure.BusinessFailureException;
import com.kishore.payments.processing.routing.RailRouter;
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
 * Stage 3: ENRICHED -> ROUTED (or -> EXCEPTION). Consumes payments.enriched,
 * produced by {@link EnrichmentConsumer}. Idempotency compares {@code
 * sequence_no}, not state -- see {@link ValidationConsumer}'s class comment.
 */
@Component
public class RoutingConsumer {

    private static final Logger log = LoggerFactory.getLogger(RoutingConsumer.class);
    private static final String ROUTED_TOPIC = "payments.routed";
    private static final String EXCEPTIONS_TOPIC = "payments.exceptions";
    private static final String ACTOR_ID = "processing-service:routing";

    private final PaymentInstructionRepository instructions;
    private final RailRouter railRouter;
    private final InstructionStateWriter stateWriter;
    private final OutboxWriter outboxWriter;
    private final ProcessingMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    public RoutingConsumer(
            PaymentInstructionRepository instructions,
            RailRouter railRouter,
            InstructionStateWriter stateWriter,
            OutboxWriter outboxWriter,
            ProcessingMetrics metrics,
            PlatformTransactionManager transactionManager) {
        this.instructions = instructions;
        this.railRouter = railRouter;
        this.stateWriter = stateWriter;
        this.outboxWriter = outboxWriter;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @KafkaListener(
            topics = "payments.enriched",
            groupId = "${payments.kafka.consumer-group}",
            concurrency = "${payments.kafka.enriched-partitions:12}")
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
            metrics.recordDuplicateSuppressed("ROUTING");
            return;
        }

        try {
            var decision = railRouter.route(instruction);
            TransitionResult result = stateWriter.transition(instructionId, InstructionState.ROUTED, ActorType.SYSTEM, ACTOR_ID, null, null);
            outboxWriter.write(toRoutedMessage(instruction, result));
            log.debug("Routed {} to {} (eligible: {})", instructionId, decision.selectedRail(), decision.eligibleRails());
        } catch (BusinessFailureException e) {
            var first = e.details().get(0);
            TransitionResult result = stateWriter.transition(
                    instructionId, InstructionState.EXCEPTION, ActorType.SYSTEM, ACTOR_ID, first.reasonCode(), first.detail());
            outboxWriter.write(toExceptionMessage(instruction, result, e));
        } catch (ConcurrentTransitionException e) {
            log.info("Concurrent transition detected for {}, discarding as duplicate", instructionId);
            metrics.recordDuplicateSuppressed("ROUTING");
        }
    }

    private OutboxMessage toRoutedMessage(PaymentInstructionEntity instruction, TransitionResult result) {
        OffsetDateTime occurredAt = OffsetDateTime.now();
        InstructionRoutedEvent event = new InstructionRoutedEvent(
                instruction.getInstructionId(),
                instruction.getUetr(),
                instruction.getEndToEndId(),
                result.sequenceNo(),
                occurredAt,
                instruction.getAmount(),
                instruction.getCurrency(),
                instruction.getSelectedRail(),
                instruction.getCorrespondentBic(),
                instruction.getNostroAccount(),
                instruction.getSettlementDate(),
                InstructionRoutedEvent.CURRENT_VERSION);
        return new OutboxMessage(
                instruction.getInstructionId(),
                ROUTED_TOPIC,
                instruction.getInstructionId().toString(),
                OutboxHeaders.of("InstructionRouted", InstructionRoutedEvent.CURRENT_VERSION, occurredAt, null),
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
            throw new IllegalStateException("Malformed payments.enriched event: " + json, e);
        }
    }
}
