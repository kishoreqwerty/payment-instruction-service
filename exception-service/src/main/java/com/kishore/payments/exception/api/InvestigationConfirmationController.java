package com.kishore.payments.exception.api;

import com.kishore.payments.exception.cases.ExceptionCaseService;
import java.security.Principal;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The CHECKER half of confirm-sent's maker-checker pair. Mirrors RepairController's own shape exactly. */
@RestController
@RequestMapping("/v1/investigation-confirmations")
public class InvestigationConfirmationController {

    private final ExceptionCaseService caseService;

    public InvestigationConfirmationController(ExceptionCaseService caseService) {
        this.caseService = caseService;
    }

    @PostMapping("/{confirmationId}/approve")
    @PreAuthorize("hasRole('CHECKER')")
    public InvestigationConfirmationResponse approve(@PathVariable UUID confirmationId, Principal principal) {
        return InvestigationConfirmationResponse.of(caseService.approveConfirmSent(confirmationId, principal.getName()));
    }
}
