package com.trova.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-jwt-secret-please-do-not-use-in-production-environment";

    private final JwtService jwtService = new JwtService(SECRET);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService);

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain chain;

    @Test
    void 유효한_Bearer_토큰이면_SecurityContext에_인증정보를_세팅한다() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer " + tokenFor(7L));

        filter.doFilterInternal(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isInstanceOf(JwtAuthenticationToken.class);
        assertThat(((JwtAuthenticationToken) auth).getUserId()).isEqualTo(7L);
        verify(chain).doFilter(request, response);
        SecurityContextHolder.clearContext();
    }

    @Test
    void Authorization_헤더가_없으면_그냥_다음_필터로_넘어간다() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void 위조된_토큰이면_인증정보를_세팅하지_않고_다음_필터로_넘어간다() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer garbage-token");

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    private String tokenFor(Long userId) {
        com.trova.backend.entity.User user = new com.trova.backend.entity.User("google", "42", "테스트", null);
        try {
            java.lang.reflect.Field field = user.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, userId);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return jwtService.issue(user);
    }
}
