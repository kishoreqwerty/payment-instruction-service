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
 *
 * <p>"Another writer already got here first" can surface at two different
 * points in {@link #transition}, depending on timing, and callers should
 * not have to know that: if this thread's own read of the row is still the
 * pre-race value, the race is caught later as an optimistic-lock failure
 * on the write. If this thread's read lands *after* another writer's
 * commit -- more likely under real contention than it looks in a fast,
 * low-concurrency test, since it only takes one writer finishing before a
 * slower one even starts reading -- the row already reflects the new state,
 * and if this call's own target is exactly that new state, {@link
 * StateMachine#transition} rejects it as illegal (no state has a
 * transition to itself in {@link StateTransitionTable}) purely because it
 * looks like a self-transition, not because the request was ever wrong.
 * Both cases are the same fact observed at different points in this method,
 * so both are reported as {@link ConcurrentTransitionException} -- see
 * {@link #transition}'s own comment for exactly where the second case is
 * caught and reclassified. A genuinely illegal request -- {@code from !=
 * to} and the transition table still rejects it -- is not reclassified and
 * keeps surfacing as {@link IllegalTransitionException}, uncaught here,
 * with no plausible race explanation to fall back on.
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

        TransitionResult result;
        try {
            result = stateMachine.transition(from, to, ctx);
        } catch (IllegalTransitionException e) {
            if (from == to) {
                // Not a genuinely illegal request: another writer's own
                // transition to this exact target committed between our
                // read and this validation, so the row is already exactly
                // where this call wanted to take it. Racing on state_version
                // (below, via the optimistic lock) and racing on the read
                // that feeds this validation are the same underlying event
                // -- "another writer got here first" -- observed at two
                // different points in this method depending on timing; only
                // one of them used to be reported as ConcurrentTransitionException.
                // Reported as the same exception here so every existing
                // caller's existing catch (ConcurrentTransitionException)
                // handles this correctly without needing to know a second
                // exception type exists. No state has a transition to
                // itself in StateTransitionTable, so from == to can only
                // mean this, never a legitimate direct request.
                throw new ConcurrentTransitionException(instructionId, from, to, e);
            }
            // Genuinely illegal from wherever the row actually is right
            // now, race or no race: propagate unchanged. Nothing has been
            // written at this point either way.
            throw e;
        }

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
