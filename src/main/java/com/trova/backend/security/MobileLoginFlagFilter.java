package com.trova.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class MobileLoginFlagFilter extends OncePerRequestFilter {

    // 모바일 앱이 OAuth 로그인을 시작할 때만 세션에 플래그를 세팅해야 한다.
    // addFilterBefore는 필터 체인 상의 "순서"만 정할 뿐 "어떤 경로에 적용될지"는
    // 정하지 않으므로, 이 매처로 /oauth2/authorization/** 요청만 걸러낸다.
    // (다른 경로에 mobile=true를 붙여 보내면 아무 동작도 하지 않아야 함 —
    // 그렇지 않으면 이미 로그인된 웹 세션에 플래그가 오염될 수 있다.)
    // PathPatternRequestMatcher(Spring Security 6+ 권장 방식)는 서블릿 컨테이너가
    // 채워주는 HttpServletMapping을 요구해서 순수 Mockito 목으로는 테스트가 불가능해
    // 여기서는 AntPathMatcher로 경로 문자열만 비교한다.
    // 주의: getRequestURI()는 컨텍스트 패스를 포함한 값을 반환한다. 지금은 컨텍스트 패스를
    // 설정하지 않아 문제가 없지만, 나중에 컨텍스트 패스가 생기면 이 매칭이 깨질 수 있다.
    private static final String MOBILE_OAUTH_START_PATTERN = "/oauth2/authorization/**";
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain
    ) throws ServletException, IOException {
        // 경로 매칭을 먼저 확인한다: getParameter()는 form-urlencoded 요청에서 요청 본문
        // 스트림을 소비할 수 있으므로, 이 필터와 무관한 경로에서는 아예 호출하지 않는다.
        if (PATH_MATCHER.match(MOBILE_OAUTH_START_PATTERN, request.getRequestURI())) {
            if ("true".equals(request.getParameter("mobile"))) {
                request.getSession(true).setAttribute("MOBILE_LOGIN", Boolean.TRUE);
            } else {
                // mobile=true 없이 재진입한 요청(웹 로그인 포함)에서는 이전에 모바일 로그인을
                // 시도했다가 중단한 세션에 남아있을 수 있는 플래그를 반드시 지운다. 그렇지
                // 않으면 이후 웹 로그인이 OAuth2LoginSuccessHandler에서 모바일 로그인으로
                // 오인되어 JWT가 딥링크 URL에 노출될 수 있다.
                HttpSession existingSession = request.getSession(false);
                if (existingSession != null) {
                    existingSession.removeAttribute("MOBILE_LOGIN");
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}
