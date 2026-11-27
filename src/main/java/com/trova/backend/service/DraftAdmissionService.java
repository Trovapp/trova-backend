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
    public enum Outcome { CREATED, REPLAY, CONFLICT, LEGACY_BUSY, INVALID }
    public record Admission(Outcome outcome, Long draftId) {}

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
        List<TripDraft> open = sourceId == null ? List.of() : drafts.lockOpenReplacements(userId, sourceId);
        for (TripDraft previous : open) {
            if (previous.getStatus() == TripDraftStatus.PENDING || previous.getStatus() == TripDraftStatus.PROCESSING) {
                return new Admission(Outcome.CONFLICT, previous.getId());
            }
        }
        Optional<TripDraft> prepared = planner.prepareDraft(user, jobIds, message, sourceId);
        if (prepared.isEmpty()) {
            return new Admission(Outcome.INVALID, null);
        }
        TripDraft draft = prepared.get();
        if (draft.getUnlistedSourceId() != null && drafts.hasPossibleLegacyReplacement(userId, sourceId)) {
            return new Admission(Outcome.LEGACY_BUSY, null);
        }
        for (TripDraft previous : open) {
            if (previous.getStatus() == TripDraftStatus.NEEDS_INPUT) {
                previous.markFailed("같은 원본에서 새 일정을 요청해 이 질문을 종료했어요.");
            }
        }
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
