package com.trova.backend.repository;

import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripDraftStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface TripDraftRepository extends JpaRepository<TripDraft, Long> {

    List<TripDraft> findByStatusInAndUpdatedAtBefore(List<TripDraftStatus> statuses, LocalDateTime threshold);
}
