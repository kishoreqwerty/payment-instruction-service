package com.kishore.payments.gateway.consumer;

import com.kishore.payments.core.event.EventJson;
import com.kishore.payments.core.event.InstructionRoutedEvent;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import com.kishore.payments.gateway.dispatch.DispatchOrchestrator;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes payments.routed and dispatches. Idempotency compares {@code
 * sequence_no} against the instruction's current {@code state_version},
 * exactly like processing-service's consumers -- a redelivery of an
 * already-superseded routed event (the instruction has since moved to SENT,
 * SENT_UNCONFIRMED, or EXCEPTION) is discarded before any assembly or
 * dispatch is attempted. Deliberately does not wrap the whole method in one
 * transaction: {@link DispatchOrchestrator} manages its own transaction
 * boundaries per dispatch attempt (see its class comment for why), and
 * acknowledgment only happens after it returns.
 */
@Component
public class RoutedConsumer {

    private static final Logger log = LoggerFactory.getLogger(RoutedConsumer.class);

    private final PaymentInstructionRepository instructions;
    private final DispatchOrchestrator orchestrator;

    public RoutedConsumer(PaymentInstructionRepository instructions, DispatchOrchestrator orchestrator) {
        this.instructions = instructions;
        this.orchestrator = orchestrator;
    }

    @KafkaListener(
            topics = "payments.routed",
            groupId = "${payments.kafka.consumer-group}",
            concurrency = "${payments.kafka.routed-partitions:12}")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        InstructionRoutedEvent event = readEvent(record.value());
        process(event.instructionId(), event.sequenceNo());
        ack.acknowledge();
    }

    private void process(UUID instructionId, int eventSequenceNo) {
        PaymentInstructionEntity instruction = instructions
                .findById(instructionId)
                .orElseThrow(() -> new NoSuchElementException("No payment instruction: " + instructionId));

        // Same "< not <=" reasoning as processing-service's ValidationConsumer:
        // the normal, first-time delivery has eventSequenceNo == state_version
        // exactly, since this event is what set it.
        if (eventSequenceNo < instruction.getStateVersion()) {
            log.debug("Discarding already-superseded payments.routed event for {}", instructionId);
            return;
        }

        orchestrator.dispatch(instructionId);
    }

    private static InstructionRoutedEvent readEvent(String json) {
        try {
            return EventJson.MAPPER.readValue(json, InstructionRoutedEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed payments.routed event: " + json, e);
        }
    }
}
