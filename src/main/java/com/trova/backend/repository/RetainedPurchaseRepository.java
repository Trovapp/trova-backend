package com.trova.backend.repository;

import com.trova.backend.entity.RetainedPurchase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RetainedPurchaseRepository extends JpaRepository<RetainedPurchase, Long> {
    Optional<RetainedPurchase> findByTransactionId(String transactionId);

    @Modifying
    @Query("delete from RetainedPurchase r where r.retainUntil < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
