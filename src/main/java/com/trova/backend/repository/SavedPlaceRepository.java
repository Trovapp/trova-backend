package com.trova.backend.repository;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SavedPlaceRepository extends JpaRepository<SavedPlace, Long> {

    // 엔티티가 필요한 기존 호출용. @ManyToOne 기본(EAGER)만으로는 작업마다 쿼리가
    // 따로 나가 SQL 22개가 나갔다(#29) — 작업·사용자를 조인한다. 목록 HTTP 응답은 아래 DTO 조회를 쓴다.
    @EntityGraph(attributePaths = {"processingJob", "processingJob.user", "user"})
    List<SavedPlace> findByUserOrderByCreatedAtDescIdDesc(User user);

    @Query("""
            select new com.trova.backend.repository.SavedPlaceListRow(
                p.id, j.id, j.sourceUrl, p.placeName, p.region, p.category, p.latitude, p.longitude,
                p.sourceUrl, p.title, p.sourcePlatform, p.createdAt, p.dayNumber, p.orderInDay,
                p.phone, p.address, p.roadAddress, p.kakaoCategoryName, p.kakaoPlaceUrl, p.videoNotes)
            from SavedPlace p join p.processingJob j
            where p.user.id = :userId
            order by p.createdAt desc, p.id desc
            """)
    List<SavedPlaceListRow> findListRowsByUserId(Long userId);
    Optional<SavedPlace> findByIdAndUser(Long id, User user);
    List<SavedPlace> findByProcessingJob(ProcessingJob processingJob);
    List<SavedPlace> findByProcessingJobAndDayNumberOrderByOrderInDayAsc(ProcessingJob processingJob, Integer dayNumber);
    void deleteByUser(User user);
}
