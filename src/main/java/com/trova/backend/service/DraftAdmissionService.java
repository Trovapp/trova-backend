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
        List<TripDraft> legacy = draft.getUnlistedSourceId() == null
                ? List.of() : drafts.lockPossibleLegacyReplacements(userId, sourceId);
        LocalDateTime questionCutoff = LocalDateTime.now().minusHours(24);
        for (TripDraft candidate : legacy) {
            if (candidate.getStatus() != TripDraftStatus.NEEDS_INPUT || candidate.getUpdatedAt().isAfter(questionCutoff)) {
                return new Admission(Outcome.LEGACY_BUSY, null);
            }
        }
        open.forEach(previous -> previous.markFailed("같은 원본에서 새 일정을 요청해 이 질문을 종료했어요."));
        legacy.forEach(previous -> previous.markFailed("24시간 동안 답변이 없어 이전 버전의 질문이 만료됐어요."));
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
