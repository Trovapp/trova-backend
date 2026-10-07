package com.trova.backend.repository;

import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TripDraftRepository extends JpaRepository<TripDraft, Long> {

    /** 승인·답변을 두 번 눌러도 한 번만 처리되게 행을 잠그고 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from TripDraft d where d.id = :id")
    Optional<TripDraft> findByIdForUpdate(@Param("id") Long id);

    List<TripDraft> findByStatusInAndUpdatedAtBefore(List<TripDraftStatus> statuses, LocalDateTime threshold);

    void deleteByUser(User user);

    // 무료·여행 패스 한도(#130)
    long countByUserAndCreatedAtGreaterThanEqual(User user, LocalDateTime from);
}
