package com.kishore.payments.processing.consumer;

import com.kishore.payments.core.domain.ActorType;
import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.event.EventJson;
import com.kishore.payments.core.event.InstructionReceivedEvent;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import com.kishore.payments.core.outbox.OutboxHeaders;
import com.kishore.payments.core.outbox.OutboxMessage;
import com.kishore.payments.core.outbox.OutboxWriter;
import com.kishore.payments.core.state.ConcurrentTransitionException;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.core.state.InstructionStateWriter;
import com.kishore.payments.core.state.TransitionResult;
import com.kishore.payments.processing.event.InstructionExceptionEvent;
import com.kishore.payments.processing.event.InstructionStageEvent;
import com.kishore.payments.processing.failure.FailureDetail;
import com.kishore.payments.processing.validation.ValidationChain;
import java.time.OffsetDateTime;
import java.util.List;
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
 * Stage 1: RECEIVED -> VALIDATED (or -> EXCEPTION). Consumes
 * payments.received, produced by intake-service.
 *
 * <p>Idempotency is the first thing this does, before any validation work,
 * and it compares {@code sequence_no}, not state (see {@link
 * #process(UUID, int)}): a repaired instruction legitimately revisits a
 * state it has already occupied -- REPAIRED -> VALIDATED re-enters exactly
 * the state RECEIVED -> VALIDATED once produced -- so comparing state alone
 * cannot tell a genuine re-entry apart from a stale redelivery. sequence_no
 * is monotonic per instruction by construction and never repeats, which
 * state does.
 */
@Component
public class ValidationConsumer {

    private static final Logger log = LoggerFactory.getLogger(ValidationConsumer.class);
    private static final String VALIDATED_TOPIC = "payments.validated";
    private static final String EXCEPTIONS_TOPIC = "payments.exceptions";
    private static final String ACTOR_ID = "processing-service:validation";

    private final PaymentInstructionRepository instructions;
    private final ValidationChain validationChain;
    private final InstructionStateWriter stateWriter;
    private final OutboxWriter outboxWriter;
    private final ProcessingMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    public ValidationConsumer(
            PaymentInstructionRepository instructions,
            ValidationChain validationChain,
            InstructionStateWriter stateWriter,
            OutboxWriter outboxWriter,
            ProcessingMetrics metrics,
            PlatformTransactionManager transactionManager) {
        this.instructions = instructions;
        this.validationChain = validationChain;
        this.stateWriter = stateWriter;
        this.outboxWriter = outboxWriter;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @KafkaListener(
            topics = "payments.received",
            groupId = "${payments.kafka.consumer-group}",
            concurrency = "${payments.kafka.received-partitions:12}")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        InstructionReceivedEvent event = readEvent(record.value());
        transactionTemplate.executeWithoutResult(status -> process(event.instructionId(), event.sequenceNo()));
        ack.acknowledge();
    }

    private void process(UUID instructionId, int eventSequenceNo) {
        PaymentInstructionEntity instruction = instructions
                .findById(instructionId)
                .orElseThrow(() -> new NoSuchElementException("No payment instruction: " + instructionId));

        // instruction.getStateVersion() is the instruction's current
        // sequence_no in disguise: every transition through
        // InstructionStateWriter computes sequence_no as the pre-update
        // state_version + 1 and JPA's @Version bumps state_version by
        // exactly the same +1 on the same write, in the same transaction --
        // the two counters cannot drift. A strictly earlier event
        // (eventSequenceNo < current) represents a transition the
        // instruction has already moved past, whether by this exact event
        // being redelivered or by a repair re-entry that legitimately
        // revisits the same state under a later sequence_no; either way
        // there is nothing to do here. Equal is the normal, first-time
        // case -- the event's sequence_no is always exactly the
        // instruction's current one when it hasn't been superseded, not
        // strictly less, so "already handled" is < , not <=.
        if (eventSequenceNo < instruction.getStateVersion()) {
            metrics.recordDuplicateSuppressed("VALIDATION");
            return;
        }

        List<FailureDetail> violations = validationChain.validate(instruction);
        try {
            if (violations.isEmpty()) {
                TransitionResult result =
                        stateWriter.transition(instructionId, InstructionState.VALIDATED, ActorType.SYSTEM, ACTOR_ID, null, null);
                outboxWriter.write(toValidatedMessage(instruction, result));
            } else {
                FailureDetail first = violations.get(0);
                TransitionResult result = stateWriter.transition(
                        instructionId, InstructionState.EXCEPTION, ActorType.SYSTEM, ACTOR_ID, first.reasonCode(), first.detail());
                outboxWriter.write(toExceptionMessage(instruction, result, FailureStage.VALIDATION, violations));
            }
        } catch (ConcurrentTransitionException e) {
            // Another consumer (or replica) already moved this instruction
            // between our read and our write. Not a failure -- the same
            // idempotent-discard outcome as the state check above.
            log.info("Concurrent transition detected for {}, discarding as duplicate", instructionId);
            metrics.recordDuplicateSuppressed("VALIDATION");
        }
    }

    private OutboxMessage toValidatedMessage(PaymentInstructionEntity instruction, TransitionResult result) {
        OffsetDateTime occurredAt = OffsetDateTime.now();
        InstructionStageEvent event = new InstructionStageEvent(
                instruction.getInstructionId(),
                instruction.getUetr(),
                instruction.getEndToEndId(),
                InstructionState.VALIDATED,
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
                VALIDATED_TOPIC,
                instruction.getInstructionId().toString(),
                OutboxHeaders.of("InstructionValidated", InstructionStageEvent.CURRENT_VERSION, occurredAt, null),
                event);
    }

    private OutboxMessage toExceptionMessage(
            PaymentInstructionEntity instruction, TransitionResult result, FailureStage stage, List<FailureDetail> violations) {
        OffsetDateTime occurredAt = OffsetDateTime.now();
        InstructionExceptionEvent event = new InstructionExceptionEvent(
                instruction.getInstructionId(),
                instruction.getUetr(),
                instruction.getEndToEndId(),
                result.sequenceNo(),
                occurredAt,
                stage,
                violations.stream().map(v -> new InstructionExceptionEvent.Detail(v.reasonCode(), v.repairability(), v.field(), v.detail())).toList(),
                InstructionExceptionEvent.CURRENT_VERSION);
        return new OutboxMessage(
                instruction.getInstructionId(),
                EXCEPTIONS_TOPIC,
                instruction.getInstructionId().toString(),
                OutboxHeaders.of("InstructionException", InstructionExceptionEvent.CURRENT_VERSION, occurredAt, null),
                event);
    }

    private static InstructionReceivedEvent readEvent(String json) {
        try {
            return EventJson.MAPPER.readValue(json, InstructionReceivedEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed payments.received event: " + json, e);
        }
    }
}
