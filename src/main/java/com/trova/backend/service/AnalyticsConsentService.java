package com.trova.backend.service;

import com.trova.backend.entity.AnalyticsConsent;
import com.trova.backend.entity.AnalyticsDeletionJob;
import com.trova.backend.repository.AnalyticsConsentRepository;
import com.trova.backend.repository.AnalyticsDeletionJobRepository;
import com.trova.backend.repository.UserRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AnalyticsConsentService {
    public static final String VERSION = "analytics-2026-11-26-v1";
    private final UserRepository users;
    private final AnalyticsConsentRepository consents;
    private final AnalyticsDeletionJobRepository deletions;
    private final boolean noticeReady;

    public AnalyticsConsentService(UserRepository users, AnalyticsConsentRepository consents,
            AnalyticsDeletionJobRepository deletions, @Value("${app.analytics.notice-ready:false}") boolean noticeReady) {
        this.users = users;
        this.consents = consents;
        this.deletions = deletions;
        this.noticeReady = noticeReady;
    }

    public record Selection(String consentVersion, Long expectedRevision, Boolean usageConsent, Boolean transferConsent) {}
    public record State(String consentVersion, long revision, boolean usageConsent, boolean transferConsent,
                        UUID analyticsId, boolean noticeReady) {}

    @Transactional(readOnly = true)
    public State get(Long userId) {
        return state(consents.findById(userId).orElseGet(() -> new AnalyticsConsent(userId)));
    }

    @Transactional
    public State checkBatch(Long userId, long expectedRevision) {
        if (invalidRevision(expectedRevision)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        lockUser(userId);
        State current = get(userId);
        if (!current.noticeReady() || current.revision() != expectedRevision || !current.usageConsent()
                || !current.transferConsent() || current.analyticsId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        return current;
    }

    private boolean invalidRevision(long revision) {
        return revision < 0 || revision > 9_007_199_254_740_991L;
    }

    @Transactional
    public State put(Long userId, Selection request) {
        if (request == null || !VERSION.equals(request.consentVersion()) || request.expectedRevision() == null
                || invalidRevision(request.expectedRevision())
                || request.usageConsent() == null || request.transferConsent() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (!noticeReady && (request.usageConsent() || request.transferConsent())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        lockUser(userId);
        AnalyticsConsent consent = consents.findById(userId).orElseGet(() -> new AnalyticsConsent(userId));
        if (consent.getRevision() != request.expectedRevision()) {
            if (consent.isRetry(request.expectedRevision(), request.usageConsent(), request.transferConsent())) {
                return state(consent);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        if (!consent.matches(request.usageConsent(), request.transferConsent())) {
            if (!request.usageConsent() || !request.transferConsent()) enqueueDeletion(consent);
            consent.change(request.usageConsent(), request.transferConsent(), request.expectedRevision());
            consents.save(consent);
        }
        // 현재 인증에는 '이번 가입 흐름' 증거가 없다. 생성 시각만으로 가입을 추정하지 않는다.
        // 계약에 따라 signup은 판정 불가로 생략한다.
        return state(consent);
    }

    @Transactional
    public void revoke(Long userId) {
        lockUser(userId);
        consents.findById(userId).ifPresent(consent -> {
            if (!consent.matches(false, false)) {
                enqueueDeletion(consent);
                consent.change(false, false, null);
            }
        });
    }

    /** 호출자의 계정 삭제 트랜잭션이 잠금을 유지하므로 PUT과 탈퇴가 교차해 ID를 잃지 않는다. */
    @Transactional
    public void withdraw(Long userId) {
        lockUser(userId);
        consents.findById(userId).ifPresent(consent -> {
            enqueueDeletion(consent);
            consents.delete(consent);
        });
    }

    private void lockUser(Long userId) {
        users.lockById(userId).orElseThrow(() -> new InsufficientAuthenticationException("사용자를 찾을 수 없습니다"));
    }

    private void enqueueDeletion(AnalyticsConsent consent) {
        if (consent.getAnalyticsId() != null) {
            deletions.save(new AnalyticsDeletionJob(consent.getAnalyticsId()));
        }
    }

    private State state(AnalyticsConsent consent) {
        return new State(VERSION, consent.getRevision(), consent.isUsageConsent(), consent.isTransferConsent(),
                consent.getAnalyticsId(), noticeReady);
    }
}
