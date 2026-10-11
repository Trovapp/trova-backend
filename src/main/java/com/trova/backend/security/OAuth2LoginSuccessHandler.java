package com.trova.backend.security;

import com.trova.backend.admin.AdminWebLoginService;
import com.trova.backend.entity.User;
import com.trova.backend.service.CurrentUserService;
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

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Value("${app.mobile-redirect-scheme}")
    private String mobileRedirectScheme;

    public OAuth2LoginSuccessHandler(CurrentUserService currentUserService, JwtService jwtService,
                                     AdminWebLoginService adminWebLoginService) {
        this.currentUserService = currentUserService;
        this.jwtService = jwtService;
        this.adminWebLoginService = adminWebLoginService;
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
                User user = currentUserService.resolve(authentication);
                String redirect = adminWebLoginService.loginRedirect(user);
                session.invalidate();
                response.sendRedirect(redirect);
                return;
            }
        }
        response.sendRedirect(frontendUrl);
    }
}
