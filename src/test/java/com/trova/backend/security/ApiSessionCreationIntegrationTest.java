package com.trova.backend.security;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 요청마다 서버 세션이 새로 생겨 30분씩 메모리에 쌓였다(#45, 로컬 실측: 요청 1,000회 → 세션 1,000개).
 * 앱(JWT)과 비로그인 요청은 세션을 만들지 않아야 하고, 웹 프론트의 세션 쿠키 인증은 그대로 동작해야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiSessionCreationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    @Test
    void JWT_요청은_세션을_만들지_않는다() throws Exception {
        User user = userRepository.save(new User("google", "session-jwt-1", "JWT", null));

        MvcResult result = mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(user)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void 로그인_안_된_API_요청은_401이고_세션을_만들지_않는다() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/trips"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void 웹의_세션_쿠키_인증은_그대로_동작한다() throws Exception {
        User user = userRepository.save(new User("google", "session-web-1", "웹유저", null));
        DefaultOAuth2User principal = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of("sub", "session-web-1", "name", "웹유저"), "sub");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google")));

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()));
    }
}
