package com.trova.backend.repository;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from ProcessingJob j where j.id = :id")
    Optional<ProcessingJob> findForAnalysisCompletion(@Param("id") Long id);

    List<ProcessingJob> findByUserOrderByCreatedAtDescIdDesc(User user);
    List<ProcessingJob> findByUserAndStatusIn(User user, List<JobStatus> statuses);
    // 사용자 ID만으로 읽는 조회 API용(ADR 0036) — 사용자 행을 따로 읽지 않는다.
    List<ProcessingJob> findByUserIdAndStatusIn(Long userId, List<JobStatus> statuses);
    List<ProcessingJob> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);
    List<ProcessingJob> findByUserAndSourceUrlAndStatusIn(User user, String sourceUrl, List<JobStatus> statuses);
    boolean existsByUserAndSourceUrlAndIdGreaterThan(User user, String sourceUrl, Long id);
    Optional<ProcessingJob> findByIdAndUser(Long id, User user);
    void deleteByUser(User user);
    // 서버 재시작 등으로 대기열에서 사라진 작업 정리용(#43).
    List<ProcessingJob> findByStatusInAndUpdatedAtBefore(List<JobStatus> statuses, java.time.LocalDateTime updatedBefore);

    // 멈춘 작업 다시 보내기용 — 정리기가 FAILED로 바꾸기 전(from 이후)이면서 오래 갱신 없는(to 이전) 작업 id(ADR 0043).
    @Query("select j.id from ProcessingJob j where j.status = :status and j.updatedAt > :from and j.updatedAt < :to")
    List<Long> findIdsByStatusAndUpdatedAtBetween(
            @Param("status") JobStatus status, @Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to);
    long countByUserAndCreatedAtGreaterThanEqual(User user, java.time.LocalDateTime from);
}
