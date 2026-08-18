package com.kishore.payments.exception.api;

import com.kishore.payments.exception.cases.ExceptionCaseEntity;
import com.kishore.payments.exception.repair.RepairActionEntity;
import java.util.List;

public record CaseDetailResponse(CaseSummaryResponse exceptionCase, List<RepairActionResponse> repairActions) {

    public static CaseDetailResponse of(ExceptionCaseEntity entity, List<RepairActionEntity> actions) {
        return new CaseDetailResponse(CaseSummaryResponse.of(entity), actions.stream().map(RepairActionResponse::of).toList());
    }
}
