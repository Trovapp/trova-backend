package com.trova.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

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
                Long userId = jwtService.verify(token);
                SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(userId));
            } catch (RuntimeException ignored) {
                // 검증 실패 시 그냥 미인증 상태로 다음 필터로 넘긴다 —
                // 기존 세션 인증 경로가 있으면 그쪽에서 처리되고,
                // 둘 다 없으면 결국 anyRequest().authenticated()에서 401.
            }
        }
        filterChain.doFilter(request, response);
    }
}
