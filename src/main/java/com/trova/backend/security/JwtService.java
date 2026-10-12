package com.trova.backend.security;

import com.trova.backend.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtService {

    private static final Duration DEFAULT_EXPIRY = Duration.ofDays(90);
    // 관리자 웹 전용 토큰(ADR 0012, #163) — 새도 피해가 짧게 1시간. 앱 토큰과 섞이지 않게 aud로 구분한다.
    public static final String ADMIN_WEB_AUDIENCE = "admin-web";
    public static final Duration ADMIN_WEB_EXPIRY = Duration.ofHours(1);

    /** 검증된 토큰 — 사용자 내부 번호, 관리자 웹 토큰인지, 만료 시각. */
    public record VerifiedToken(Long userId, boolean adminWeb, Instant expiresAt, Instant issuedAt) {
    }

    private final SecretKey key;
    private final Duration expiry;

    @Autowired
    public JwtService(@Value("${app.jwt-secret}") String secret) {
        this(secret, DEFAULT_EXPIRY);
    }

    JwtService(String secret, Duration expiry) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiry = expiry;
    }

    public String issue(User user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiry.toMillis()))
                .signWith(key)
                .compact();
    }

    public String issueAdminWeb(User user) {
        return issueAdminWebAt(user, Instant.now());
    }

    /** 발급 시각을 정해 관리자 웹 토큰을 만든다 — 테스트·재인증 확인용. */
    public String issueAdminWebAt(User user, Instant issuedAt) {
        Date now = Date.from(issuedAt);
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .audience().add(ADMIN_WEB_AUDIENCE).and()
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ADMIN_WEB_EXPIRY.toMillis()))
                .signWith(key)
                .compact();
    }

    public Long verify(String token) {
        return parse(token).userId();
    }

    public VerifiedToken parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        boolean adminWeb = claims.getAudience() != null && claims.getAudience().contains(ADMIN_WEB_AUDIENCE);
        // 예전 토큰은 iat가 없을 수 있다.
        Instant issuedAt = claims.getIssuedAt() == null ? null : claims.getIssuedAt().toInstant();
        return new VerifiedToken(Long.valueOf(claims.getSubject()), adminWeb, claims.getExpiration().toInstant(), issuedAt);
    }
}
