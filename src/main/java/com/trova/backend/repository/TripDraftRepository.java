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

    @Modifying
    @Query("update TripDraft d set d.status = FAILED, d.errorMessage = :message, d.updatedAt = :now "
            + "where d.status in :statuses and d.updatedAt < :threshold")
    int failStale(List<TripDraftStatus> statuses, LocalDateTime threshold, LocalDateTime now, String message);

    // 답변 후 다시 PROCESSING이 되어도 이전 회차의 결과는 기록하지 않는다.
    @Modifying
    @Query("""
            update TripDraft d set d.status = :#{#result.status}, d.updatedAt = :#{#result.updatedAt},
                d.days = :#{#result.days}, d.startDate = :#{#result.startDate}, d.requestSource = :#{#result.requestSource},
                d.geminiCalls = :#{#result.geminiCalls}, d.summaryJson = :#{#result.summaryJson},
                d.question = :#{#result.question}, d.draftJson = :#{#result.draftJson}, d.errorMessage = :#{#result.errorMessage}
            where d.id = :id and d.status = PROCESSING and d.processingRound = :#{#result.processingRound}
            """)
    int finishIfProcessing(Long id, @Param("result") TripDraft result);

    // 작업을 시작한 뒤에도 대체 초안이 닫힐 수 있으므로 완료 트랜잭션에서 저장된 숨김 시각을 확인한다.
    @Modifying
    @Query("""
            update TripDraft d set d.dismissedAt = :now
            where d.dismissedAt is null and d.status <> APPROVED and d.id = (
                select r.replacesDraftId from TripDraft r where r.id = :replacementId and r.dismissedAt is null
            )
            """)
    void dismissSourceIfReplacementVisible(Long replacementId, LocalDateTime now);

    @Query("""
            select exists (select 1 from TripDraft d
                where d.user.id = :userId and d.dismissedAt is not null
                  and (d.replacesDraftId = :sourceId or d.unlistedSourceId = :sourceId))
            """)
    boolean existsDismissedReplacement(Long userId, Long sourceId);

    // 멈춘 초안 다시 보내기용(ADR 0043).
    @Query("select d.id from TripDraft d where d.status = :status and d.updatedAt > :from and d.updatedAt < :to")
    List<Long> findIdsByStatusAndUpdatedAtBetween(
            @Param("status") TripDraftStatus status, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select d.id from TripDraft d where d.user.id = :userId and d.requestKey = :key "
            + "and d.createdAt > :cutoff")
    Optional<Long> findRecentRequest(Long userId, UUID key, LocalDateTime cutoff);

    @Query("select d.id from TripDraft d join d.replayKeys k "
            + "where d.user.id = :userId and k = :key and d.createdAt > :cutoff")
    Optional<Long> findRecentReplayKey(Long userId, UUID key, LocalDateTime cutoff);

    @Modifying
    @Query(value = "insert into draft_replay_keys (draft_id, request_key) values (:draftId, :key)", nativeQuery = true)
    void addReplayKey(Long draftId, UUID key);

    @Modifying
    @Query(value = "delete from draft_replay_keys where draft_id in "
            + "(select id from trip_drafts where created_at <= :cutoff)", nativeQuery = true)
    void clearExpiredReplayKeys(LocalDateTime cutoff);

    @Query("""
            select d.id from TripDraft d
            where d.user.id = :userId and d.requestHash = :hash and d.createdAt > :cutoff
              and (d.status in :replayEvenIfClosed
                   or (d.dismissedAt is null and d.status in (PENDING, PROCESSING, NEEDS_INPUT, READY)))
            order by d.id desc limit 1
            """)
    Optional<Long> findReplayableContent(Long userId, String hash, LocalDateTime cutoff,
                                         List<TripDraftStatus> replayEvenIfClosed);

    // 답변 API와 같은 행을 잠가 질문의 재개와 교체가 동시에 성공하는 것을 막는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from TripDraft d where d.user.id = :userId and d.status in (PENDING, PROCESSING, NEEDS_INPUT) "
            + "and (d.replacesDraftId = :sourceId or d.unlistedSourceId = :sourceId) order by d.id")
    List<TripDraft> lockOpenReplacements(Long userId, Long sourceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from TripDraft d where d.user.id = :userId and d.id > :sourceId "
            + "and d.sourceTracked is null and d.autoCreated = false and d.replacesDraftId is null "
            + "and d.unlistedSourceId is null and d.status in (PENDING, PROCESSING, NEEDS_INPUT) order by d.id")
    List<TripDraft> lockPossibleLegacyReplacements(Long userId, Long sourceId);

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
