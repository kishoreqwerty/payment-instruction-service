package com.kishore.payments.intake.api;

import com.kishore.payments.intake.instruction.InstructionIntakeService;
import com.kishore.payments.intake.instruction.IntakeOutcome;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/instructions")
public class IntakeController {

    private final InstructionIntakeService intakeService;

    public IntakeController(InstructionIntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<?> receive(@RequestBody byte[] body, HttpServletRequest request) {
        // No auth/client-identity concept in this phase; remote address is a
        // best-effort source_identifier for the raw_message audit row.
        IntakeOutcome outcome = intakeService.receive(body, "REST", request.getRemoteAddr(), MediaType.APPLICATION_XML_VALUE);

        return switch (outcome) {
            // A 200 (not 409) for an exact-content resubmission is
            // deliberate: a sender retrying after a network timeout is
            // behaving correctly, and an error response is exactly the
            // pressure that would push it toward generating a fresh
            // EndToEndId on the next attempt -- the actual double-payment
            // mechanism this design exists to avoid. Returning the original
            // UETR tells the sender the payment it meant to make already
            // exists.
            case IntakeOutcome.Accepted(var instructionId, var uetr, var duplicate) -> ResponseEntity
                    .status(duplicate ? HttpStatus.OK : HttpStatus.ACCEPTED)
                    .body(new AcceptedResponse(instructionId, uetr, duplicate));

            case IntakeOutcome.MalformedXml ignored -> ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("MALFORMED_XML", "The request body is not well-formed XML."));

            case IntakeOutcome.SchemaInvalid(var violations) -> ResponseEntity
                    .status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new SchemaInvalidResponse("SCHEMA_INVALID", violations));

            case IntakeOutcome.EndToEndIdNotProvided ignored -> ResponseEntity
                    .status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new ErrorResponse(
                            "END_TO_END_ID_NOT_PROVIDED",
                            "EndToEndId must be a unique reference for this payment; the literal value NOTPROVIDED is not accepted."));

            // Same reference as an existing instruction, different content:
            // a sender defect, not a retry -- rejected rather than silently
            // accepted under a wider key or silently dropped under a
            // narrower one. See .notes/reports/PHASE-2-REPORT.md §6.
            case IntakeOutcome.ReferenceConflict(var conflictingFields, var existingUetr) -> ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(new ReferenceConflictResponse("REFERENCE_CONFLICT", conflictingFields, existingUetr));
        };
    }

    private record AcceptedResponse(UUID instructionId, UUID uetr, boolean duplicate) {
    }

    private record ErrorResponse(String error, String detail) {
    }

    private record SchemaInvalidResponse(String error, List<String> violations) {
    }

    private record ReferenceConflictResponse(String error, List<String> conflictingFields, UUID existingUetr) {
    }
}
