package com.kishore.payments.exception.repair;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepairActionRepository extends JpaRepository<RepairActionEntity, UUID> {

    List<RepairActionEntity> findByCaseIdOrderByProposedAtAsc(UUID caseId);

    List<RepairActionEntity> findByCaseIdIn(List<UUID> caseIds);

    /** The checker's approval queue (Phase 9): every proposal awaiting a decision, oldest first, across every case. */
    List<RepairActionEntity> findByApprovedByIsNullOrderByProposedAtAsc();
}
