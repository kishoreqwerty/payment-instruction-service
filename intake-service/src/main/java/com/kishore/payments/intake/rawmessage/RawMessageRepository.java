package com.kishore.payments.intake.rawmessage;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawMessageRepository extends JpaRepository<RawMessageEntity, UUID> {
}
