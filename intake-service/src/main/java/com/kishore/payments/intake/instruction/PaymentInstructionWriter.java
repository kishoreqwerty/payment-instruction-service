package com.kishore.payments.intake.instruction;

import com.kishore.payments.core.domain.ActorType;
import com.kishore.payments.core.state.InstructionState;
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

    private final PaymentInstructionRepository instructions;
    private final InstructionEventRepository events;

    public PaymentInstructionWriter(PaymentInstructionRepository instructions, InstructionEventRepository events) {
        this.instructions = instructions;
        this.events = events;
    }

    /**
     * Attempts to insert a brand-new instruction and its seed RECEIVED event
     * in one transaction. Throws (letting the caller's transaction roll back
     * cleanly) if the reference already exists -- callers are expected to
     * catch a constraint-violation failure and resolve it with a separate,
     * subsequent read via {@link #findByReference}, not by continuing to use
     * this transaction.
     */
    @Transactional
    public void insertNew(PaymentInstructionEntity entity) {
        instructions.saveAndFlush(entity);
        events.save(new InstructionEventEntity(
                entity.getInstructionId(),
                1,
                null,
                InstructionState.RECEIVED,
                ActorType.SYSTEM,
                "intake-service",
                null,
                null));
    }

    @Transactional(readOnly = true)
    public Optional<PaymentInstructionEntity> findByReference(String debtorAccount, String endToEndId) {
        return instructions.findByDebtorAccountAndEndToEndId(debtorAccount, endToEndId);
    }
}
