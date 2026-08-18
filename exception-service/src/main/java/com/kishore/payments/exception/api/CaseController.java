package com.kishore.payments.exception.api;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.exception.cases.CaseNotFoundException;
import com.kishore.payments.exception.cases.CaseSpecifications;
import com.kishore.payments.exception.cases.CaseStatus;
import com.kishore.payments.exception.cases.ExceptionCaseEntity;
import com.kishore.payments.exception.cases.ExceptionCaseRepository;
import com.kishore.payments.exception.cases.ExceptionCaseService;
import com.kishore.payments.exception.repair.FieldChange;
import com.kishore.payments.exception.repair.RepairActionRepository;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    private final ExceptionCaseService caseService;

    public CaseController(ExceptionCaseRepository cases, RepairActionRepository repairActions, ExceptionCaseService caseService) {
        this.cases = cases;
        this.repairActions = repairActions;
        this.caseService = caseService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('VIEWER', 'MAKER', 'CHECKER')")
    public Page<CaseSummaryResponse> list(
            @RequestParam(required = false) CaseStatus status,
            @RequestParam(required = false) FailureStage failureStage,
            @RequestParam(required = false) String reasonCode,
            @RequestParam(required = false) String assignedTo,
            Pageable pageable) {
        return cases.findAll(CaseSpecifications.matching(status, failureStage, reasonCode, assignedTo), pageable).map(CaseSummaryResponse::of);
    }

    @GetMapping("/{caseId}")
    @PreAuthorize("hasAnyRole('VIEWER', 'MAKER', 'CHECKER')")
    public CaseDetailResponse get(@PathVariable UUID caseId) {
        ExceptionCaseEntity exceptionCase = cases.findById(caseId).orElseThrow(() -> new CaseNotFoundException(caseId));
        return CaseDetailResponse.of(exceptionCase, repairActions.findByCaseIdOrderByProposedAtAsc(caseId));
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
        return CaseSummaryResponse.of(caseService.retryStaticData(caseId, principal.getName()));
    }

    @PostMapping("/{caseId}/reject")
    @PreAuthorize("hasRole('CHECKER')")
    public CaseSummaryResponse reject(@PathVariable UUID caseId, Principal principal) {
        return CaseSummaryResponse.of(caseService.rejectCase(caseId, principal.getName()));
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
        return CaseSummaryResponse.of(caseService.rejectInvestigation(caseId, body.justification(), principal.getName()));
    }
}
