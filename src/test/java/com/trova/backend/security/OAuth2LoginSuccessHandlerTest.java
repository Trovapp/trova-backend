package com.trova.backend.security;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuth2LoginSuccessHandlerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtService jwtService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private HttpSession session;

    private OAuth2LoginSuccessHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OAuth2LoginSuccessHandler(userRepository, jwtService);
        ReflectionTestUtils.setField(handler, "frontendUrl", "http://localhost:3000");
        ReflectionTestUtils.setField(handler, "mobileRedirectScheme", "trova");
    }

    private OAuth2AuthenticationToken tokenFor(String sub) {
        OAuth2User principal = new DefaultOAuth2User(
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")),
                Map.of("sub", sub, "name", "테스트", "picture", "https://example.com/p.jpg"),
                "sub"
        );
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
    }

    @Test
    void 모바일_로그인이면_JWT를_발급해서_딥링크로_리다이렉트한다() throws Exception {
        User user = new User("google", "42", "테스트", null);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("MOBILE_LOGIN")).thenReturn(Boolean.TRUE);
        when(userRepository.findByProviderAndProviderUserId("google", "42")).thenReturn(Optional.of(user));
        when(jwtService.issue(user)).thenReturn("jwt-token-value");

        handler.onAuthenticationSuccess(request, response, tokenFor("42"));

        verify(session).removeAttribute("MOBILE_LOGIN");
        verify(response).sendRedirect("trova://auth?token=jwt-token-value");
    }

    @Test
    void 웹_로그인이면_기존처럼_프론트_URL로_리다이렉트한다() throws Exception {
        when(request.getSession(false)).thenReturn(null);

        handler.onAuthenticationSuccess(request, response, tokenFor("42"));

        verify(response).sendRedirect("http://localhost:3000");
    }
}
