package com.kishore.payments.core.state;

import com.kishore.payments.core.domain.ActorType;

/**
 * The circumstances under which a state transition is attempted.
 *
 * @param reasonCode nullable — an ISO external reason code, present for
 *                   failure or repair transitions
 * @param reasonDetail nullable — free-text detail accompanying the reason code
 * @param currentSequenceNo the sequence number of the instruction's most
 *                          recently recorded event; the transition's event
 *                          is recorded at {@code currentSequenceNo + 1}
 */
public record TransitionContext(
        ActorType actorType,
        String actorId,
        String reasonCode,
        String reasonDetail,
        int currentSequenceNo) {
}
