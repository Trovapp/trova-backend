package com.trova.backend.security;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JWT 인증 체인이 실제 Spring Security 필터 체인 + 실제 컨트롤러를 거쳐 끝까지
 * 동작하는지 검증한다. JwtService/JwtAuthenticationFilter/CurrentUserService는
 * 각각 단위 테스트가 있지만, "Authorization: Bearer <토큰>" 헤더가 실제로
 * 올바른 User로 해석되는 이음매(Task 6에서 8개 컨트롤러 파라미터 타입을 넓힌 지점)를
 * 검증하는 테스트는 없었다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JwtAuthenticationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Test
    void 유효한_JWT면_실제_컨트롤러가_해당_사용자로_인증한다() throws Exception {
        User user = userRepository.save(new User("google", "jwt-it-1", "JWT통합테스트", null));
        String token = jwtService.issue(user);

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()))
                .andExpect(jsonPath("$.nickname").value("JWT통합테스트"));
    }

    @Test
    void 위조된_JWT면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer garbage-not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 탈퇴한_사용자의_JWT면_401을_반환한다() throws Exception {
        // JWT는 서명 검증에는 통과하지만, User row가 이미 삭제된 경우(탈퇴)를 재현한다.
        // 고쳐지기 전에는 CurrentUserService가 IllegalStateException을 던져
        // 매핑하는 곳이 없어 500이 노출됐다 (findings #2).
        User user = userRepository.save(new User("google", "jwt-it-withdrawn", "탈퇴예정", null));
        String token = jwtService.issue(user);
        userRepository.delete(user);

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void Authorization_헤더도_세션도_없으면_401을_반환한다() throws Exception {
        // JWT 필터가 아무도 함부로 인증시키지 않는다는 베이스라인 확인.
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}
