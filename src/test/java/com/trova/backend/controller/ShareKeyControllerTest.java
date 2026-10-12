package com.trova.backend.controller;

import com.trova.backend.entity.ShareKey;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ShareKeyRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import com.trova.backend.service.PlaceExtractionService;
import com.trova.backend.support.RateLimitWindow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** iOS 단축어용 공유 전용 키(#165) — 공유 저장(POST /api/shares)만 할 수 있는 사용자별 키. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.share-key.per-minute=3")
class ShareKeyControllerTest {

    private static final String HEADER = "X-Trova-Share-Key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ShareKeyRepository shareKeyRepository;

    @Autowired
    private JwtService jwtService;

    // 공유가 실제 분석을 시작하지 않게 — 여기선 키로 인증되는지만 본다.
    @MockitoBean
    private PlaceExtractionService placeExtractionService;

    private User user(String sub) {
        return userRepository.findByProviderAndProviderUserId("google", sub)
                .orElseGet(() -> userRepository.save(new User("google", sub, sub, null)));
    }

    private RequestPostProcessor appToken(User user) {
        String token = jwtService.issue(user);
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    private String issueKey(User user) throws Exception {
        String body = mockMvc.perform(post("/api/share-key").with(appToken(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"key\":\"([^\"]+)\".*", "$1");
    }

    private String share(String url) {
        return "{\"url\":\"" + url + "\"}";
    }

    @Test
    void 키를_발급하면_원문은_한_번만_주고_DB에는_해시만_남긴다() throws Exception {
        User user = user("sk-1");

        String key = issueKey(user);

        assertThat(key).startsWith("trv_sk_").hasSizeGreaterThan(40);
        List<ShareKey> saved = shareKeyRepository.findAll().stream().filter(k -> k.getUserId().equals(user.getId())).toList();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getKeyHash()).isNotEqualTo(key).doesNotContain(key);
        mockMvc.perform(get("/api/share-key").with(appToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.key").doesNotExist());
    }

    @Test
    void 키로_공유를_저장하고_마지막_사용_시각을_남긴다() throws Exception {
        User user = user("sk-2");
        String key = issueKey(user);

        mockMvc.perform(post("/api/shares").header(HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON).content(share("https://www.instagram.com/reel/abc1/")))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/share-key").with(appToken(user)))
                .andExpect(jsonPath("$.lastUsedAt").isNotEmpty());
    }

    @Test
    void 키로는_공유_저장_말고_다른_API를_쓸_수_없다() throws Exception {
        String key = issueKey(user("sk-3"));

        mockMvc.perform(get("/api/trips").header(HEADER, key)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/shares").header(HEADER, key)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/share-key").header(HEADER, key)).andExpect(status().isUnauthorized());
    }

    @Test
    void 재발급하면_이전_키는_바로_쓸_수_없고_폐기하면_키가_없다() throws Exception {
        User user = user("sk-4");
        String first = issueKey(user);
        String second = issueKey(user);

        mockMvc.perform(post("/api/shares").header(HEADER, first)
                        .contentType(MediaType.APPLICATION_JSON).content(share("https://youtu.be/sk4a")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/shares").header(HEADER, second)
                        .contentType(MediaType.APPLICATION_JSON).content(share("https://youtu.be/sk4b")))
                .andExpect(status().isAccepted());

        mockMvc.perform(delete("/api/share-key").with(appToken(user))).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/shares").header(HEADER, second)
                        .contentType(MediaType.APPLICATION_JSON).content(share("https://youtu.be/sk4c")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/share-key").with(appToken(user))).andExpect(jsonPath("$.exists").value(false));
    }

    @Test
    void 잘못된_키는_401이다() throws Exception {
        mockMvc.perform(post("/api/shares").header(HEADER, "trv_sk_wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(share("https://youtu.be/x")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 키당_분당_한도를_넘으면_429다() throws Exception {
        RateLimitWindow.awaitFreshMinute();
        String key = issueKey(user("sk-5"));
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/shares").header(HEADER, key)
                            .contentType(MediaType.APPLICATION_JSON).content(share("https://youtu.be/sk5" + i)))
                    .andExpect(status().isAccepted());
        }
        mockMvc.perform(post("/api/shares").header(HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON).content(share("https://youtu.be/sk5x")))
                .andExpect(status().isTooManyRequests());
    }
}
