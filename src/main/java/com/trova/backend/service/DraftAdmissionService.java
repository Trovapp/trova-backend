package com.trova.backend.service;

import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.entity.User;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.UserRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class DraftAdmissionService {
    public enum Outcome { CREATED, REPLAY, CONFLICT, INVALID }
    public record Admission(Outcome outcome, Long draftId) {}

    private static final List<TripDraftStatus> ACTIVE = List.of(
            TripDraftStatus.PENDING, TripDraftStatus.PROCESSING, TripDraftStatus.NEEDS_INPUT);
    private final UserRepository users;
    private final TripDraftRepository drafts;
    private final TripPlannerService planner;

    public DraftAdmissionService(UserRepository users, TripDraftRepository drafts, TripPlannerService planner) {
        this.users = users;
        this.drafts = drafts;
        this.planner = planner;
    }

    @Transactional
    public Admission admit(Long userId, List<Long> jobIds, String message, Long sourceId, UUID key) {
        User user = users.lockById(userId)
                .orElseThrow(() -> new InsufficientAuthenticationException("사용자를 찾을 수 없습니다"));
        if (key != null) {
            Optional<Long> replay = drafts.findRecentRequest(userId, key, keyCutoff());
            if (replay.isPresent()) {
                return new Admission(Outcome.REPLAY, replay.get());
            }
        }
        if (sourceId != null) {
            Optional<Long> active = drafts.findActiveReplacement(userId, sourceId, ACTIVE);
            if (active.isPresent()) {
                return new Admission(Outcome.CONFLICT, active.get());
            }
        }
        Optional<TripDraft> prepared = planner.prepareDraft(user, jobIds, message, sourceId);
        if (prepared.isEmpty()) {
            return new Admission(Outcome.INVALID, null);
        }
        TripDraft draft = prepared.get();
        draft.recordRequestKey(key);
        return new Admission(Outcome.CREATED, drafts.save(draft).getId());
    }

    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.HOURS)
    @Transactional
    public void clearExpiredKeys() {
        drafts.clearExpiredRequestKeys(keyCutoff());
    }

    private static LocalDateTime keyCutoff() {
        return LocalDateTime.now().minusHours(24);
    }
}
