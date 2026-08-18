package com.kishore.payments.core.instruction;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstructionEventRepository extends JpaRepository<InstructionEventEntity, Long> {

    /** Phase 8's timeline endpoint: every transition an instruction has ever made, in the order they happened. */
    List<InstructionEventEntity> findByInstructionIdOrderBySequenceNoAsc(UUID instructionId);
}
