package com.trova.backend.security;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.service.UserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 조회 API는 확인된 사용자 ID를 메모리에 기억한다(ADR 0036). 탈퇴가 커밋되면 기억에서 빠져
 * 탈퇴한 사용자의 토큰은 계속 401을 받아야 한다. 커밋 뒤 동작을 보려고 테스트 트랜잭션을 쓰지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KnownUsersWithdrawalIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private KnownUsers knownUsers;

    @Test
    void 탈퇴하면_기억된_사용자라도_조회_API가_401을_반환한다() throws Exception {
        User user = userRepository.save(new User("google", "known-users-withdraw", "탈퇴예정", null));
        String bearer = "Bearer " + jwtService.issue(user);
        for (String path : new String[] {"/api/places", "/api/places/pending", "/api/trips", "/api/bookmarks",
                "/api/bookmarks/folders", "/api/notifications", "/api/trip-drafts/auto"}) {
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk());
        }
        assertThat(knownUsers.contains(user.getId())).isTrue();

        userAccountService.withdraw(userRepository.findById(user.getId()).orElseThrow());

        assertThat(knownUsers.contains(user.getId())).isFalse();
        mockMvc.perform(get("/api/places").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isUnauthorized());
    }
}
