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
        if (isCodeOnlyLogin(session)) {
            // 관리자 웹(#163)·PWA(#165)는 1회용 코드 → 토큰만 쓴다. 세션을 지워 세션 쿠키 인증이 남지 않게 한다.
            // 같은 브라우저에서 일반 웹에 세션으로 로그인돼 있었다면 그것도 함께 로그아웃된다 — 이 로그인으로 세션 인증이
            // 어차피 이 계정으로 바뀌므로 지우는 쪽이 안전하다. 사용자 확인·기록 저장 등 어디서 예외가 나도 세션은 지운다.
            String redirect;
            try {
                redirect = codeOnlyRedirect(session, currentUserService.resolve(authentication));
            } finally {
                session.invalidate();
            }
            response.sendRedirect(redirect);
            return;
        }
        User loginUser = currentUserService.resolve(authentication);
        if (loginUser.isSuspended()) {
            String redirect = suspendedRedirect(session);
            if (session != null) {
                session.invalidate();
            }
            response.sendRedirect(redirect);
            return;
        }
        if (session != null && Boolean.TRUE.equals(session.getAttribute("MOBILE_LOGIN"))) {
            session.removeAttribute("MOBILE_LOGIN");
            String token = jwtService.issue(loginUser);
            response.sendRedirect(mobileRedirectScheme + "://auth?token=" + token);
            return;
        }
        // 표시는 있지만 그 웹 주소가 설정되지 않은 경우 — 표시만 지우고 일반 웹 로그인처럼 보낸다.
        if (session != null) {
            session.removeAttribute(MobileLoginFlagFilter.ADMIN_WEB_LOGIN);
            session.removeAttribute(MobileLoginFlagFilter.WEB_APP_LOGIN);
        }
        response.sendRedirect(frontendUrl);
    }

    /** 앱 로그인이 아니고, 관리자 웹이나 PWA 표시가 있고 그 웹 주소가 설정돼 있으면 코드만 주는 로그인이다. */
    private boolean isCodeOnlyLogin(HttpSession session) {
        if (session == null || Boolean.TRUE.equals(session.getAttribute("MOBILE_LOGIN"))) {
            return false;
        }
        return (Boolean.TRUE.equals(session.getAttribute(MobileLoginFlagFilter.ADMIN_WEB_LOGIN))
                && adminWebLoginService.enabled())
                || (Boolean.TRUE.equals(session.getAttribute(MobileLoginFlagFilter.WEB_APP_LOGIN))
                && webAppLoginService.enabled());
    }

    private String codeOnlyRedirect(HttpSession session, User loginUser) {
        if (loginUser.isSuspended()) {
            return suspendedRedirect(session);
        }
        if (Boolean.TRUE.equals(session.getAttribute(MobileLoginFlagFilter.ADMIN_WEB_LOGIN))
                && adminWebLoginService.enabled()) {
            return adminWebLoginService.loginRedirect(loginUser);
        }
        return webAppLoginService.loginRedirect(loginUser);
    }

    // 정지 계정은 어느 로그인 흐름이든 토큰·코드를 주지 않고 그 흐름의 실패 주소로 보낸다(#167).
    private String suspendedRedirect(HttpSession session) {
        if (session != null && Boolean.TRUE.equals(session.getAttribute("MOBILE_LOGIN"))) {
            return mobileRedirectScheme + "://auth?error=suspended";
        }
        if (session != null && Boolean.TRUE.equals(session.getAttribute(MobileLoginFlagFilter.WEB_APP_LOGIN))
                && webAppLoginService.enabled()) {
            return webAppLoginService.suspendedRedirect();
        }
        if (session != null && Boolean.TRUE.equals(session.getAttribute(MobileLoginFlagFilter.ADMIN_WEB_LOGIN))
                && adminWebLoginService.enabled()) {
            return adminWebLoginService.failureRedirect();
        }
        return frontendUrl + "/login?error=suspended";
    }
}
