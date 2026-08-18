package com.kishore.payments.exception.cases;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ExceptionCaseRepository extends JpaRepository<ExceptionCaseEntity, UUID>, JpaSpecificationExecutor<ExceptionCaseEntity> {

    /** At most one row can ever match, per {@code uq_one_open_case_per_instruction}. */
    Optional<ExceptionCaseEntity> findByInstructionIdAndStatusNotIn(UUID instructionId, List<CaseStatus> terminalStatuses);

    /** Newest first: used to find the immediately-preceding case in an instruction's repair lineage, to inherit repair_attempts from. */
    List<ExceptionCaseEntity> findByInstructionIdOrderByOpenedAtDesc(UUID instructionId);
}
