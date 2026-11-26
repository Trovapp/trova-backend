package com.trova.backend.repository;

import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripDraftRepository extends JpaRepository<TripDraft, Long> {

    /** 승인·답변을 두 번 눌러도 한 번만 처리되게 행을 잠그고 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from TripDraft d where d.id = :id")
    Optional<TripDraft> findByIdForUpdate(@Param("id") Long id);

    List<TripDraft> findByStatusInAndUpdatedAtBefore(List<TripDraftStatus> statuses, LocalDateTime threshold);

    // 멈춘 초안 다시 보내기용(ADR 0043).
    @Query("select d.id from TripDraft d where d.status = :status and d.updatedAt > :from and d.updatedAt < :to")
    List<Long> findIdsByStatusAndUpdatedAtBetween(
            @Param("status") TripDraftStatus status, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select d.id from TripDraft d where d.user.id = :userId and d.requestKey = :key "
            + "and d.createdAt > :cutoff")
    Optional<Long> findRecentRequest(Long userId, UUID key, LocalDateTime cutoff);

    @Query("select d.id from TripDraft d where d.user.id = :userId and d.status in :statuses "
            + "and (d.replacesDraftId = :sourceId or d.unlistedSourceId = :sourceId) order by d.id limit 1")
    Optional<Long> findActiveReplacement(Long userId, Long sourceId, List<TripDraftStatus> statuses);

    @Modifying
    @Query(value = "update trip_drafts set request_key = null where request_key is not null and created_at <= :cutoff",
            nativeQuery = true)
    void clearExpiredRequestKeys(LocalDateTime cutoff);

    void deleteByUser(User user);

    // 자동 생성 초안 중 같은 영상으로 또 만들지 않았는지 확인할 때 쓴다(#136).
    List<TripDraft> findByUserAndAutoCreatedTrue(User user);

    // 위치 바로잡기 표시용 — 홈에 보이는 초안 중 승인·닫힘·실패는 제외한다(#450).
    @Query("select d from TripDraft d where d.user = :user "
            + "and (d.autoCreated = true or d.replacesDraftId is not null) and d.dismissedAt is null "
            + "and d.status not in (com.trova.backend.entity.TripDraftStatus.APPROVED, "
            + "com.trova.backend.entity.TripDraftStatus.FAILED)")
    List<TripDraft> findListedForPlacesChangeByUser(@Param("user") User user);

    // 홈 화면에 보여줄 자동 초안 — 닫지 않았고 아직 끝나지 않은 것만, 최신순.
    List<TripDraft> findByUserAndAutoCreatedTrueAndDismissedAtIsNullAndStatusInOrderByCreatedAtDesc(
            User user, List<TripDraftStatus> statuses);

    // 홈에 보여줄 초안 — 자동 초안과, 그런 초안을 "다시 짜기"로 대신한 초안(#424). 닫지 않은 것만, 최신순.
    @Query("select d from TripDraft d where d.user.id = :userId and (d.autoCreated = true or d.replacesDraftId is not null) "
            + "and d.dismissedAt is null and d.status in :statuses order by d.createdAt desc, d.id desc")
    List<TripDraft> findListedByUserId(@Param("userId") Long userId, @Param("statuses") List<TripDraftStatus> statuses);

    @Query("select new com.trova.backend.repository.AutoDraftRow(d.id, d.status, d.jobIds, d.days, d.createdAt, d.draftJson) "
            + "from TripDraft d where d.user.id = :userId and (d.autoCreated = true or d.replacesDraftId is not null) "
            + "and d.dismissedAt is null and d.status in :statuses order by d.createdAt desc, d.id desc")
    List<AutoDraftRow> findListedRowsByUserId(@Param("userId") Long userId, @Param("statuses") List<TripDraftStatus> statuses);

    // 사용자 ID만으로 읽는 조회 API용(ADR 0036) — 사용자 행을 따로 읽지 않는다.
    List<TripDraft> findByUserIdAndAutoCreatedTrueAndDismissedAtIsNullAndStatusInOrderByCreatedAtDesc(
            Long userId, List<TripDraftStatus> statuses);
}
