package com.kishore.payments.core.instruction;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InstructionEventRepository extends JpaRepository<InstructionEventEntity, Long> {
}
