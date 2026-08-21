package com.kishore.payments.exception.cases;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable filters for {@code GET /v1/cases}: resolution status, failure
 * stage, reason code, repairability, assignee -- any subset, combined with
 * AND. {@code repairability} was added building the ops-dashboard (Phase 9):
 * the queue screen's own brief requires filtering on it, and Phase 8 never
 * needed it since it had no filtering UI of its own.
 */
public final class CaseSpecifications {

    private CaseSpecifications() {
    }

    public static Specification<ExceptionCaseEntity> matching(
            CaseStatus status, FailureStage failureStage, String reasonCode, Repairability repairability, String assignedTo) {
        return (root, query, cb) -> {
            var predicate = cb.conjunction();
            if (status != null) {
                predicate = cb.and(predicate, cb.equal(root.get("status"), status));
            }
            if (failureStage != null) {
                predicate = cb.and(predicate, cb.equal(root.get("failureStage"), failureStage));
            }
            if (reasonCode != null) {
                predicate = cb.and(predicate, cb.equal(root.get("reasonCode"), reasonCode));
            }
            if (repairability != null) {
                predicate = cb.and(predicate, cb.equal(root.get("repairability"), repairability));
            }
            if (assignedTo != null) {
                predicate = cb.and(predicate, cb.equal(root.get("assignedTo"), assignedTo));
            }
            return predicate;
        };
    }
}
