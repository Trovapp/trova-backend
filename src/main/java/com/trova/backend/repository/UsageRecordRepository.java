package com.trova.backend.repository;

import com.trova.backend.entity.MeteredFeature;
import com.trova.backend.entity.UsageRecord;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, Long> {
    long countByUserAndFeatureAndCreatedAtGreaterThanEqual(User user, MeteredFeature feature, LocalDateTime from);

    void deleteByUser(User user);
}
