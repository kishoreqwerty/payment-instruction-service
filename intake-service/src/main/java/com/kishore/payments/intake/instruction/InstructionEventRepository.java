package com.kishore.payments.intake.instruction;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InstructionEventRepository extends JpaRepository<InstructionEventEntity, Long> {
}
