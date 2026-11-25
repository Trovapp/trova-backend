package com.trova.backend.config;

import com.trova.backend.admin.RequestRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import com.trova.backend.controller.ApiErrors;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * IP별 분당 요청 수 제한(#239) — 로그인 전 요청이 한 곳에서 몰려도 작은 서버(E2.1.Micro, 메모리 1GB)가 버티게,
 * 인증·DB 조회 전에 429로 끊는다. Spring Security보다 먼저 돈다.
 * IP는 Caddy가 넘긴 X-Forwarded-For를 Tomcat이 반영한 값(forward-headers-strategy). 서버 안에서 직접 부르는
 * 측정(127.0.0.1)은 빼고, 기준은 앱 폴링(5초)보다 넉넉하게 감으로 정했다(실측 아님) — 0이면 끈다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class IpRateLimitFilter extends OncePerRequestFilter {

    private final RequestRateLimiter rateLimiter;
    private final int perMinute;

    public IpRateLimitFilter(RequestRateLimiter rateLimiter,
                             @Value("${app.rate-limit.ip-per-minute:300}") int perMinute) {
        this.rateLimiter = rateLimiter;
        this.perMinute = perMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return perMinute <= 0 || isLoopback(request.getRemoteAddr());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (rateLimiter.tryAcquire("ip:" + request.getRemoteAddr(), perMinute)) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Retry-After", "60");
        ApiErrors.write(response, 429, Map.of("message", "요청이 너무 많아요. 1분 뒤 다시 시도해 주세요."));
    }

    private static boolean isLoopback(String address) {
        try {
            // 숫자 주소만 오므로 DNS 조회는 일어나지 않는다.
            return address != null && InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
