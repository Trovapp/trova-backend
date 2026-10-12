package com.trova.backend.config;

import com.trova.backend.security.CustomOAuth2UserService;
import com.trova.backend.security.JwtAuthenticationFilter;
import com.trova.backend.security.JwtSkippingSecurityContextRepository;
import com.trova.backend.security.MobileLoginFlagFilter;
import com.trova.backend.security.OAuth2LoginFailureHandler;
import com.trova.backend.security.OAuth2LoginSuccessHandler;
import com.trova.backend.security.ShareKeyAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;
    private final OAuth2LoginFailureHandler oAuth2LoginFailureHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final MobileLoginFlagFilter mobileLoginFlagFilter;
    private final ShareKeyAuthenticationFilter shareKeyAuthenticationFilter;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    // 관리자 웹(Cloudflare Pages, #163). 비어 있으면 CORS에 더하지 않는다.
    @Value("${app.admin-web-url:}")
    private String adminWebUrl;

    // PWA 웹앱(Cloudflare Pages, #165). 비어 있으면 CORS에 더하지 않는다.
    @Value("${app.web-app-url:}")
    private String webAppUrl;

    // API 문서(#149)를 켰을 때만 문서 경로를 로그인 없이 연다. 꺼져 있으면 다른 경로와 똑같이 로그인이 필요하다.
    @Value("${springdoc.api-docs.enabled:false}")
    private boolean apiDocsEnabled;

    public SecurityConfig(CustomOAuth2UserService customOAuth2UserService,
                           OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler,
                           OAuth2LoginFailureHandler oAuth2LoginFailureHandler,
                           JwtAuthenticationFilter jwtAuthenticationFilter,
                           MobileLoginFlagFilter mobileLoginFlagFilter,
                           ShareKeyAuthenticationFilter shareKeyAuthenticationFilter) {
        this.customOAuth2UserService = customOAuth2UserService;
        this.oAuth2LoginSuccessHandler = oAuth2LoginSuccessHandler;
        this.oAuth2LoginFailureHandler = oAuth2LoginFailureHandler;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.mobileLoginFlagFilter = mobileLoginFlagFilter;
        this.shareKeyAuthenticationFilter = shareKeyAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 세션 쿠키 기반 API + 별도 origin 프론트 조합이라 CSRF는 비활성화한 상태다.
                // 단, CSRF 설정자가 없으면 LogoutConfigurer가 로그아웃 매처를 POST뿐 아니라
                // GET/PUT/DELETE까지 허용하도록 폴백하므로, 타 사이트의 <img src> 한 줄로도
                // 강제 로그아웃이 가능해진다. 그래서 아래 logout 설정에서 매처를 POST로 명시 고정한다.
                //
                // 재검토 결과(POST /api/shares, DELETE /api/places/{id} 추가 후):
                // 세션 쿠키가 SameSite=Lax(application.yml에 명시)이고 CORS도 frontendUrl
                // 단일 origin 화이트리스트라, 상태 변경 요청은 (1) 크로스사이트 form POST면
                // Lax가 쿠키 전송을 막고, (2) JSON body라 브라우저가 preflight를 강제하는데
                // 허용 안 된 origin이면 preflight에서 막힌다 — 두 계층 모두 뚫려야 성립하는
                // 공격이라 CSRF 비활성화 유지로 결론. 단, 이후 SameSite=None이 필요해지는
                // 상황(임베드/크로스사이트 쿠키 요구 등)이 생기면 이 판단은 무효이므로
                // 그때 반드시 다시 검토할 것.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                // 세션은 웹 프론트의 OAuth2 로그인에만 쓴다 — 앱(JWT) 요청과 비로그인 API 요청이 요청마다 세션을
                // 만들어 메모리에 쌓이던 문제(#45). 로그인 성공 후엔 항상 프론트로 보내므로 "돌아갈 요청" 저장도 필요 없다.
                .securityContext(context -> context.securityContextRepository(new JwtSkippingSecurityContextRepository()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .authorizeHttpRequests(auth -> {
                    // 오류 응답 만들기(/error로 다시 넘김)는 인증을 다시 보지 않는다 — 막으면 로그인 없이 부르는 교환 API의
                    // 400·429가 모두 401로 바뀐다(#163). 원래 요청의 권한 확인은 이미 끝난 뒤라 데이터가 새지 않는다.
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                    auth.requestMatchers("/oauth2/**", "/login/**").permitAll()
                            // 관리자 웹 1회용 코드 교환 — 코드 자체가 인증이다(AdminWebLoginService, #163).
                            .requestMatchers(HttpMethod.POST, "/api/auth/exchange").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/auth/web-exchange").permitAll()
                            // 인증 없이 열어두되, 실제로 노출되는 엔드포인트는 application.yml의
                            // management.endpoints.web.exposure.include가 정한다 — 기본은 health만이고
                            // prometheus는 로컬에서 ACTUATOR_EXPOSE로 켤 때만 노출된다(#27).
                            .requestMatchers("/actuator/**").permitAll();
                    if (apiDocsEnabled) {
                        auth.requestMatchers("/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                        .successHandler(oAuth2LoginSuccessHandler)
                        .failureHandler(oAuth2LoginFailureHandler)
                )
                .logout(logout -> logout
                        .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults()
                                .matcher(HttpMethod.POST, "/api/auth/logout"))
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.OK.value()))
                )
                .exceptionHandling(exception -> exception
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                PathPatternRequestMatcher.pathPattern("/api/**")
                        )
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(shareKeyAuthenticationFilter, JwtAuthenticationFilter.class)
                .addFilterBefore(mobileLoginFlagFilter, OAuth2AuthorizationRequestRedirectFilter.class);

        return http.build();
    }

    // JwtAuthenticationFilter/MobileLoginFlagFilter는 @Component가 붙은 OncePerRequestFilter라
    // Spring Boot가 서블릿 컨테이너에 일반 필터로도 자동 등록해버린다(ServletContextInitializerBeans).
    // 이 자동 등록은 지금은 securityFilterChain(순서 -100)이 먼저 실행되며 OncePerRequestFilter의
    // "이미 처리됨" 마커를 세팅해줘서 우연히 무해하지만, 필터 순서가 바뀌면 아무 증상 없이
    // 인증이 조용히 깨질 수 있다. 아래 두 빈으로 자동 등록을 꺼서 .addFilterBefore(...)로
    // 등록한 시큐리티 체인 안에서만 실행되게 한다.
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ShareKeyAuthenticationFilter> shareKeyAuthenticationFilterRegistration(
            ShareKeyAuthenticationFilter filter) {
        FilterRegistrationBean<ShareKeyAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<MobileLoginFlagFilter> mobileLoginFlagFilterRegistration(
            MobileLoginFlagFilter filter) {
        FilterRegistrationBean<MobileLoginFlagFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cookieWeb = corsFor(frontendUrl, true);
        String adminOrigin = origin(adminWebUrl);
        String webAppOrigin = origin(webAppUrl);
        CorsConfiguration adminWeb = adminOrigin == null ? null : corsFor(adminOrigin, false);
        CorsConfiguration webApp = webAppOrigin == null ? null : corsFor(webAppOrigin, false);
        // 요청을 보낸 주소(Origin)로 설정을 고른다. 쿠키를 싣는 일반 API는 frontendUrl 하나만이라는 CSRF 판단 전제를 지키고,
        // Bearer 토큰만 쓰는 관리자 웹(#163)과 PWA 웹앱(#165)은 쿠키 없이 연다. 관리자 웹은 관리자 API와 교환에만,
        // 웹앱은 관리자 API를 뺀 /api 전체에. 그 밖의 경우는 기본 설정(frontendUrl만 허용)이 다른 주소를 막는다.
        return request -> {
            String origin = request.getHeader("Origin");
            String path = request.getRequestURI();
            if (adminWeb != null && adminOrigin.equals(origin)
                    && (path.startsWith("/api/admin/") || path.equals("/api/auth/exchange"))) {
                return adminWeb;
            }
            if (webApp != null && webAppOrigin.equals(origin) && path.startsWith("/api/") && !path.startsWith("/api/admin/")) {
                return webApp;
            }
            return cookieWeb;
        };
    }

    private static CorsConfiguration corsFor(String origin, boolean credentials) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(origin));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(credentials);
        return configuration;
    }

    /** 설정한 주소의 출처(끝 / 없이). 비어 있으면 null. */
    private static String origin(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
