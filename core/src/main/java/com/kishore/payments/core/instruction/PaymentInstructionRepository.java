package com.kishore.payments.core.instruction;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentInstructionRepository extends JpaRepository<PaymentInstructionEntity, UUID> {

    Optional<PaymentInstructionEntity> findByDebtorAccountAndEndToEndId(String debtorAccount, String endToEndId);

    Optional<PaymentInstructionEntity> findByUetr(UUID uetr);

    /**
     * Not a single result: uq_reference is {@code (debtor_account,
     * end_to_end_id)}, not end_to_end_id alone (see .notes/ARCHITECTURE.md
     * §3.2), so two different debtor accounts can legitimately send the
     * same end-to-end reference. Phase 8's {@code GET
     * /v1/instructions?endToEndId=} lookup returns every match rather than
     * assuming there is exactly one.
     */
    List<PaymentInstructionEntity> findByEndToEndId(String endToEndId);
}
