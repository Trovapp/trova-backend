package com.trova.backend.repository;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, Long> {
    List<ProcessingJob> findByUserOrderByCreatedAtDescIdDesc(User user);
    List<ProcessingJob> findByUserAndStatusIn(User user, List<JobStatus> statuses);
    List<ProcessingJob> findByUserAndSourceUrlAndStatusIn(User user, String sourceUrl, List<JobStatus> statuses);
    Optional<ProcessingJob> findByIdAndUser(Long id, User user);
    void deleteByUser(User user);
    // 서버 재시작 등으로 대기열에서 사라진 작업 정리용(#43).
    List<ProcessingJob> findByStatusInAndUpdatedAtBefore(List<JobStatus> statuses, java.time.LocalDateTime updatedBefore);
    long countByUserAndCreatedAtGreaterThanEqual(User user, java.time.LocalDateTime from);
}
