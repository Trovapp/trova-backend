package com.trova.backend.security;

import com.trova.backend.admin.AdminWebLoginService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Value("${app.mobile-redirect-scheme}")
    private String mobileRedirectScheme;

    private final AdminWebLoginService adminWebLoginService;

    public OAuth2LoginFailureHandler(AdminWebLoginService adminWebLoginService) {
        this.adminWebLoginService = adminWebLoginService;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                         AuthenticationException exception) throws IOException, ServletException {
        HttpSession session = request.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute("MOBILE_LOGIN"))) {
            session.removeAttribute("MOBILE_LOGIN");
            response.sendRedirect(mobileRedirectScheme + "://auth?error=oauth_failed");
            return;
        }
        if (session != null && Boolean.TRUE.equals(session.getAttribute(MobileLoginFlagFilter.ADMIN_WEB_LOGIN))) {
            session.removeAttribute(MobileLoginFlagFilter.ADMIN_WEB_LOGIN);
            if (adminWebLoginService.enabled()) {
                response.sendRedirect(adminWebLoginService.failureRedirect());
                return;
            }
        }
        response.sendRedirect(frontendUrl + "/login?error=oauth_failed");
    }
}
