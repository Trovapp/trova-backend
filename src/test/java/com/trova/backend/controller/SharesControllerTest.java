package com.trova.backend.controller;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.service.PlaceExtractionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.doThrow;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SharesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @MockitoBean
    private PlaceExtractionService placeExtractionService;

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

    @Test
    void URL을_받으면_작업을_생성하고_202를_반환한다() throws Exception {
        userRepository.save(new User("google", "1234567890", "테스트유저", null));
        doNothing().when(placeExtractionService).process(anyLong());

        mockMvc.perform(post("/api/shares")
                        .with(oauth2Login()
                                .clientRegistration(googleRegistration())
                                .attributes(attrs -> {
                                    attrs.put("sub", "1234567890");
                                    attrs.put("name", "테스트유저");
                                    attrs.put("picture", "https://example.com/p.jpg");
                                }))
                        .contentType("application/json")
                        .content("{\"url\":\"https://www.youtube.com/shorts/abc\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));

        org.assertj.core.api.Assertions.assertThat(processingJobRepository.findAll()).hasSize(1);
        verify(placeExtractionService).process(
                processingJobRepository.findAll().get(0).getId());
    }

    @Test
    void 처리_대기열이_가득_차면_503을_주고_작업을_남기지_않는다() throws Exception {
        User user = userRepository.save(new User("google", "1234567890", "테스트유저", null));
        doThrow(new TaskRejectedException("queue full")).when(placeExtractionService).process(anyLong());

        mockMvc.perform(post("/api/shares")
                        .with(oauth2Login()
                                .clientRegistration(googleRegistration())
                                .attributes(attrs -> {
                                    attrs.put("sub", "1234567890");
                                    attrs.put("name", "테스트유저");
                                    attrs.put("picture", "https://example.com/p.jpg");
                                }))
                        .contentType("application/json")
                        .content("{\"url\":\"https://www.youtube.com/shorts/full-queue\"}"))
                .andExpect(status().isServiceUnavailable());

        // 아무도 처리하지 않는 "처리 대기" 작업이 남으면 앱의 처리 중 카드·배지가 영원히 사라지지 않는다.
        org.assertj.core.api.Assertions.assertThat(processingJobRepository.findByUserOrderByCreatedAtDescIdDesc(user)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.youtube.com/redirect?q=http%3A%2F%2F169.254.169.254%2F&event=video_description",
            "https://www.youtube.com/attribution_link?u=http%3A%2F%2F127.0.0.1%2F"
    })
    void 허용_호스트라도_영상이_아닌_경로면_400을_반환하고_작업을_만들지_않는다(String url) throws Exception {
        // yt-dlp가 유튜브 리다이렉트를 따라 서버 내부 주소로 요청하게 만들 수 있던 링크(#49).
        userRepository.save(new User("google", "1234567890", "테스트유저", null));

        mockMvc.perform(post("/api/shares")
                        .with(oauth2Login()
                                .clientRegistration(googleRegistration())
                                .attributes(attrs -> {
                                    attrs.put("sub", "1234567890");
                                    attrs.put("name", "테스트유저");
                                    attrs.put("picture", "https://example.com/p.jpg");
                                }))
                        .contentType("application/json")
                        .content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().isBadRequest());

        org.assertj.core.api.Assertions.assertThat(processingJobRepository.findAll()).isEmpty();
        verify(placeExtractionService, org.mockito.Mockito.never()).process(anyLong());
    }

    @Test
    void 작업에는_사용자가_보낸_링크_대신_정식_주소를_저장한다() throws Exception {
        // 추적용 쿼리(si, t 등)가 붙은 링크도 yt-dlp에는 영상 ID로 다시 만든 주소만 넘긴다(#49).
        userRepository.save(new User("google", "1234567890", "테스트유저", null));
        doNothing().when(placeExtractionService).process(anyLong());

        mockMvc.perform(post("/api/shares")
                        .with(oauth2Login()
                                .clientRegistration(googleRegistration())
                                .attributes(attrs -> {
                                    attrs.put("sub", "1234567890");
                                    attrs.put("name", "테스트유저");
                                    attrs.put("picture", "https://example.com/p.jpg");
                                }))
                        .contentType("application/json")
                        .content("{\"url\":\"https://youtu.be/dQw4w9WgXcQ?si=tracking\"}"))
                .andExpect(status().isAccepted());

        org.assertj.core.api.Assertions.assertThat(processingJobRepository.findAll().get(0).getSourceUrl())
                .isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
    }

    @Test
    void 같은_URL이_이미_처리중이면_새_job을_만들지_않고_기존_job을_반환한다() throws Exception {
        User user = userRepository.save(new User("google", "1234567890", "테스트유저", null));
        ProcessingJob existing = processingJobRepository.save(
                new ProcessingJob(user, "https://www.youtube.com/shorts/dup", SourcePlatform.YOUTUBE));

        mockMvc.perform(post("/api/shares")
                        .with(oauth2Login()
                                .clientRegistration(googleRegistration())
                                .attributes(attrs -> {
                                    attrs.put("sub", "1234567890");
                                    attrs.put("name", "테스트유저");
                                    attrs.put("picture", "https://example.com/p.jpg");
                                }))
                        .contentType("application/json")
                        .content("{\"url\":\"https://www.youtube.com/shorts/dup\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(existing.getId()));

        org.assertj.core.api.Assertions.assertThat(processingJobRepository.findAll()).hasSize(1);
        verify(placeExtractionService, org.mockito.Mockito.never()).process(anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://m.instagram.com/reel/abc",
            "https://youtube-nocookie.com/watch?v=abc",
            "https://www.youtube-nocookie.com/embed/abc",
            "https://www.youtube.com./shorts/abc"
    })
    void 확장된_허용_호스트_변형도_202를_반환한다(String url) throws Exception {
        userRepository.save(new User("google", "1234567890", "테스트유저", null));
        doNothing().when(placeExtractionService).process(anyLong());

        mockMvc.perform(post("/api/shares")
                        .with(oauth2Login()
                                .clientRegistration(googleRegistration())
                                .attributes(attrs -> {
                                    attrs.put("sub", "1234567890");
                                    attrs.put("name", "테스트유저");
                                    attrs.put("picture", "https://example.com/p.jpg");
                                }))
                        .contentType("application/json")
                        .content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void 지원하지_않는_URL이면_400을_반환하고_작업을_만들지_않는다() throws Exception {
        userRepository.save(new User("google", "1234567890", "테스트유저", null));

        mockMvc.perform(post("/api/shares")
                        .with(oauth2Login()
                                .clientRegistration(googleRegistration())
                                .attributes(attrs -> {
                                    attrs.put("sub", "1234567890");
                                    attrs.put("name", "테스트유저");
                                    attrs.put("picture", "https://example.com/p.jpg");
                                }))
                        .contentType("application/json")
                        .content("{\"url\":\"https://evil.example.com/x\"}"))
                .andExpect(status().isBadRequest());

        org.assertj.core.api.Assertions.assertThat(processingJobRepository.findAll()).isEmpty();
    }

    @Test
    void URL_형식이_아니면_400을_반환하고_작업을_만들지_않는다() throws Exception {
        userRepository.save(new User("google", "1234567890", "테스트유저", null));

        mockMvc.perform(post("/api/shares")
                        .with(oauth2Login()
                                .clientRegistration(googleRegistration())
                                .attributes(attrs -> {
                                    attrs.put("sub", "1234567890");
                                    attrs.put("name", "테스트유저");
                                    attrs.put("picture", "https://example.com/p.jpg");
                                }))
                        .contentType("application/json")
                        .content("{\"url\":\"not a url\"}"))
                .andExpect(status().isBadRequest());

        org.assertj.core.api.Assertions.assertThat(processingJobRepository.findAll()).isEmpty();
    }

    @Test
    void url이_없으면_400을_반환하고_작업을_만들지_않는다() throws Exception {
        userRepository.save(new User("google", "1234567890", "테스트유저", null));

        mockMvc.perform(post("/api/shares")
                        .with(oauth2Login()
                                .clientRegistration(googleRegistration())
                                .attributes(attrs -> {
                                    attrs.put("sub", "1234567890");
                                    attrs.put("name", "테스트유저");
                                    attrs.put("picture", "https://example.com/p.jpg");
                                }))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());

        org.assertj.core.api.Assertions.assertThat(processingJobRepository.findAll()).isEmpty();
    }

    @Test
    void 인증되지_않은_요청은_401을_반환한다() throws Exception {
        mockMvc.perform(post("/api/shares")
                        .contentType("application/json")
                        .content("{\"url\":\"https://www.youtube.com/shorts/abc\"}"))
                .andExpect(status().isUnauthorized());
    }
}
