package com.trova.backend.controller;

import com.trova.backend.entity.User;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import com.trova.backend.support.AdminWebAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * 새 관리자 API가 권한 확인을 빠뜨리지 않게, 등록된 모든 /api/admin/** 매핑을 훑어 비로그인 401·관리자 아님 403인지 본다(#159·#161).
 * 경로 변수는 1로 채운다. 관리자 API는 관리자 웹 토큰(aud=admin-web)만 받으므로 관리자라도 앱 토큰·웹 세션이면 403이다(#163).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.admin.users=google:sec-admin,google:sec-admin-session")
class AdminEndpointsSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    // 임베딩 백필(POST /api/admin/backfill-embeddings)은 외부 API를 부르므로 목 처리 — 권한 단계에서 막혀야 한다.
    @MockitoBean
    private PlaceEmbeddingService placeEmbeddingService;

    private record Endpoint(HttpMethod method, String path) {
    }

    private List<Endpoint> adminEndpoints() {
        List<Endpoint> endpoints = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/admin")) {
                    continue;
                }
                String path = pattern.replaceAll("\\{[^}]+}", "1");
                if (methods.isEmpty()) {
                    endpoints.add(new Endpoint(HttpMethod.GET, path));
                }
                for (RequestMethod m : methods) {
                    endpoints.add(new Endpoint(HttpMethod.valueOf(m.name()), path));
                }
            }
        }
        return endpoints;
    }

    private RequestPostProcessor sessionLoginAs(String sub) {
        ClientRegistration google = ClientRegistration.withRegistrationId("google")
                .clientId("test-client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .build();
        return oauth2Login().clientRegistration(google).attributes(attrs -> {
            attrs.put("sub", sub);
            attrs.put("name", sub);
            attrs.put("picture", "https://example.com/p.jpg");
        });
    }

    @Test
    void 모든_관리자_API는_비로그인이면_401_관리자가_아니면_403이다() throws Exception {
        userRepository.save(new User("google", "sec-normal", "일반", null));
        userRepository.save(new User("google", "sec-admin-session", "세션 관리자", null));
        List<Endpoint> endpoints = adminEndpoints();
        // 지금 있는 것: ai-metrics, backfill-embeddings, dashboard 5개 — 매핑을 하나도 못 찾는 실수를 막는다.
        assertThat(endpoints).hasSizeGreaterThanOrEqualTo(7);

        for (Endpoint e : endpoints) {
            int anonymous = status(request(e.method(), e.path()));
            int normal = status(request(e.method(), e.path())
                    .with(AdminWebAuth.adminWebToken(jwtService, userRepository, "sec-normal")));
            int adminAppToken = status(request(e.method(), e.path())
                    .with(AdminWebAuth.appToken(jwtService, userRepository, "sec-admin")));
            int adminSession = status(request(e.method(), e.path()).with(sessionLoginAs("sec-admin-session")));
            assertThat(anonymous).as("비로그인 %s %s", e.method(), e.path()).isEqualTo(401);
            assertThat(normal).as("관리자 아님 %s %s", e.method(), e.path()).isEqualTo(403);
            assertThat(adminAppToken).as("관리자 + 앱 토큰 %s %s", e.method(), e.path()).isEqualTo(403);
            assertThat(adminSession).as("관리자 + 웹 세션 %s %s", e.method(), e.path()).isEqualTo(403);
            // 관리자 웹 토큰이면 통과하는지는 읽기(GET)만 본다 — POST(임베딩 백필 등)는 실제로 실행되므로 각 API 테스트에서 확인.
            if (e.method() == HttpMethod.GET) {
                int adminWebToken = status(request(e.method(), e.path())
                        .with(AdminWebAuth.adminWebToken(jwtService, userRepository, "sec-admin")));
                assertThat(adminWebToken).as("관리자 + 관리자 웹 토큰 %s %s", e.method(), e.path()).isNotIn(401, 403);
            }
        }
    }

    private int status(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder).andReturn().getResponse().getStatus();
    }
}
