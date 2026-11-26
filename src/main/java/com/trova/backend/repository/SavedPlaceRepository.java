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
    long countByProcessingJobId(Long jobId);

    // 엔티티가 필요한 기존 호출용. @ManyToOne 기본(EAGER)만으로는 작업마다 쿼리가
    // 따로 나가 SQL 22개가 나갔다(#29) — 작업·사용자를 조인한다. 목록 HTTP 응답은 아래 DTO 조회를 쓴다.
    @EntityGraph(attributePaths = {"processingJob", "processingJob.user", "user"})
    List<SavedPlace> findByUserOrderByCreatedAtDescIdDesc(User user);

    record KakaoCategoryRow(String googlePlaceId, String category) {}

    @Query("""
            select distinct new com.trova.backend.repository.SavedPlaceRepository$KakaoCategoryRow(
                p.googlePlaceId, p.kakaoCategoryName)
            from SavedPlace p
            where p.user.id = :userId and p.googlePlaceId in :googleIds and p.kakaoCategoryName is not null
            """)
    List<KakaoCategoryRow> findKakaoCategories(Long userId, List<String> googleIds);

    String LIST_ROWS = """
            select new com.trova.backend.repository.SavedPlaceListRow(
                p.id, j.id, j.sourceUrl, p.placeName, p.region, p.category, p.latitude, p.longitude,
                p.sourceUrl, p.title, p.sourcePlatform, p.createdAt, p.dayNumber, p.orderInDay,
                p.phone, p.address, p.roadAddress, p.kakaoCategoryName, p.kakaoPlaceUrl, p.videoNotes, p.nearbyBranchName)
            from SavedPlace p join p.processingJob j
            where p.user.id = :userId
            """;

    @Query(LIST_ROWS + " order by p.createdAt desc, p.id desc")
    List<SavedPlaceListRow> findListRowsByUserId(Long userId);

    @Query(LIST_ROWS + " and j.id = :jobId order by p.createdAt desc, p.id desc")
    List<SavedPlaceListRow> findListRowsByUserIdAndJobId(Long userId, Long jobId);

    @Query("""
            select new com.trova.backend.repository.VideoHistoryRow(j.id, j.createdAt, count(p))
            from SavedPlace p join p.processingJob j
            where p.user.id = :userId and coalesce(
                'yt:' || cast(function('regexp_substr', j.sourceUrl, :youtube, 1, 1, 'i', 1) as String),
                'ig:' || cast(function('regexp_substr', j.sourceUrl, :instagram, 1, 1, 'i', 1) as String),
                trim(j.sourceUrl)) = :videoKey
            group by j.id, j.createdAt
            order by j.id desc
            """)
    List<VideoHistoryRow> findVideoHistory(Long userId, String videoKey, String youtube, String instagram);

    // 초안 상세의 현재 번호 계산용(#473) — 장소가 하나라도 남은 작업의 번호·주소만, 작업마다 한 행. 상태는 보지 않는다.
    @Query("""
            select distinct new com.trova.backend.repository.JobSourceUrlRow(j.id, j.sourceUrl)
            from SavedPlace p join p.processingJob j
            where p.user.id = :userId
            """)
    List<JobSourceUrlRow> findJobsWithPlacesByUserId(Long userId);
    Optional<SavedPlace> findByIdAndUser(Long id, User user);
    List<SavedPlace> findByProcessingJob(ProcessingJob processingJob);
    List<SavedPlace> findByProcessingJobAndDayNumberOrderByOrderInDayAsc(ProcessingJob processingJob, Integer dayNumber);
    void deleteByUser(User user);

    // 작업별 EAGER 추가 조회 없이 같은 영상의 재분석 결과를 한 번에 읽는다.
    @EntityGraph(attributePaths = {"processingJob", "processingJob.user", "user"})
    List<SavedPlace> findByProcessingJobIn(List<ProcessingJob> processingJobs);
}
