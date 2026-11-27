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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class DraftAdmissionService {
    public enum Outcome { CREATED, REPLAY, CONFLICT, LEGACY_BUSY, INVALID }
    public record Admission(Outcome outcome, Long draftId) {}

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

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
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime keyCutoff = now.minusHours(24);
        if (key != null) {
            Optional<Long> replay = drafts.findRecentRequest(userId, key, keyCutoff)
                    .or(() -> drafts.findRecentReplayKey(userId, key, keyCutoff));
            if (replay.isPresent()) {
                return new Admission(Outcome.REPLAY, replay.get());
            }
        }
        List<TripDraft> open = sourceId == null ? List.of() : drafts.lockOpenReplacements(userId, sourceId);
        String hash = TripPlannerService.validDraftInput(jobIds, message) ? requestHash(jobIds, message, sourceId) : null;
        boolean initialCreation = sourceId == null;
        LocalDateTime contentCutoff = initialCreation ? keyCutoff : now.minusMinutes(10);
        Optional<Long> matching = hash == null ? Optional.empty()
                : drafts.findReplayableContent(userId, hash, contentCutoff, initialCreation);
        // 다른 조건의 새 대체 작업이 이미 열렸다면 과거 작업을 재생하지 않는다.
        if (matching.isPresent() && (open.isEmpty() || open.getFirst().getId().equals(matching.get()))) {
            Long draftId = matching.get();
            if (key != null) {
                drafts.addReplayKey(draftId, key);
            }
            return new Admission(Outcome.REPLAY, draftId);
        }
        for (TripDraft previous : open) {
            if (previous.getStatus() == TripDraftStatus.PENDING || previous.getStatus() == TripDraftStatus.PROCESSING) {
                // 409 본문이 유실돼도 같은 입력의 재시도는 이 작업의 최초 202를 복구한다.
                if (key != null && previous.matchesRecentRequest(hash, keyCutoff)) {
                    drafts.addReplayKey(previous.getId(), key);
                }
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
        LocalDateTime questionCutoff = now.minusHours(24);
        for (TripDraft candidate : legacy) {
            if (candidate.getStatus() != TripDraftStatus.NEEDS_INPUT || candidate.getUpdatedAt().isAfter(questionCutoff)) {
                return new Admission(Outcome.LEGACY_BUSY, null);
            }
        }
        open.forEach(previous -> previous.markFailed("같은 원본에서 새 일정을 요청해 이 질문을 종료했어요."));
        legacy.forEach(previous -> previous.markFailed("24시간 동안 답변이 없어 이전 버전의 질문이 만료됐어요."));
        draft.recordRequest(key, hash);
        return new Admission(Outcome.CREATED, drafts.save(draft).getId());
    }

    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.HOURS)
    @Transactional
    public void clearExpiredKeys() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        drafts.clearExpiredRequestKeys(cutoff);
        drafts.clearExpiredReplayKeys(cutoff);
    }

    private static String requestHash(List<Long> jobIds, String message, Long sourceId) {
        String videos = jobIds.stream().distinct().sorted().map(String::valueOf).collect(Collectors.joining(","));
        String conditions = WHITESPACE.matcher(message).replaceAll(" ").trim();
        // 앞 두 필드는 숫자 목록과 원본 번호뿐이므로 조건에 줄바꿈이나 구분자가 있어도 모호하지 않다.
        String canonical = videos + "\n" + sourceId + "\n" + conditions;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
