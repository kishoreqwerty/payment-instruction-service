package com.kishore.payments.intake.instruction;

import java.util.List;
import java.util.UUID;

/** Every way a submission to POST /v1/instructions can resolve. */
public sealed interface IntakeOutcome {

    /** @param duplicate true if this reference already existed with identical content */
    record Accepted(UUID instructionId, UUID uetr, boolean duplicate) implements IntakeOutcome {
    }

    record MalformedXml() implements IntakeOutcome {
    }

    record SchemaInvalid(List<String> violations) implements IntakeOutcome {
    }

    /** SEPA permits EndToEndId = "NOTPROVIDED"; this system requires a unique reference. See .notes/ARCHITECTURE.md §1.2. */
    record EndToEndIdNotProvided() implements IntakeOutcome {
    }

    /** Same (debtor_account, end_to_end_id) as an existing instruction, but the content differs: a sender defect, not a retry. */
    record ReferenceConflict(List<String> conflictingFields, UUID existingUetr) implements IntakeOutcome {
    }
}
