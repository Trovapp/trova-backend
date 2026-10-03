package com.trova.backend.controller;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.service.PlaceExtractionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 같은 영상 링크가 동시에 들어올 때 작업이 하나만 생기는지(#97). 요청마다 실제로 따로 커밋돼야 서로를 볼 수 있으므로
 * SharesControllerTest와 달리 테스트 트랜잭션으로 묶지 않고, 끝나면 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SharesConcurrencyTest {

    private static final int REQUESTS = 5;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @MockitoBean
    private PlaceExtractionService placeExtractionService;

    private User user;

    @AfterEach
    void cleanUp() {
        if (user != null) {
            processingJobRepository.deleteAll(processingJobRepository.findByUserOrderByCreatedAtDescIdDesc(user));
            userRepository.delete(user);
        }
    }

    @Test
    void 같은_링크를_동시에_여러_번_보내도_작업은_하나만_생기고_모두_같은_작업을_돌려받는다() throws Exception {
        user = userRepository.save(new User("google", "concurrent-share-1", "동시제출", null));
        CyclicBarrier start = new CyclicBarrier(REQUESTS);
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        List<Future<String>> responses = new ArrayList<>();
        try {
            for (int i = 0; i < REQUESTS; i++) {
                responses.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(post("/api/shares")
                                    .with(oauth2Login()
                                            .clientRegistration(googleRegistration())
                                            .attributes(attrs -> {
                                                attrs.put("sub", "concurrent-share-1");
                                                attrs.put("name", "동시제출");
                                            }))
                                    .contentType("application/json")
                                    .content("{\"url\":\"https://www.youtube.com/shorts/concurrent1\"}"))
                            .andReturn().getResponse().getContentAsString();
                }));
            }
            List<String> bodies = new ArrayList<>();
            for (Future<String> response : responses) {
                bodies.add(response.get());
            }

            List<ProcessingJob> jobs = processingJobRepository.findByUserOrderByCreatedAtDescIdDesc(user);
            assertThat(jobs).hasSize(1);
            String jobIdField = "\"jobId\":" + jobs.get(0).getId();
            assertThat(bodies).allSatisfy(body -> assertThat(body).contains(jobIdField));
        } finally {
            pool.shutdownNow();
        }
    }

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
}
