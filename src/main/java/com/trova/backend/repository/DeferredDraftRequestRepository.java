package com.trova.backend.repository;

import com.trova.backend.entity.DeferredDraftRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface DeferredDraftRequestRepository extends JpaRepository<DeferredDraftRequest, Long> {
    @Query("select r.createdAt from DeferredDraftRequest r where r.user.id = :userId and r.requestKey = :key "
            + "and r.requestHash = :hash and r.createdAt > :cutoff")
    Optional<LocalDateTime> waitingSince(Long userId, UUID key, String hash, LocalDateTime cutoff);

    @Modifying
    @Query("delete from DeferredDraftRequest r where r.user.id = :userId and r.requestKey = :key")
    void clearKey(Long userId, UUID key);

    @Modifying
    @Query(value = """
            insert into draft_replay_keys (draft_id, request_key)
            select :draftId, request_key from deferred_draft_requests
            where user_id = :userId and request_hash = :hash and created_at > :cutoff
            """, nativeQuery = true)
    void linkWaitingKeys(Long userId, String hash, LocalDateTime cutoff, Long draftId);

    @Modifying
    @Query("delete from DeferredDraftRequest r where r.user.id = :userId and r.requestHash = :hash")
    void clearContent(Long userId, String hash);

    @Modifying
    @Query("delete from DeferredDraftRequest r where r.createdAt <= :cutoff")
    void clearExpired(LocalDateTime cutoff);
}
