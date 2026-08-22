package com.trova.backend.config;

import com.trova.backend.security.CustomOAuth2UserService;
import com.trova.backend.security.OAuth2LoginFailureHandler;
import com.trova.backend.security.OAuth2LoginSuccessHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;
    private final OAuth2LoginFailureHandler oAuth2LoginFailureHandler;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    public SecurityConfig(CustomOAuth2UserService customOAuth2UserService,
                           OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler,
                           OAuth2LoginFailureHandler oAuth2LoginFailureHandler) {
        this.customOAuth2UserService = customOAuth2UserService;
        this.oAuth2LoginSuccessHandler = oAuth2LoginSuccessHandler;
        this.oAuth2LoginFailureHandler = oAuth2LoginFailureHandler;
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
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/oauth2/**", "/login/**").permitAll()
                        .anyRequest().authenticated()
                )
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
                );

        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(frontendUrl));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        configuration.setAllowCredentials(true);
        configuration.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
