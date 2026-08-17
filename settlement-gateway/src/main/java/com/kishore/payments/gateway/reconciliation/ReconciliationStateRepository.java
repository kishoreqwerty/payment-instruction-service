package com.kishore.payments.gateway.reconciliation;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationStateRepository extends JpaRepository<ReconciliationStateEntity, UUID> {
}
