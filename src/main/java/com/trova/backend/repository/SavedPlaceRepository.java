package com.trova.backend.repository;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedPlaceRepository extends JpaRepository<SavedPlace, Long> {
    List<SavedPlace> findByProcessingJobIn(java.util.Collection<ProcessingJob> processingJobs);

    // 목록 응답이 처리 작업(제목·원본 주소)을 읽는데, @ManyToOne 기본(EAGER)만으로는 작업마다 쿼리가
    // 따로 나가 요청 한 번에 SQL 22개가 나갔다(#29, 실측) — 작업·사용자를 한 번에 조인해 가져온다.
    @EntityGraph(attributePaths = {"processingJob", "processingJob.user", "user"})
    List<SavedPlace> findByUserOrderByCreatedAtDescIdDesc(User user);
    Optional<SavedPlace> findByIdAndUser(Long id, User user);
    List<SavedPlace> findByProcessingJob(ProcessingJob processingJob);
    List<SavedPlace> findByProcessingJobAndDayNumberOrderByOrderInDayAsc(ProcessingJob processingJob, Integer dayNumber);
    void deleteByUser(User user);
}
