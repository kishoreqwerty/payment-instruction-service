package com.kishore.payments.exception.api;

import com.kishore.payments.exception.cases.ExceptionCaseService;
import com.kishore.payments.exception.repair.RepairActionRepository;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/repairs")
public class RepairController {

    private final ExceptionCaseService caseService;
    private final RepairActionRepository repairActions;

    public RepairController(ExceptionCaseService caseService, RepairActionRepository repairActions) {
        this.caseService = caseService;
        this.repairActions = repairActions;
    }

    /**
     * The approval queue's data source (Phase 9, added building the
     * ops-dashboard -- see PHASE-9-REPORT.md §5): every unapproved repair
     * action across every case, already carrying the from/to diff and the
     * proposer, so the checker's landing screen never has to open a case to
     * show what changed.
     */
    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('VIEWER', 'MAKER', 'CHECKER')")
    public List<RepairActionResponse> pending() {
        return repairActions.findByApprovedByIsNullOrderByProposedAtAsc().stream().map(RepairActionResponse::of).toList();
    }

    @PostMapping("/{actionId}/approve")
    @PreAuthorize("hasRole('CHECKER')")
    public RepairActionResponse approve(@PathVariable UUID actionId, Principal principal) {
        return RepairActionResponse.of(caseService.approveRepair(actionId, principal.getName()));
    }
}
