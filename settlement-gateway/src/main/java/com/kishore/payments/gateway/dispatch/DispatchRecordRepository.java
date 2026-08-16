package com.kishore.payments.gateway.dispatch;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DispatchRecordRepository extends JpaRepository<DispatchRecordEntity, UUID> {

    List<DispatchRecordEntity> findByUetrOrderByAttemptNo(UUID uetr);

    @Query("SELECT COALESCE(MAX(d.attemptNo), 0) FROM DispatchRecordEntity d WHERE d.uetr = :uetr")
    int maxAttemptNo(@Param("uetr") UUID uetr);
}
