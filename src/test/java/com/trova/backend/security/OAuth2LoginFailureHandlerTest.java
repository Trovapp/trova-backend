package com.trova.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuth2LoginFailureHandlerTest {

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private HttpSession session;

    private OAuth2LoginFailureHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OAuth2LoginFailureHandler();
        ReflectionTestUtils.setField(handler, "frontendUrl", "http://localhost:3000");
        ReflectionTestUtils.setField(handler, "mobileRedirectScheme", "trova");
    }

    @Test
    void 모바일_로그인_실패면_딥링크_에러로_리다이렉트한다() throws Exception {
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("MOBILE_LOGIN")).thenReturn(Boolean.TRUE);

        handler.onAuthenticationFailure(request, response, new BadCredentialsException("test"));

        verify(session).removeAttribute("MOBILE_LOGIN");
        verify(response).sendRedirect("trova://auth?error=oauth_failed");
    }

    @Test
    void 웹_로그인_실패면_기존처럼_프론트_URL로_리다이렉트한다() throws Exception {
        when(request.getSession(false)).thenReturn(null);

        handler.onAuthenticationFailure(request, response, new BadCredentialsException("test"));

        verify(response).sendRedirect("http://localhost:3000/login?error=oauth_failed");
    }
}
