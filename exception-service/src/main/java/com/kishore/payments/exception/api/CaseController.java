package com.kishore.payments.exception.api;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import com.kishore.payments.exception.cases.CaseNotFoundException;
import com.kishore.payments.exception.cases.CaseSpecifications;
import com.kishore.payments.exception.cases.CaseStatus;
import com.kishore.payments.exception.cases.ExceptionCaseEntity;
import com.kishore.payments.exception.cases.ExceptionCaseRepository;
import com.kishore.payments.exception.cases.ExceptionCaseService;
import com.kishore.payments.exception.cases.InvestigationConfirmationRepository;
import com.kishore.payments.exception.repair.FieldChange;
import com.kishore.payments.exception.repair.RepairActionRepository;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/cases")
public class CaseController {

    private final ExceptionCaseRepository cases;
    private final RepairActionRepository repairActions;
    private final InvestigationConfirmationRepository confirmations;
    private final ExceptionCaseService caseService;
    private final PaymentInstructionRepository instructions;

    public CaseController(
            ExceptionCaseRepository cases,
            RepairActionRepository repairActions,
            InvestigationConfirmationRepository confirmations,
            ExceptionCaseService caseService,
            PaymentInstructionRepository instructions) {
        this.cases = cases;
        this.repairActions = repairActions;
        this.confirmations = confirmations;
        this.caseService = caseService;
        this.instructions = instructions;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('VIEWER', 'MAKER', 'CHECKER')")
    public Page<CaseSummaryResponse> list(
            @RequestParam(required = false) CaseStatus status,
            @RequestParam(required = false) FailureStage failureStage,
            @RequestParam(required = false) String reasonCode,
            @RequestParam(required = false) Repairability repairability,
            @RequestParam(required = false) String assignedTo,
            Pageable pageable) {
        // caseId (the primary key, always unique) is appended as a tiebreaker regardless of what
        // sort the caller asked for: without one, two cases with the same openedAt -- the same
        // age bucket, or bulk-seeded data sharing a timestamp -- have no defined relative order,
        // and Postgres is free to return them in a different order on a later, otherwise
        // identical query. That reads as the queue reshuffling between refreshes, when what's
        // actually missing is a total order to begin with.
        Pageable stableSort = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort().and(Sort.by(Sort.Direction.ASC, "caseId")));
        Page<ExceptionCaseEntity> page =
                cases.findAll(CaseSpecifications.matching(status, failureStage, reasonCode, repairability, assignedTo), stableSort);
        // Batched, not one instruction lookup per row: see CaseSummaryResponse's own javadoc.
        Map<UUID, PaymentInstructionEntity> byInstructionId = instructions
                .findAllById(page.getContent().stream().map(ExceptionCaseEntity::getInstructionId).toList())
                .stream()
                .collect(Collectors.toMap(PaymentInstructionEntity::getInstructionId, i -> i));
        return page.map(entity -> CaseSummaryResponse.of(entity, byInstructionId.get(entity.getInstructionId())));
    }

    @GetMapping("/{caseId}")
    @PreAuthorize("hasAnyRole('VIEWER', 'MAKER', 'CHECKER')")
    public CaseDetailResponse get(@PathVariable UUID caseId) {
        ExceptionCaseEntity exceptionCase = cases.findById(caseId).orElseThrow(() -> new CaseNotFoundException(caseId));
        return CaseDetailResponse.of(
                exceptionCase, instructionOf(exceptionCase), repairActions.findByCaseIdOrderByProposedAtAsc(caseId),
                confirmations.findByCaseId(caseId));
    }

    @PostMapping("/{caseId}/repairs")
    @PreAuthorize("hasRole('MAKER')")
    public ResponseEntity<List<RepairActionResponse>> propose(
            @PathVariable UUID caseId, @RequestBody List<FieldChange> changes, Principal principal) {
        List<RepairActionResponse> responses =
                caseService.proposeRepair(caseId, changes, principal.getName()).stream().map(RepairActionResponse::of).toList();
        return ResponseEntity.status(HttpStatus.CREATED).body(responses);
    }

    @PostMapping("/{caseId}/retry")
    @PreAuthorize("hasRole('MAKER')")
    public CaseSummaryResponse retry(@PathVariable UUID caseId, Principal principal) {
        ExceptionCaseEntity exceptionCase = caseService.retryStaticData(caseId, principal.getName());
        return CaseSummaryResponse.of(exceptionCase, instructionOf(exceptionCase));
    }

    /**
     * Phase 11: a direct record of what the operator did with the classifier's proposal --
     * "Accept" or "Edit/Ignore" on the panel, called regardless of whether they go on to submit a
     * repair at all. Not maker-checker gated, and not derived from comparing the eventual repair
     * to the suggestion afterward: see ExceptionCaseService.recordClassifierFeedback's own comment
     * for why a direct UI signal is the more honest choice here.
     */
    @PostMapping("/{caseId}/classifier-feedback")
    @PreAuthorize("hasRole('MAKER')")
    public CaseSummaryResponse classifierFeedback(@PathVariable UUID caseId, @RequestBody ClassifierFeedbackRequest body) {
        ExceptionCaseEntity exceptionCase = caseService.recordClassifierFeedback(caseId, body.accepted());
        return CaseSummaryResponse.of(exceptionCase, instructionOf(exceptionCase));
    }

    @PostMapping("/{caseId}/reject")
    @PreAuthorize("hasRole('CHECKER')")
    public CaseSummaryResponse reject(@PathVariable UUID caseId, Principal principal) {
        ExceptionCaseEntity exceptionCase = caseService.rejectCase(caseId, principal.getName());
        return CaseSummaryResponse.of(exceptionCase, instructionOf(exceptionCase));
    }

    /**
     * The MAKER half of confirm-sent's maker-checker pair (see
     * ExceptionCaseService's own comment on why confirm-sent, unlike
     * reject, gets this control): records the justification and evidence
     * the maker relied on and moves the case to PENDING_APPROVAL. Approval
     * is a separate resource -- POST /v1/investigation-confirmations/{id}/approve,
     * InvestigationConfirmationController -- mirroring exactly how a
     * proposed repair action is approved.
     */
    @PostMapping("/{caseId}/investigation/confirm-sent")
    @PreAuthorize("hasRole('MAKER')")
    public ResponseEntity<InvestigationConfirmationResponse> proposeConfirmSent(
            @PathVariable UUID caseId, @RequestBody JustificationRequest body, Principal principal) {
        InvestigationConfirmationResponse response =
                InvestigationConfirmationResponse.of(caseService.proposeConfirmSent(caseId, body.justification(), principal.getName()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{caseId}/investigation/reject")
    @PreAuthorize("hasRole('CHECKER')")
    public CaseSummaryResponse rejectInvestigation(@PathVariable UUID caseId, @RequestBody JustificationRequest body, Principal principal) {
        ExceptionCaseEntity exceptionCase = caseService.rejectInvestigation(caseId, body.justification(), principal.getName());
        return CaseSummaryResponse.of(exceptionCase, instructionOf(exceptionCase));
    }

    private PaymentInstructionEntity instructionOf(ExceptionCaseEntity exceptionCase) {
        return instructions
                .findById(exceptionCase.getInstructionId())
                .orElseThrow(() -> new NoSuchElementException("No payment instruction: " + exceptionCase.getInstructionId()));
    }
}
