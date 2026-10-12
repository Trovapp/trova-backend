package com.trova.backend.admin;

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
 * 관리자 웹 로그인의 1회용 교환 코드(ADR 0012). 토큰을 주소창에 넣지 않으려고 로그인 뒤 이 코드만 넘기고, 웹이 바로 토큰으로 바꾼다.
 * 무작위 32바이트, 60초, 한 번만. 서버 1대라 메모리에 둔다 — 재시작하면 사라져 다시 로그인하면 된다.
 * PWA(웹앱) 로그인(#165)도 같은 저장소를 쓰되 용도를 함께 저장해, 관리자 웹 코드와 웹앱 코드를 서로 바꿔 쓸 수 없게 한다.
 */
@Component
public class AdminExchangeCodeStore {

    static final Duration TTL = Duration.ofSeconds(60);
    private static final int CODE_BYTES = 32;

    public enum Purpose { ADMIN_WEB, WEB_APP }

    private record Entry(Long userId, Purpose purpose, Instant expiresAt) {
    }

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> codes = new ConcurrentHashMap<>();

    public AdminExchangeCodeStore(Clock clock) {
        this.clock = clock;
    }

    public String issue(Long userId) {
        return issue(userId, Purpose.ADMIN_WEB);
    }

    public String issue(Long userId, Purpose purpose) {
        Instant now = clock.instant();
        codes.values().removeIf(e -> !e.expiresAt().isAfter(now));
        byte[] bytes = new byte[CODE_BYTES];
        random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        codes.put(code, new Entry(userId, purpose, now.plus(TTL)));
        return code;
    }

    public Optional<Long> consume(String code) {
        return consume(code, Purpose.ADMIN_WEB);
    }

    /** 코드를 쓰면 바로 지운다(한 번만, 용도가 달라도 지운다). 없거나 만료거나 용도가 다르면 빈 값. */
    public Optional<Long> consume(String code, Purpose purpose) {
        if (code == null) {
            return Optional.empty();
        }
        Entry entry = codes.remove(code);
        if (entry == null || entry.purpose() != purpose || !entry.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(entry.userId());
    }
}
