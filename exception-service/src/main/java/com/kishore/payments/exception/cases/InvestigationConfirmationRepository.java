package com.kishore.payments.exception.cases;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestigationConfirmationRepository extends JpaRepository<InvestigationConfirmationEntity, UUID> {
}
