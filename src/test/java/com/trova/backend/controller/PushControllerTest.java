package com.trova.backend.controller;

import com.trova.backend.entity.PushSubscription;
import com.trova.backend.entity.User;
import com.trova.backend.repository.PushSubscriptionRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 웹 푸시 구독 API(#185, ADR 0016). 테스트 환경은 VAPID 키가 없어 공개 키는 404다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PushControllerTest {

    private static final String EP = "https://web.push.apple.com/abc";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PushSubscriptionRepository subscriptions;

    @Autowired
    private JwtService jwtService;

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

    private static String body(String endpoint) {
        return "{\"endpoint\":\"" + endpoint + "\",\"keys\":{\"p256dh\":\"pk\",\"auth\":\"au\"}}";
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        mockMvc.perform(post("/api/push/subscriptions").contentType(MediaType.APPLICATION_JSON).content(body(EP)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 키가_없으면_공개_키는_404() throws Exception {
        mockMvc.perform(get("/api/push/public-key").with(appToken(user("push-a"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void 등록하면_저장된다() throws Exception {
        User a = user("push-a");
        mockMvc.perform(post("/api/push/subscriptions").with(appToken(a))
                        .contentType(MediaType.APPLICATION_JSON).content(body(EP)))
                .andExpect(status().isNoContent());
        assertThat(subscriptions.findByUserId(a.getId())).hasSize(1);
    }

    @Test
    void 같은_기기에서_다른_사람이_등록하면_그_사람으로_옮겨진다() throws Exception {
        User a = user("push-a");
        User b = user("push-b");
        mockMvc.perform(post("/api/push/subscriptions").with(appToken(a))
                .contentType(MediaType.APPLICATION_JSON).content(body(EP))).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/push/subscriptions").with(appToken(b))
                .contentType(MediaType.APPLICATION_JSON).content(body(EP))).andExpect(status().isNoContent());

        assertThat(subscriptions.findByUserId(a.getId())).isEmpty();
        assertThat(subscriptions.findByUserId(b.getId())).hasSize(1);
        assertThat(subscriptions.count()).isEqualTo(1);
    }

    @Test
    void https가_아니거나_너무_길거나_키가_없으면_400() throws Exception {
        User a = user("push-a");
        mockMvc.perform(post("/api/push/subscriptions").with(appToken(a))
                .contentType(MediaType.APPLICATION_JSON).content(body("http://push.example/a")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/push/subscriptions").with(appToken(a))
                .contentType(MediaType.APPLICATION_JSON).content(body("https://p.example/" + "a".repeat(1000))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/push/subscriptions").with(appToken(a))
                .contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"" + EP + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 해제는_내_구독만_지운다() throws Exception {
        User a = user("push-a");
        User b = user("push-b");
        subscriptions.save(new PushSubscription(b.getId(), EP, "pk", "au"));

        mockMvc.perform(delete("/api/push/subscriptions").with(appToken(a))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"" + EP + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(subscriptions.findByEndpoint(EP)).isPresent();

        mockMvc.perform(delete("/api/push/subscriptions").with(appToken(b))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"" + EP + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(subscriptions.findByEndpoint(EP)).isEmpty();
    }
}
