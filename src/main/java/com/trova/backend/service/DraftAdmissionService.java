package com.trova.backend.service;

import com.trova.backend.entity.DeferredDraftRequest;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.entity.User;
import com.trova.backend.repository.DeferredDraftRequestRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.UserRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
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
    public record Replay(Long draftId, TripDraftStatus status) {}

    private static final Duration REPLAY_WINDOW = Duration.ofHours(24);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private final UserRepository users;
    private final TripDraftRepository drafts;
    private final TripPlannerService planner;
    private final DeferredDraftRequestRepository deferred;

    public DraftAdmissionService(UserRepository users, TripDraftRepository drafts, TripPlannerService planner,
                                 DeferredDraftRequestRepository deferred) {
        this.deferred = deferred;
        this.users = users;
        this.drafts = drafts;
        this.planner = planner;
    }

    @Transactional
    public Admission admit(Long userId, List<Long> jobIds, String message, Long sourceId, UUID key) {
        User user = lockUser(userId);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime replayCutoff = now.minus(REPLAY_WINDOW);
        Optional<Long> replay = findByKey(userId, key, replayCutoff);
        if (replay.isPresent()) {
            return new Admission(Outcome.REPLAY, replay.get());
        }
        List<TripDraft> open = sourceId == null ? List.of() : drafts.lockOpenReplacements(userId, sourceId);
        String hash = validatedHash(jobIds, message, sourceId);
        Optional<LocalDateTime> since = waitingSince(userId, key, hash, replayCutoff);
        Optional<Long> matching = Optional.empty();
        if (hash != null) {
            matching = since.isPresent() ? drafts.findLatestContent(userId, hash, since.get())
                    : drafts.findReplayableContent(userId, hash, replayCutoff, key == null);
        }
        if (matching.isPresent()) {
            Long draftId = matching.get();
            if (key != null) {
                bindKey(userId, draftId, key);
            }
            return new Admission(Outcome.REPLAY, draftId);
        }
        for (TripDraft previous : open) {
            if (previous.getStatus() == TripDraftStatus.PENDING || previous.getStatus() == TripDraftStatus.PROCESSING) {
                // 409 본문이 유실돼도 같은 입력의 재시도는 이 작업의 최초 202를 복구한다.
                if (key != null && previous.matchesRecentRequest(hash, replayCutoff)) {
                    bindKey(userId, previous.getId(), key);
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
                if (key != null && since.isEmpty()) {
                    deferred.clearKey(userId, key);
                    deferred.save(new DeferredDraftRequest(user, key, hash, now));
                }
                return new Admission(Outcome.LEGACY_BUSY, null);
            }
        }
        open.forEach(previous -> previous.markFailed("같은 원본에서 새 일정을 요청해 이 질문을 종료했어요."));
        legacy.forEach(previous -> previous.markFailed("24시간 동안 답변이 없어 이전 버전의 질문이 만료됐어요."));
        draft.recordRequest(key, hash);
        Long draftId = drafts.save(draft).getId();
        if (key != null) {
            deferred.clearKey(userId, key);
        }
        // 다른 기기의 승인·재접수 전에 이 입력으로 기다리던 모든 키를 최초 접수에 고정한다.
        deferred.linkWaitingKeys(userId, hash, replayCutoff, draftId);
        deferred.clearContent(userId, hash);
        return new Admission(Outcome.CREATED, draftId);
    }

    @Transactional
    public Optional<Replay> lookup(Long userId, List<Long> jobIds, String message, Long sourceId, UUID key) {
        lockUser(userId);
        LocalDateTime cutoff = LocalDateTime.now().minus(REPLAY_WINDOW);
        String hash = validatedHash(jobIds, message, sourceId);
        return findByKey(userId, key, cutoff)
                .or(() -> hash == null ? Optional.empty()
                        : drafts.findLatestContent(userId, hash, waitingSince(userId, key, hash, cutoff).orElse(cutoff)))
                .flatMap(drafts::findById)
                .map(draft -> new Replay(draft.getId(), draft.getStatus()));
    }

    private User lockUser(Long userId) {
        return users.lockById(userId)
                .orElseThrow(() -> new InsufficientAuthenticationException("사용자를 찾을 수 없습니다"));
    }

    private Optional<Long> findByKey(Long userId, UUID key, LocalDateTime cutoff) {
        return key == null ? Optional.empty() : drafts.findRecentRequest(userId, key, cutoff)
                .or(() -> drafts.findRecentReplayKey(userId, key, cutoff));
    }

    private Optional<LocalDateTime> waitingSince(Long userId, UUID key, String hash, LocalDateTime cutoff) {
        return key == null || hash == null ? Optional.empty() : deferred.waitingSince(userId, key, hash, cutoff);
    }

    private void bindKey(Long userId, Long draftId, UUID key) {
        deferred.clearKey(userId, key);
        drafts.addReplayKey(draftId, key);
    }

    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.HOURS)
    @Transactional
    public void clearExpiredKeys() {
        LocalDateTime cutoff = LocalDateTime.now().minus(REPLAY_WINDOW);
        deferred.clearExpired(cutoff);
        drafts.clearExpiredRequestKeys(cutoff);
        drafts.clearExpiredReplayKeys(cutoff);
    }

    private static String validatedHash(List<Long> jobIds, String message, Long sourceId) {
        if (!TripPlannerService.validDraftInput(jobIds, message)) {
            return null;
        }
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
