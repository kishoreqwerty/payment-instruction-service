package com.kishore.payments.exception.cases;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestigationConfirmationRepository extends JpaRepository<InvestigationConfirmationEntity, UUID> {

    /** The checker's approval queue (Phase 9): every confirm-sent proposal awaiting a decision, oldest first, across every case. */
    List<InvestigationConfirmationEntity> findByApprovedByIsNullOrderByProposedAtAsc();

    /** Mirrors {@code RepairActionRepository#findByCaseIdIn}: this case's own confirmations, for the case detail and timeline views. */
    List<InvestigationConfirmationEntity> findByCaseIdIn(List<UUID> caseIds);

    List<InvestigationConfirmationEntity> findByCaseId(UUID caseId);
}
