package com.kishore.payments.exception.event;

import com.kishore.payments.core.event.EventJson;
import com.kishore.payments.core.event.InstructionExceptionEvent;
import com.kishore.payments.exception.cases.ExceptionCaseService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumes payments.exceptions -- the only consumer this topic has ever
 * had. {@link ExceptionCaseService#handleExceptionEvent} is naturally
 * idempotent under Kafka's at-least-once redelivery without needing an
 * explicit sequence-number guard here: a redelivered event either finds no
 * case yet (the first attempt's transaction never committed, so this is
 * correctly a first attempt again) or finds the case its own first delivery
 * already wrote (open or appended), and appending the same reason/detail a
 * second time is a harmless overwrite with identical values, not a second
 * side effect -- see that method's own javadoc.
 */
@Component
public class ExceptionEventConsumer {

    private final ExceptionCaseService caseService;
    private final TransactionTemplate transactionTemplate;

    public ExceptionEventConsumer(ExceptionCaseService caseService, PlatformTransactionManager transactionManager) {
        this.caseService = caseService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @KafkaListener(
            topics = "payments.exceptions",
            groupId = "${payments.kafka.consumer-group}",
            concurrency = "${payments.kafka.exceptions-partitions:6}")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        InstructionExceptionEvent event = readEvent(record.value());
        transactionTemplate.executeWithoutResult(status -> caseService.handleExceptionEvent(event));
        ack.acknowledge();
    }

    private static InstructionExceptionEvent readEvent(String json) {
        try {
            return EventJson.MAPPER.readValue(json, InstructionExceptionEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed payments.exceptions event: " + json, e);
        }
    }
}
