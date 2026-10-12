package com.trova.backend.security;

import com.trova.backend.admin.AdminWebLoginService;
import com.trova.backend.entity.User;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.WebAppLoginService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final CurrentUserService currentUserService;
    private final JwtService jwtService;
    private final AdminWebLoginService adminWebLoginService;
    private final WebAppLoginService webAppLoginService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Value("${app.mobile-redirect-scheme}")
    private String mobileRedirectScheme;

    public OAuth2LoginSuccessHandler(CurrentUserService currentUserService, JwtService jwtService,
                                     AdminWebLoginService adminWebLoginService, WebAppLoginService webAppLoginService) {
        this.currentUserService = currentUserService;
        this.jwtService = jwtService;
        this.adminWebLoginService = adminWebLoginService;
        this.webAppLoginService = webAppLoginService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException, ServletException {
        HttpSession session = request.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute("MOBILE_LOGIN"))) {
            session.removeAttribute("MOBILE_LOGIN");
            User user = currentUserService.resolve(authentication);
            String token = jwtService.issue(user);
            response.sendRedirect(mobileRedirectScheme + "://auth?token=" + token);
            return;
        }
        if (session != null && Boolean.TRUE.equals(session.getAttribute(MobileLoginFlagFilter.ADMIN_WEB_LOGIN))) {
            session.removeAttribute(MobileLoginFlagFilter.ADMIN_WEB_LOGIN);
            if (adminWebLoginService.enabled()) {
                // 관리자 웹은 1회용 코드 → 1시간 토큰만 쓴다. 세션을 지워 세션 쿠키 인증이 남지 않게 한다(#163).
                // 같은 브라우저에서 일반 웹에 세션으로 로그인돼 있었다면 그것도 함께 로그아웃된다 — 이 로그인으로 세션 인증이
                // 어차피 관리자 계정으로 바뀌므로 지우는 쪽이 안전하다. 기록 저장 등에서 예외가 나도 세션은 지운다.
                String redirect;
                try {
                    User user = currentUserService.resolve(authentication);
                    redirect = adminWebLoginService.loginRedirect(user);
                } finally {
                    session.invalidate();
                }
                response.sendRedirect(redirect);
                return;
            }
        }
        if (session != null && Boolean.TRUE.equals(session.getAttribute(MobileLoginFlagFilter.WEB_APP_LOGIN))) {
            session.removeAttribute(MobileLoginFlagFilter.WEB_APP_LOGIN);
            if (webAppLoginService.enabled()) {
                // PWA도 1회용 코드 → 토큰만 쓴다. 관리자 웹과 같은 이유로 세션을 지운다(#165).
                String redirect;
                try {
                    redirect = webAppLoginService.loginRedirect(currentUserService.resolve(authentication));
                } finally {
                    session.invalidate();
                }
                response.sendRedirect(redirect);
                return;
            }
        }
        response.sendRedirect(frontendUrl);
    }
}
