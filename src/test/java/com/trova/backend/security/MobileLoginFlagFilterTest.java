package com.trova.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
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
        when(request.getParameter("mobile")).thenReturn("true");
        when(request.getSession(true)).thenReturn(session);

        filter.doFilterInternal(request, response, chain);

        verify(session).setAttribute("MOBILE_LOGIN", Boolean.TRUE);
        verify(chain).doFilter(request, response);
    }

    @Test
    void mobile_파라미터가_없으면_세션을_건드리지_않는다() throws Exception {
        when(request.getParameter("mobile")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(session);
        verify(chain).doFilter(request, response);
    }
}
