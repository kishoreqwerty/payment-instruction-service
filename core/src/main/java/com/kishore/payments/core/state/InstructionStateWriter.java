package com.kishore.payments.core.state;

import com.kishore.payments.core.domain.ActorType;
import com.kishore.payments.core.instruction.InstructionEventEntity;
import com.kishore.payments.core.instruction.InstructionEventRepository;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import java.time.OffsetDateTime;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wires {@link StateMachine} (pure) to persistence: loads the current state
 * and state_version, validates the move, updates payment_instruction under
 * the optimistic lock, and inserts the matching instruction_event -- both
 * writes in one transaction. Shared by every service that moves an
 * instruction through the state machine (intake-service, processing-service)
 * so the transition semantics -- and in particular what counts as a
 * {@link ConcurrentTransitionException} -- are identical everywhere rather
 * than independently reimplemented per service.
 */
public class InstructionStateWriter {

    private final PaymentInstructionRepository instructions;
    private final InstructionEventRepository events;
    private final StateMachine stateMachine;

    public InstructionStateWriter(
            PaymentInstructionRepository instructions, InstructionEventRepository events, StateMachine stateMachine) {
        this.instructions = instructions;
        this.events = events;
        this.stateMachine = stateMachine;
    }

    @Transactional
    public TransitionResult transition(
            UUID instructionId, InstructionState to, ActorType actorType, String actorId, String reasonCode, String reasonDetail) {
        PaymentInstructionEntity entity = instructions
                .findById(instructionId)
                .orElseThrow(() -> new NoSuchElementException("No payment instruction: " + instructionId));

        InstructionState from = entity.getState();
        TransitionContext ctx = new TransitionContext(actorType, actorId, reasonCode, reasonDetail, entity.getStateVersion());

        // Throws IllegalTransitionException and propagates unchanged if the
        // move isn't legal; nothing has been written yet at that point.
        TransitionResult result = stateMachine.transition(from, to, ctx);

        entity.setState(to);
        entity.setUpdatedAt(OffsetDateTime.now());
        try {
            // flush forces the WHERE instruction_id = ? AND state_version = ?
            // update to execute now, inside this try block, rather than at
            // commit time after this method has already returned.
            instructions.saveAndFlush(entity);
        } catch (OptimisticLockingFailureException e) {
            throw new ConcurrentTransitionException(instructionId, from, to, e);
        }

        InstructionEventEntity event = new InstructionEventEntity(
                instructionId, result.sequenceNo(), from, to, actorType, actorId, reasonCode, reasonDetail);
        events.save(event);

        return result;
    }
}
