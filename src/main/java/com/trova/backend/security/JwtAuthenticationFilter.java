package com.trova.backend.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ADMIN_API_PREFIX = "/api/admin/";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                JwtService.VerifiedToken verified = jwtService.parse(token);
                // 관리자 웹 토큰은 관리자 API에서만 인증으로 받는다 — 다른 API에서는 무시해 401(#163).
                if (!verified.adminWeb() || request.getRequestURI().startsWith(ADMIN_API_PREFIX)) {
                    SecurityContextHolder.getContext().setAuthentication(
                            new JwtAuthenticationToken(verified.userId(), verified.adminWeb(), verified.issuedAt()));
                }
            } catch (JwtException | IllegalArgumentException e) {
                // 검증 실패 시 그냥 미인증 상태로 다음 필터로 넘긴다 —
                // 기존 세션 인증 경로가 있으면 그쪽에서 처리되고,
                // 둘 다 없으면 결국 anyRequest().authenticated()에서 401.
                // 다만 완전히 삼키면 시크릿 로테이션/클라이언트 버그 같은 실제 문제가
                // 운영에서 안 보이게 되므로 debug 레벨로는 남긴다.
                log.debug("JWT verification failed: {}", e.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }
}
