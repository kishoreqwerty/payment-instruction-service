package com.kishore.payments.gateway.callback;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.outbox.OutboxHeaders;
import com.kishore.payments.core.outbox.OutboxMessage;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.core.state.TransitionResult;
import com.kishore.payments.core.event.InstructionExceptionEvent;
import com.kishore.payments.gateway.event.InstructionSettlementEvent;
import com.kishore.payments.gateway.failure.FailureDetail;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/** Outbox message construction shared by {@link CallbackCorrelationService} and {@link CallbackStatusApplier}. */
final class CallbackOutboxMessages {

    private static final String SETTLED_TOPIC = "payments.settled";
    private static final String EXCEPTIONS_TOPIC = "payments.exceptions";

    private CallbackOutboxMessages() {
    }

    static OutboxMessage toSettlementMessage(
            PaymentInstructionEntity instruction, TransitionResult result, InstructionState state, String railId, String railReasonCode, Clock clock) {
        OffsetDateTime occurredAt = OffsetDateTime.now(clock);
        InstructionSettlementEvent event = new InstructionSettlementEvent(
                instruction.getInstructionId(),
                instruction.getUetr(),
                instruction.getEndToEndId(),
                state,
                result.sequenceNo(),
                occurredAt,
                railId,
                railReasonCode,
                InstructionSettlementEvent.CURRENT_VERSION);
        return new OutboxMessage(
                instruction.getInstructionId(),
                SETTLED_TOPIC,
                instruction.getInstructionId().toString(),
                OutboxHeaders.of("InstructionSettlement", InstructionSettlementEvent.CURRENT_VERSION, occurredAt),
                event);
    }

    static OutboxMessage toExceptionMessage(PaymentInstructionEntity instruction, TransitionResult result, FailureDetail detail, Clock clock) {
        OffsetDateTime occurredAt = OffsetDateTime.now(clock);
        InstructionExceptionEvent event = new InstructionExceptionEvent(
                instruction.getInstructionId(),
                instruction.getUetr(),
                instruction.getEndToEndId(),
                result.sequenceNo(),
                occurredAt,
                com.kishore.payments.core.domain.FailureStage.CONFIRMATION,
                List.of(new InstructionExceptionEvent.Detail(detail.reasonCode(), detail.repairability(), detail.field(), detail.detail())),
                InstructionExceptionEvent.CURRENT_VERSION);
        return new OutboxMessage(
                instruction.getInstructionId(),
                EXCEPTIONS_TOPIC,
                instruction.getInstructionId().toString(),
                OutboxHeaders.of("InstructionException", InstructionExceptionEvent.CURRENT_VERSION, occurredAt),
                event);
    }
}
