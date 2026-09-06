package com.trova.backend.security;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final JwtService jwtService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Value("${app.mobile-redirect-scheme}")
    private String mobileRedirectScheme;

    public OAuth2LoginSuccessHandler(UserRepository userRepository, JwtService jwtService) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException, ServletException {
        HttpSession session = request.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute("MOBILE_LOGIN"))) {
            session.removeAttribute("MOBILE_LOGIN");
            OAuth2AuthenticationToken oauth2Token = (OAuth2AuthenticationToken) authentication;
            OAuth2UserInfo info = OAuth2UserInfo.of(
                    oauth2Token.getAuthorizedClientRegistrationId(),
                    oauth2Token.getPrincipal().getAttributes()
            );
            User user = userRepository.findByProviderAndProviderUserId(info.provider(), info.providerUserId())
                    .orElseThrow(() -> new IllegalStateException(
                            "인증된 사용자를 찾을 수 없습니다: " + info.provider() + " " + info.providerUserId()));
            String token = jwtService.issue(user);
            response.sendRedirect(mobileRedirectScheme + "://auth?token=" + token);
            return;
        }
        response.sendRedirect(frontendUrl);
    }
}
