package com.kishore.payments.intake.instruction;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.intake.pain001.Pain001MessageParser;
import com.kishore.payments.intake.pain001.Pain001Validator;
import com.kishore.payments.intake.pain001.ParsedPain001Instruction;
import com.kishore.payments.intake.rawmessage.RawMessageEntity;
import com.kishore.payments.intake.rawmessage.RawMessageService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a single POST /v1/instructions submission: persist raw bytes,
 * validate against the XSD, parse, and either create a new instruction or
 * resolve a reference collision -- all without a pre-check SELECT. The insert
 * is attempted directly; a uq_reference violation is caught and only then
 * does a second, separate read decide whether it's a retry or a conflict.
 * That's deliberate: a check-then-insert has a race window two identical
 * requests can both slip through, so the database has to be the one to
 * arbitrate.
 */
@Service
public class InstructionIntakeService {

    private static final String NOT_PROVIDED = "NOTPROVIDED";

    private final RawMessageService rawMessageService;
    private final Pain001Validator validator;
    private final Pain001MessageParser parser;
    private final PaymentInstructionWriter writer;
    private final IntakeMetrics metrics;

    public InstructionIntakeService(
            RawMessageService rawMessageService,
            Pain001Validator validator,
            Pain001MessageParser parser,
            PaymentInstructionWriter writer,
            IntakeMetrics metrics) {
        this.rawMessageService = rawMessageService;
        this.validator = validator;
        this.parser = parser;
        this.writer = writer;
        this.metrics = metrics;
    }

    public IntakeOutcome receive(byte[] payload, String sourceChannel, String sourceIdentifier, String contentType) {
        // Counted here, before validation/parsing: "received" means an intake attempt arrived on
        // this channel, the same moment raw_message is persisted below, regardless of what this
        // service later decides about it -- malformed, schema-invalid, a duplicate, all still count.
        metrics.recordReceived(sourceChannel);
        RawMessageEntity raw = rawMessageService.persist(payload, sourceChannel, sourceIdentifier, contentType);

        Pain001Validator.ValidationResult validation = validator.validate(payload);
        if (!validation.wellFormed()) {
            return new IntakeOutcome.MalformedXml();
        }
        if (!validation.violations().isEmpty()) {
            return new IntakeOutcome.SchemaInvalid(validation.violations());
        }

        ParsedPain001Instruction parsed = parser.parse(payload);

        if (NOT_PROVIDED.equals(parsed.endToEndId())) {
            return new IntakeOutcome.EndToEndIdNotProvided();
        }

        return insertOrResolve(raw.getRawMessageId(), parsed);
    }

    private IntakeOutcome insertOrResolve(UUID rawMessageId, ParsedPain001Instruction parsed) {
        UUID instructionId = UUID.randomUUID();
        UUID uetr = parsed.uetr() != null ? parsed.uetr() : UUID.randomUUID();
        PaymentInstructionEntity entity = new PaymentInstructionEntity(
                instructionId,
                rawMessageId,
                uetr,
                parsed.endToEndId(),
                parsed.instructionIdExt(),
                parsed.debtorName(),
                parsed.debtorAccount(),
                parsed.debtorAgentBic(),
                parsed.creditorName(),
                parsed.creditorAccount(),
                parsed.creditorAgentBic(),
                parsed.amount(),
                parsed.currency(),
                parsed.chargeBearer(),
                parsed.requestedExecDate());

        try {
            writer.insertNew(entity);
            return new IntakeOutcome.Accepted(instructionId, uetr, false);
        } catch (DataIntegrityViolationException e) {
            if (!isRetryCollision(e)) {
                throw e;
            }
            return resolveAgainstExisting(parsed);
        }
    }

    private IntakeOutcome resolveAgainstExisting(ParsedPain001Instruction parsed) {
        PaymentInstructionEntity existing = writer.findByReference(parsed.debtorAccount(), parsed.endToEndId())
                .orElseThrow(() -> new IllegalStateException(
                        "uq_reference violated but no row found for debtor_account/end_to_end_id combination"));

        List<String> conflicts = contentDiffs(existing, parsed);
        if (conflicts.isEmpty()) {
            return new IntakeOutcome.Accepted(existing.getInstructionId(), existing.getUetr(), true);
        }
        return new IntakeOutcome.ReferenceConflict(conflicts, existing.getUetr());
    }

    /**
     * A resubmission of the exact same message can collide on either of two
     * constraints: uq_reference (debtor_account, end_to_end_id) always, and
     * additionally the plain UNIQUE on uetr when the inbound message carries
     * its own UETR (so every resubmission proposes the same one). Postgres
     * reports whichever constraint it happens to check first, so both names
     * are treated the same way -- resolve by re-reading the row that must
     * already exist, rather than by branching on which one fired. A UETR
     * collision across two genuinely different (debtor_account, end_to_end_id)
     * references isn't handled here; findByReference would find nothing and
     * resolveAgainstExisting's orElseThrow surfaces that as a 500, which is
     * an acceptable fallback for what should be a near-impossible input.
     */
    private static boolean isRetryCollision(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        if (cause.getMessage() == null) {
            return false;
        }
        return cause.getMessage().contains("uq_reference") || cause.getMessage().contains("payment_instruction_uetr_key");
    }

    private static List<String> contentDiffs(PaymentInstructionEntity existing, ParsedPain001Instruction parsed) {
        List<String> diffs = new ArrayList<>();
        if (!existing.getCreditorAccount().equals(parsed.creditorAccount())) {
            diffs.add("creditorAccount");
        }
        if (!existing.getCreditorAgentBic().equals(parsed.creditorAgentBic())) {
            diffs.add("creditorAgentBic");
        }
        if (existing.getAmount().compareTo(parsed.amount()) != 0) {
            diffs.add("amount");
        }
        if (!existing.getCurrency().equals(parsed.currency())) {
            diffs.add("currency");
        }
        if (!existing.getRequestedExecDate().equals(parsed.requestedExecDate())) {
            diffs.add("requestedExecDate");
        }
        return diffs;
    }
}
