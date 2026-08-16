package com.kishore.payments.core.instruction;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentInstructionRepository extends JpaRepository<PaymentInstructionEntity, UUID> {

    Optional<PaymentInstructionEntity> findByDebtorAccountAndEndToEndId(String debtorAccount, String endToEndId);

    Optional<PaymentInstructionEntity> findByUetr(UUID uetr);
}
