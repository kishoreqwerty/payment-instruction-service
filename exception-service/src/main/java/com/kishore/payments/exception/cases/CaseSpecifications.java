package com.kishore.payments.exception.cases;

import com.kishore.payments.core.domain.FailureStage;
import org.springframework.data.jpa.domain.Specification;

/** Composable filters for {@code GET /v1/cases}: resolution status, failure stage, reason code, assignee -- any subset, combined with AND. */
public final class CaseSpecifications {

    private CaseSpecifications() {
    }

    public static Specification<ExceptionCaseEntity> matching(CaseStatus status, FailureStage failureStage, String reasonCode, String assignedTo) {
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
            if (assignedTo != null) {
                predicate = cb.and(predicate, cb.equal(root.get("assignedTo"), assignedTo));
            }
            return predicate;
        };
    }
}
