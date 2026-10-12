package com.trova.backend.service;

import com.trova.backend.admin.AdminExchangeCodeStore;
import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * PWA(웹앱) 로그인(ADR 0013, #165): OAuth 로그인 성공 → 1회용 코드를 붙여 웹앱 콜백으로 → 웹앱이 코드를 앱 토큰으로 바꾼다.
 * 관리자 웹 로그인(AdminWebLoginService)과 같은 방식이고 쿠키를 쓰지 않는다. 앱과 같은 토큰(90일)을 준다.
 */
@Service
public class WebAppLoginService {

    public record WebAppToken(String token) {
    }

    // 정적 사이트(trailingSlash)라 끝에 /를 붙인다 — 호스팅이 /로 다시 보내며 ?code=를 잃지 않게.
    private static final String CALLBACK_PATH = "/auth/callback/";

    private final AdminExchangeCodeStore codeStore;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final String webAppUrl;

    public WebAppLoginService(AdminExchangeCodeStore codeStore, UserRepository userRepository, JwtService jwtService,
                              @Value("${app.web-app-url:}") String webAppUrl) {
        this.codeStore = codeStore;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        String url = webAppUrl == null ? "" : webAppUrl.trim();
        this.webAppUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 웹앱 주소(APP_WEB_APP_URL)가 없으면 웹앱 로그인은 꺼진다. */
    public boolean enabled() {
        return !webAppUrl.isEmpty();
    }

    public String loginRedirect(User user) {
        return webAppUrl + CALLBACK_PATH + "?code=" + codeStore.issue(user.getId(), AdminExchangeCodeStore.Purpose.WEB_APP);
    }

    public String failureRedirect() {
        return webAppUrl + CALLBACK_PATH + "?error=oauth_failed";
    }

    /** 없는·만료·이미 쓴·다른 용도의 코드는 400. */
    @Transactional(readOnly = true)
    public WebAppToken exchange(String code) {
        Long userId = codeStore.consume(code, AdminExchangeCodeStore.Purpose.WEB_APP)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못되었거나 만료된 코드입니다"));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못되었거나 만료된 코드입니다"));
        return new WebAppToken(jwtService.issue(user));
    }
}
