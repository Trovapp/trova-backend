package com.trova.backend.controller;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UsersControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private SavedPlaceRepository savedPlaceRepository;

    private ClientRegistration googleRegistration() {
        return ClientRegistration.withRegistrationId("google")
                .clientId("test-client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .build();
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor loginAs(String sub, String name) {
        return oauth2Login()
                .clientRegistration(googleRegistration())
                .attributes(attrs -> {
                    attrs.put("sub", sub);
                    attrs.put("name", name);
                    attrs.put("picture", "https://example.com/p.jpg");
                });
    }

    @Test
    void 인증되지_않은_요청은_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 마이페이지_기본_정보를_반환한다() throws Exception {
        userRepository.save(new User("google", "users-me-1", "마페유저", "https://example.com/photo.jpg"));

        mockMvc.perform(get("/api/users/me").with(loginAs("users-me-1", "마페유저")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("마페유저"))
                .andExpect(jsonPath("$.profileImageUrl").value("https://example.com/photo.jpg"))
                .andExpect(jsonPath("$.provider").value("google"));
    }

    @Test
    void 회원탈퇴하면_본인_데이터가_전부_삭제되고_204를_반환한다() throws Exception {
        User me = userRepository.save(new User("google", "withdraw-api-1", "탈퇴API유저", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/wapi", SourcePlatform.YOUTUBE));
        savedPlaceRepository.save(new SavedPlace(job, me, "탈퇴로 지워질 장소", null, "cafe", null, null));
        Long userId = me.getId();

        mockMvc.perform(delete("/api/users/me").with(loginAs("withdraw-api-1", "탈퇴API유저")))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(userId)).isEmpty();
    }
}
