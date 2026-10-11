package com.trova.backend.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

public class JwtAuthenticationToken extends AbstractAuthenticationToken {

    private final Long userId;
    private final boolean adminWeb;

    public JwtAuthenticationToken(Long userId) {
        this(userId, false);
    }

    /** adminWeb: 관리자 웹 전용 토큰(aud=admin-web)으로 인증됐는지 — 관리자 API는 이 경우만 받는다(#163). */
    public JwtAuthenticationToken(Long userId, boolean adminWeb) {
        super(List.of(new SimpleGrantedAuthority("ROLE_USER")));
        this.userId = userId;
        this.adminWeb = adminWeb;
        setAuthenticated(true);
    }

    public Long getUserId() {
        return userId;
    }

    public boolean isAdminWeb() {
        return adminWeb;
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
