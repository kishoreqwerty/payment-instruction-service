package com.kishore.payments.exception.repair;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The repair allowlist -- any field on the instruction, not "any field."
 * {@code amount}, {@code currency}, {@code debtorAccount}, {@code
 * endToEndId}, {@code uetr}, and every state column are deliberately absent:
 * changing the amount of a payment is not a repair, it is a different
 * payment, and it must go back to the originator. {@link #byFieldPath}
 * returns empty for anything not on this list, which is what {@code
 * ExceptionCaseService} uses to reject a disallowed field with 422 rather
 * than silently ignoring it or, worse, reflectively mutating an arbitrary
 * property.
 *
 * <p>{@code debtorAgentBic} is deliberately absent too, despite being on the
 * phase brief's own original list. The debtor's agent is our own side of
 * the payment: if it is wrong, our reference data about this debtor is
 * wrong, and fixing it on one instruction papers over a problem that will
 * recur on every subsequent payment through that agent. That is a {@code
 * STATIC_DATA} case and a reference-data fix followed by a retry, not a
 * field repair scoped to a single instruction.
 */
public enum RepairableField {
    CREDITOR_ACCOUNT("creditorAccount", PaymentInstructionEntity::getCreditorAccount, PaymentInstructionEntity::setCreditorAccount),
    CREDITOR_AGENT_BIC("creditorAgentBic", PaymentInstructionEntity::getCreditorAgentBic, PaymentInstructionEntity::setCreditorAgentBic),
    CREDITOR_NAME("creditorName", PaymentInstructionEntity::getCreditorName, PaymentInstructionEntity::setCreditorName),
    CHARGE_BEARER("chargeBearer", PaymentInstructionEntity::getChargeBearer, PaymentInstructionEntity::setChargeBearer),
    REQUESTED_EXECUTION_DATE(
            "requestedExecutionDate",
            entity -> entity.getRequestedExecDate() == null ? null : entity.getRequestedExecDate().toString(),
            (entity, value) -> entity.setRequestedExecDate(LocalDate.parse(value)));

    private final String fieldPath;
    private final Function<PaymentInstructionEntity, String> reader;
    private final BiConsumer<PaymentInstructionEntity, String> writer;

    RepairableField(String fieldPath, Function<PaymentInstructionEntity, String> reader, BiConsumer<PaymentInstructionEntity, String> writer) {
        this.fieldPath = fieldPath;
        this.reader = reader;
        this.writer = writer;
    }

    public String fieldPath() {
        return fieldPath;
    }

    public String read(PaymentInstructionEntity instruction) {
        return reader.apply(instruction);
    }

    public void write(PaymentInstructionEntity instruction, String newValue) {
        writer.accept(instruction, newValue);
    }

    public static Optional<RepairableField> byFieldPath(String fieldPath) {
        return Arrays.stream(values()).filter(f -> f.fieldPath.equals(fieldPath)).findFirst();
    }
}
