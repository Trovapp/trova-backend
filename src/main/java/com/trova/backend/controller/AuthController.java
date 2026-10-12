package com.trova.backend.controller;

import com.trova.backend.admin.AdminWebLoginService;
import com.trova.backend.admin.RequestRateLimiter;
import com.trova.backend.entity.User;
import com.trova.backend.service.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AuthController {

    private final CurrentUserService currentUserService;
    private final AdminWebLoginService adminWebLoginService;
    private final RequestRateLimiter rateLimiter;
    private final int exchangePerMinute;

    public AuthController(CurrentUserService currentUserService, AdminWebLoginService adminWebLoginService,
                          RequestRateLimiter rateLimiter,
                          @Value("${app.admin.rate-limit.exchange-per-minute:10}") int exchangePerMinute) {
        this.currentUserService = currentUserService;
        this.adminWebLoginService = adminWebLoginService;
        this.rateLimiter = rateLimiter;
        this.exchangePerMinute = exchangePerMinute;
    }

    @GetMapping("/api/auth/me")
    public ResponseEntity<MeResponse> me(Authentication authentication) {
        User user = currentUserService.resolve(authentication);

        return ResponseEntity.ok(new MeResponse(user.getId(), user.getNickname(), user.getProfileImageUrl()));
    }

    public record MeResponse(Long id, String nickname, String profileImageUrl) {
    }

    /**
     * 관리자 웹 로그인의 1회용 코드를 1시간 관리자 웹 토큰으로 바꾼다(#163). 로그인 없이 부르는 API라 IP당 분당 횟수를 제한한다
     * (코드 무작위 대입 방지). IP는 운영에서 Caddy가 넘긴 X-Forwarded-For를 Tomcat이 반영한 값(forward-headers-strategy).
     */
    @PostMapping("/api/auth/exchange")
    public AdminWebLoginService.AdminWebToken exchange(@RequestBody ExchangeRequest request, HttpServletRequest http) {
        if (!rateLimiter.tryAcquire("exchange:" + http.getRemoteAddr(), exchangePerMinute)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        return adminWebLoginService.exchange(request.code());
    }

    public record ExchangeRequest(String code) {
    }
}
