package com.trova.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * JWT 인증은 요청마다 토큰으로 다시 하므로 세션에 저장하지 않는다(#45). 저장하면 앱 요청 하나마다 쓰이지 않는
 * 세션이 새로 생겨 30분씩 메모리에 쌓였다(로컬 실측: 요청 1,000회 → 세션 1,000개).
 * 웹 프론트의 OAuth2 세션 로그인은 기존(Spring Security 기본값)처럼 요청 속성 + 세션에 저장한다.
 */
public class JwtSkippingSecurityContextRepository implements SecurityContextRepository {

    private final RequestAttributeSecurityContextRepository requestAttributes = new RequestAttributeSecurityContextRepository();
    private final SecurityContextRepository defaults = new DelegatingSecurityContextRepository(
            requestAttributes, new HttpSessionSecurityContextRepository());

    @Override
    @Deprecated
    public SecurityContext loadContext(HttpRequestResponseHolder requestResponseHolder) {
        return defaults.loadContext(requestResponseHolder);
    }

    @Override
    public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        return defaults.loadDeferredContext(request);
    }

    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        if (context.getAuthentication() instanceof JwtAuthenticationToken) {
            requestAttributes.saveContext(context, request, response);
            return;
        }
        defaults.saveContext(context, request, response);
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        return defaults.containsContext(request);
    }
}
