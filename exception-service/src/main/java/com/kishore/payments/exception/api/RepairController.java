package com.kishore.payments.exception.api;

import com.kishore.payments.exception.cases.ExceptionCaseService;
import java.security.Principal;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/repairs")
public class RepairController {

    private final ExceptionCaseService caseService;

    public RepairController(ExceptionCaseService caseService) {
        this.caseService = caseService;
    }

    @PostMapping("/{actionId}/approve")
    @PreAuthorize("hasRole('CHECKER')")
    public RepairActionResponse approve(@PathVariable UUID actionId, Principal principal) {
        return RepairActionResponse.of(caseService.approveRepair(actionId, principal.getName()));
    }
}
