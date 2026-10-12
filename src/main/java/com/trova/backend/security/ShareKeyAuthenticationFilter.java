package com.trova.backend.security;

import com.trova.backend.admin.RequestRateLimiter;
import com.trova.backend.entity.ShareKey;
import com.trova.backend.service.ShareKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * iOS 단축어의 공유 전용 키(헤더 X-Trova-Share-Key)로 인증한다(ADR 0013, #165). 공유 저장(POST /api/shares)에서만 받고,
 * 다른 경로에서는 헤더를 보지 않아 인증되지 않는다(→ 401). 키당 분당 횟수를 제한하고(429), 하루 공유 한도는 기존대로 적용된다.
 */
@Component
public class ShareKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trova-Share-Key";
    private static final String SHARES_PATH = "/api/shares";

    private final ShareKeyService shareKeyService;
    private final RequestRateLimiter rateLimiter;
    private final int perMinute;

    public ShareKeyAuthenticationFilter(ShareKeyService shareKeyService, RequestRateLimiter rateLimiter,
                                        @Value("${app.share-key.per-minute:10}") int perMinute) {
        this.shareKeyService = shareKeyService;
        this.rateLimiter = rateLimiter;
        this.perMinute = perMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && SHARES_PATH.equals(request.getRequestURI())
                && request.getHeader(HEADER) != null);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<ShareKey> key = shareKeyService.authenticate(request.getHeader(HEADER));
        if (key.isPresent()) {
            // 감으로 정한 한도(실측 아님) — 단축어가 새거나 잘못 반복돼도 하루 한도를 순식간에 쓰지 않게.
            if (!rateLimiter.tryAcquire("share-key:" + key.get().getId(), perMinute)) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                return;
            }
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(key.get().getUserId()));
        }
        chain.doFilter(request, response);
    }
}
