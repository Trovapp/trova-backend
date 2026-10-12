package com.trova.backend.support;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** 관리자 API 테스트용 — google 로그인 사용자(sub)를 찾거나 만들어 관리자 웹 토큰(aud=admin-web)을 붙인다(#163). */
public final class AdminWebAuth {

    private AdminWebAuth() {
    }

    public static RequestPostProcessor adminWebToken(JwtService jwtService, UserRepository userRepository, String sub) {
        User user = userRepository.findByProviderAndProviderUserId("google", sub)
                .orElseGet(() -> userRepository.save(new User("google", sub, sub, null)));
        return bearer(jwtService.issueAdminWeb(user));
    }

    public static RequestPostProcessor appToken(JwtService jwtService, UserRepository userRepository, String sub) {
        User user = userRepository.findByProviderAndProviderUserId("google", sub)
                .orElseGet(() -> userRepository.save(new User("google", sub, sub, null)));
        return bearer(jwtService.issue(user));
    }

    private static RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }
}
