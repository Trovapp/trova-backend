package com.trova.backend.admin;

import com.trova.backend.entity.User;
import com.trova.backend.security.JwtAuthenticationToken;
import com.trova.backend.service.AdminAccessService;
import com.trova.backend.service.CurrentUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;

/**
 * 관리자 API 공통 권한 확인. 로그인하지 않은 요청은 보안 필터가 먼저 401로 막는다.
 * 관리자 웹 전용 토큰(aud=admin-web, 1시간)으로 온 요청만 받는다 — 관리자라도 앱 토큰(90일)·웹 세션이면 403(ADR 0012, #163).
 */
@Service
public class AdminGuard {

    private final CurrentUserService currentUserService;
    private final AdminAccessService adminAccessService;
    private final RequestRateLimiter rateLimiter;
    private final int adminApiPerMinute;

    public AdminGuard(CurrentUserService currentUserService, AdminAccessService adminAccessService,
                      RequestRateLimiter rateLimiter,
                      @Value("${app.admin.rate-limit.admin-api-per-minute:120}") int adminApiPerMinute) {
        this.currentUserService = currentUserService;
        this.adminAccessService = adminAccessService;
        this.rateLimiter = rateLimiter;
        this.adminApiPerMinute = adminApiPerMinute;
    }

    public User requireAdmin(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAdminWeb()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        User user = currentUserService.resolve(authentication);
        if (!adminAccessService.isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        // 관리자당 분당 횟수 제한 — 토큰이 새거나 화면이 폭주해도 운영 서버(E2.1.Micro)·DB를 지키려고(감으로 정함, 실측 아님).
        if (!rateLimiter.tryAcquire("admin:" + user.getId(), adminApiPerMinute)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        return user;
    }

    /** 실행 조치 전용 — requireAdmin 뒤에 부른다. */
    public void requireRecentLogin(Authentication authentication, Duration maxAge) {
        if (!(authentication instanceof JwtAuthenticationToken token) || token.getIssuedAt() == null
                || token.getIssuedAt().isBefore(Instant.now().minus(maxAge))) {
            throw new AdminReauthRequiredException();
        }
    }
}
