package com.kishore.payments.exception.api;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import com.kishore.payments.exception.cases.CaseStatus;
import com.kishore.payments.exception.cases.ExceptionCaseRepository;
import com.kishore.payments.exception.timeline.TimelineEntry;
import com.kishore.payments.exception.timeline.TimelineService;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/instructions")
public class InstructionController {

    /** Same definition as {@code ExceptionCaseOpener.TERMINAL_STATUSES} -- "no case is open" means no case in a non-terminal status. */
    private static final List<CaseStatus> TERMINAL_STATUSES = List.of(CaseStatus.RESOLVED, CaseStatus.REJECTED);

    private final PaymentInstructionRepository instructions;
    private final ExceptionCaseRepository cases;
    private final TimelineService timelineService;

    public InstructionController(
            PaymentInstructionRepository instructions, ExceptionCaseRepository cases, TimelineService timelineService) {
        this.instructions = instructions;
        this.cases = cases;
        this.timelineService = timelineService;
    }

    @GetMapping("/{instructionId}/timeline")
    @PreAuthorize("hasAnyRole('VIEWER', 'MAKER', 'CHECKER')")
    public List<TimelineEntry> timeline(@PathVariable UUID instructionId) {
        instructions.findById(instructionId).orElseThrow(() -> new NoSuchElementException("No payment instruction: " + instructionId));
        return timelineService.forInstruction(instructionId);
    }

    /** Exactly one of {@code uetr} or {@code endToEndId} is expected; uetr is globally unique, endToEndId is not (see PaymentInstructionRepository). */
    @GetMapping
    @PreAuthorize("hasAnyRole('VIEWER', 'MAKER', 'CHECKER')")
    public List<InstructionSummaryResponse> lookup(
            @RequestParam(required = false) UUID uetr, @RequestParam(required = false) String endToEndId) {
        if (uetr != null) {
            return instructions.findByUetr(uetr).map(List::of).orElseGet(List::of).stream().map(this::withOpenCase).toList();
        }
        if (endToEndId != null) {
            return instructions.findByEndToEndId(endToEndId).stream().map(this::withOpenCase).toList();
        }
        throw new IllegalArgumentException("One of uetr or endToEndId is required");
    }

    /** A lookup-screen result needs somewhere to navigate to (a case if one is open) -- see {@link InstructionSummaryResponse#openCaseId}. */
    private InstructionSummaryResponse withOpenCase(PaymentInstructionEntity entity) {
        UUID openCaseId = cases.findByInstructionIdAndStatusNotIn(entity.getInstructionId(), TERMINAL_STATUSES)
                .map(exceptionCase -> exceptionCase.getCaseId())
                .orElse(null);
        return InstructionSummaryResponse.of(entity, openCaseId);
    }
}
