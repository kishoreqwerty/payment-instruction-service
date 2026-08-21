package com.kishore.payments.exception.api;

import com.kishore.payments.exception.repair.RepairableField;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Added building the ops-dashboard (Phase 9, see PHASE-9-REPORT.md §5): the
 * repair form must offer only allowlisted fields (brief §3/acceptance
 * criterion 2), and {@code RepairableField} is exactly that allowlist --
 * but nothing served it before this. Without this endpoint, the only two
 * options were hardcoding a second copy of the five field names in the
 * dashboard (silently correct today, silently wrong the next time this
 * enum changes -- exactly the kind of drift {@code debtorAgentBic}'s own
 * removal, one phase ago, is a live example of) or discovering the
 * allowlist by trial-and-error against the 422 body. Reading it from the
 * enum itself means the two can never disagree.
 */
@RestController
@RequestMapping("/v1/repairable-fields")
public class RepairableFieldsController {

    @GetMapping
    @PreAuthorize("hasAnyRole('VIEWER', 'MAKER', 'CHECKER')")
    public List<RepairableFieldResponse> list() {
        return Arrays.stream(RepairableField.values()).map(f -> new RepairableFieldResponse(f.fieldPath(), label(f.fieldPath()))).toList();
    }

    private static String label(String fieldPath) {
        String withSpaces = fieldPath.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase();
        return Character.toUpperCase(withSpaces.charAt(0)) + withSpaces.substring(1);
    }

    public record RepairableFieldResponse(String fieldPath, String label) {
    }
}
