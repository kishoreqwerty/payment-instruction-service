package com.kishore.payments.exception.api;

import com.kishore.payments.exception.cases.ExceptionCaseService;
import com.kishore.payments.exception.cases.InvestigationConfirmationRepository;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The CHECKER half of confirm-sent's maker-checker pair. Mirrors RepairController's own shape exactly, {@code /pending} included. */
@RestController
@RequestMapping("/v1/investigation-confirmations")
public class InvestigationConfirmationController {

    private final ExceptionCaseService caseService;
    private final InvestigationConfirmationRepository confirmations;

    public InvestigationConfirmationController(ExceptionCaseService caseService, InvestigationConfirmationRepository confirmations) {
        this.caseService = caseService;
        this.confirmations = confirmations;
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('VIEWER', 'MAKER', 'CHECKER')")
    public List<InvestigationConfirmationResponse> pending() {
        return confirmations.findByApprovedByIsNullOrderByProposedAtAsc().stream().map(InvestigationConfirmationResponse::of).toList();
    }

    @PostMapping("/{confirmationId}/approve")
    @PreAuthorize("hasRole('CHECKER')")
    public InvestigationConfirmationResponse approve(@PathVariable UUID confirmationId, Principal principal) {
        return InvestigationConfirmationResponse.of(caseService.approveConfirmSent(confirmationId, principal.getName()));
    }
}
