package com.trova.backend.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;
import java.util.List;

public class JwtAuthenticationToken extends AbstractAuthenticationToken {

    private final Long userId;
    private final boolean adminWeb;
    private final Instant issuedAt;

    public JwtAuthenticationToken(Long userId) {
        this(userId, false, null);
    }

    /** adminWeb: 관리자 웹 전용 토큰(aud=admin-web)으로 인증됐는지 — 관리자 API는 이 경우만 받는다(#163). */
    public JwtAuthenticationToken(Long userId, boolean adminWeb) {
        this(userId, adminWeb, null);
    }

    /** issuedAt: 토큰 발급 시각 — 관리자 실행 조치의 10분 재인증에 쓴다(#167). */
    public JwtAuthenticationToken(Long userId, boolean adminWeb, Instant issuedAt) {
        super(List.of(new SimpleGrantedAuthority("ROLE_USER")));
        this.userId = userId;
        this.adminWeb = adminWeb;
        this.issuedAt = issuedAt;
        setAuthenticated(true);
    }

    public Long getUserId() {
        return userId;
    }

    public boolean isAdminWeb() {
        return adminWeb;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return userId;
    }
}
