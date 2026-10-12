package com.trova.backend.security;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.service.ShareKeyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SuspendedUserAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private SuspendedUsers suspendedUsers;

    @Autowired
    private ShareKeyService shareKeyService;

    private User user;

    @AfterEach
    void tearDown() {
        if (user != null) {
            suspendedUsers.remove(user.getId());
        }
    }

    @Test
    void 정지하면_이미_받은_앱_토큰도_다음_요청부터_401() throws Exception {
        user = userRepository.save(new User("google", "suspend-jwt", "정지", null));
        String token = jwtService.issue(user);
        mockMvc.perform(get("/api/places").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        suspendedUsers.add(user.getId());

        mockMvc.perform(get("/api/places").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void 정지하면_단축어_키로도_저장할_수_없다() throws Exception {
        user = userRepository.save(new User("google", "suspend-key", "정지", null));
        String key = shareKeyService.issue(user.getId()).key();
        suspendedUsers.add(user.getId());

        mockMvc.perform(post("/api/shares").header(ShareKeyAuthenticationFilter.HEADER, key)
                        .contentType("application/json").content("{\"url\":\"https://www.youtube.com/shorts/abc\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 서버가_뜰_때_DB의_정지_계정을_목록에_싣는다() {
        user = userRepository.save(new User("google", "suspend-load", "정지", null));
        user.suspend();
        userRepository.save(user);

        suspendedUsers.reload();

        assertThat(suspendedUsers.isSuspended(user.getId())).isTrue();
    }
}
