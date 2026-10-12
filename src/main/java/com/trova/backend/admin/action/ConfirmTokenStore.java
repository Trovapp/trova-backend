package com.trova.backend.admin.action;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 관리자 조치의 확인 토큰. 미리보기 때 발급하고 실행 때 한 번만 꺼낸다.
 * 무작위 32바이트, 5분. 서버 1대라 메모리에 둔다 — 재시작하면 사라져 미리보기를 다시 하면 된다.
 */
@Component
public class ConfirmTokenStore {

    public static final Duration TTL = Duration.ofMinutes(5);
    private static final int TOKEN_BYTES = 32;

    public record Pending(Long adminUserId, AdminAction action, Long targetId, Long targetUserId, String confirmText) {
    }

    private record Entry(Pending pending, Instant expiresAt) {
    }

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();

    public ConfirmTokenStore(Clock clock) {
        this.clock = clock;
    }

    public String issue(Pending pending) {
        Instant now = clock.instant();
        tokens.values().removeIf(e -> !e.expiresAt().isAfter(now));
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(token, new Entry(pending, now.plus(TTL)));
        return token;
    }

    /** 꺼내면 바로 지운다(한 번만). 없거나 만료면 빈 값. */
    public Optional<Pending> consume(String token) {
        if (token == null) {
            return Optional.empty();
        }
        Entry entry = tokens.remove(token);
        if (entry == null || !entry.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(entry.pending());
    }
}
