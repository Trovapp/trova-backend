package com.trova.backend.repository;

import com.trova.backend.entity.ServerMetricSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ServerMetricSnapshotRepository extends JpaRepository<ServerMetricSnapshot, Long> {
    List<ServerMetricSnapshot> findByInstanceNameAndMeasuredAtGreaterThanEqualOrderByMeasuredAt(String instanceName, LocalDateTime from);

    @Modifying
    @Query("delete from ServerMetricSnapshot s where s.measuredAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
