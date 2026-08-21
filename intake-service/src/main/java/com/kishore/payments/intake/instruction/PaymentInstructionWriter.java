package com.kishore.payments.intake.instruction;

import com.kishore.payments.core.domain.ActorType;
import com.kishore.payments.core.event.InstructionReceivedEvent;
import com.kishore.payments.core.instruction.InstructionEventEntity;
import com.kishore.payments.core.instruction.InstructionEventRepository;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import com.kishore.payments.core.outbox.OutboxHeaders;
import com.kishore.payments.core.outbox.OutboxMessage;
import com.kishore.payments.core.outbox.OutboxWriter;
import com.kishore.payments.core.state.InstructionState;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A separate bean from {@code InstructionIntakeService} on purpose: Spring's
 * {@code @Transactional} is proxy-based and self-invocation (a bean calling
 * its own {@code @Transactional} method) bypasses the proxy entirely, silently
 * running with no transaction at all. Keeping the transactional boundaries
 * here and calling into this bean from outside guarantees they're honoured.
 */
@Component
public class PaymentInstructionWriter {

    private static final String TOPIC = "payments.received";

    private final PaymentInstructionRepository instructions;
    private final InstructionEventRepository events;
    private final OutboxWriter outbox;

    public PaymentInstructionWriter(
            PaymentInstructionRepository instructions, InstructionEventRepository events, OutboxWriter outbox) {
        this.instructions = instructions;
        this.events = events;
        this.outbox = outbox;
    }

    /**
     * Attempts to insert a brand-new instruction, its seed RECEIVED event,
     * and the payments.received outbox row, all in one transaction. Throws
     * (letting the caller's transaction roll back cleanly, taking all three
     * writes with it) if the reference already exists -- callers are
     * expected to catch a constraint-violation failure and resolve it with a
     * separate, subsequent read via {@link #findByReference}, not by
     * continuing to use this transaction.
     */
    @Transactional
    public void insertNew(PaymentInstructionEntity entity) {
        instructions.saveAndFlush(entity);

        int sequenceNo = 1;
        events.save(new InstructionEventEntity(
                entity.getInstructionId(),
                sequenceNo,
                null,
                InstructionState.RECEIVED,
                ActorType.SYSTEM,
                "intake-service",
                null,
                null));

        OffsetDateTime occurredAt = OffsetDateTime.now();
        InstructionReceivedEvent event = new InstructionReceivedEvent(
                entity.getInstructionId(),
                entity.getUetr(),
                entity.getEndToEndId(),
                InstructionState.RECEIVED,
                sequenceNo,
                occurredAt,
                entity.getAmount(),
                entity.getCurrency(),
                entity.getDebtorAgentBic(),
                entity.getCreditorAgentBic(),
                entity.getRequestedExecDate(),
                InstructionReceivedEvent.CURRENT_VERSION);

        outbox.write(new OutboxMessage(
                entity.getInstructionId(),
                TOPIC,
                entity.getInstructionId().toString(),
                OutboxHeaders.of("InstructionReceived", InstructionReceivedEvent.CURRENT_VERSION, occurredAt),
                event));
    }

    @Transactional(readOnly = true)
    public Optional<PaymentInstructionEntity> findByReference(String debtorAccount, String endToEndId) {
        return instructions.findByDebtorAccountAndEndToEndId(debtorAccount, endToEndId);
    }
}
