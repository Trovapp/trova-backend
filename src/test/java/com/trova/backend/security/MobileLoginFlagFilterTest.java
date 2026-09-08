package com.trova.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MobileLoginFlagFilterTest {

    private final MobileLoginFlagFilter filter = new MobileLoginFlagFilter();

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain chain;
    @Mock
    private HttpSession session;

    @Test
    void mobile_파라미터가_true면_세션에_플래그를_저장한다() throws Exception {
        when(request.getRequestURI()).thenReturn("/oauth2/authorization/google");
        when(request.getParameter("mobile")).thenReturn("true");
        when(request.getSession(true)).thenReturn(session);

        filter.doFilterInternal(request, response, chain);

        verify(session).setAttribute("MOBILE_LOGIN", Boolean.TRUE);
        verify(chain).doFilter(request, response);
    }

    @Test
    void mobile_파라미터가_없고_기존_세션도_없으면_아무_것도_하지_않는다() throws Exception {
        when(request.getRequestURI()).thenReturn("/oauth2/authorization/google");
        when(request.getParameter("mobile")).thenReturn(null);
        when(request.getSession(false)).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(session);
        verify(chain).doFilter(request, response);
    }

    @Test
    void oauth2_authorization_경로가_아니면_mobile이_true여도_세션을_건드리지_않는다() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/places");

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(session);
        // 경로가 매칭되지 않으면 getParameter("mobile")조차 호출하지 않아야 한다
        // (form-urlencoded 요청 본문 스트림을 불필요하게 건드리지 않기 위함).
        verify(request, never()).getParameter("mobile");
        verify(chain).doFilter(request, response);
    }

    @Test
    void mobile_파라미터_없이_oauth2_authorization에_요청하면_기존_세션의_MOBILE_LOGIN_플래그를_지운다() throws Exception {
        // 모바일 로그인을 시작했다가 중단한 세션(MOBILE_LOGIN=true가 이미 세팅됨)에서
        // 이어서 웹 로그인(mobile 파라미터 없음)을 시도하는 경우를 재현한다. 이 경우
        // 플래그가 지워지지 않으면 OAuth2LoginSuccessHandler가 웹 로그인을 모바일
        // 로그인으로 착각해 90일짜리 JWT를 딥링크 URL에 담아 리다이렉트하게 된다.
        when(request.getRequestURI()).thenReturn("/oauth2/authorization/kakao");
        when(request.getParameter("mobile")).thenReturn(null);
        when(request.getSession(false)).thenReturn(session);

        filter.doFilterInternal(request, response, chain);

        verify(session).removeAttribute("MOBILE_LOGIN");
        verify(chain).doFilter(request, response);
    }
}
