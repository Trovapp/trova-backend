package com.trova.backend.repository;

import com.trova.backend.entity.ApiCallLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ApiCallLogRepository extends JpaRepository<ApiCallLog, Long> {
    List<ApiCallLog> findByCreatedAtAfter(LocalDateTime since);
}
