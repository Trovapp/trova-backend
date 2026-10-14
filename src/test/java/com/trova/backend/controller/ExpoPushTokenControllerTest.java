package com.trova.backend.controller;

import com.trova.backend.entity.ExpoPushToken;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ExpoPushTokenRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 안드로이드 앱(Expo) 알림 토큰 API(#190, ADR 0017). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExpoPushTokenControllerTest {

    private static final String TOKEN = "ExponentPushToken[abcdefghij123456]";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ExpoPushTokenRepository tokens;

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

    private static String body(String token) {
        return "{\"token\":\"" + token + "\"}";
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        mockMvc.perform(post("/api/push/expo-tokens").contentType(MediaType.APPLICATION_JSON).content(body(TOKEN)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 등록하면_저장된다() throws Exception {
        User a = user("expo-a");
        mockMvc.perform(post("/api/push/expo-tokens").with(appToken(a))
                .contentType(MediaType.APPLICATION_JSON).content(body(TOKEN))).andExpect(status().isNoContent());
        assertThat(tokens.findByUserId(a.getId())).hasSize(1);
    }

    @Test
    void 같은_기기에서_다른_사람이_등록하면_그_사람으로_옮겨진다() throws Exception {
        User a = user("expo-a");
        User b = user("expo-b");
        mockMvc.perform(post("/api/push/expo-tokens").with(appToken(a))
                .contentType(MediaType.APPLICATION_JSON).content(body(TOKEN))).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/push/expo-tokens").with(appToken(b))
                .contentType(MediaType.APPLICATION_JSON).content(body(TOKEN))).andExpect(status().isNoContent());
        assertThat(tokens.findByUserId(a.getId())).isEmpty();
        assertThat(tokens.findByUserId(b.getId())).hasSize(1);
        assertThat(tokens.count()).isEqualTo(1);
    }

    @Test
    void 형식이_틀린_토큰은_400() throws Exception {
        User a = user("expo-a");
        for (String bad : new String[] {"abc", "ExponentPushToken[]", "ExponentPushToken[" + "a".repeat(190) + "]",
                "https://evil.io/ExponentPushToken[abcdefghij]"}) {
            mockMvc.perform(post("/api/push/expo-tokens").with(appToken(a))
                    .contentType(MediaType.APPLICATION_JSON).content(body(bad))).andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/push/expo-tokens").with(appToken(a))
                .contentType(MediaType.APPLICATION_JSON).content(body("ExpoPushToken[xyz0123456789_-]")))
                .andExpect(status().isNoContent());
    }

    @Test
    void 한_사람의_토큰은_10개까지_넘으면_가장_오래된_것부터_지운다() throws Exception {
        User a = user("expo-a");
        for (int i = 0; i < 12; i++) {
            mockMvc.perform(post("/api/push/expo-tokens").with(appToken(a))
                    .contentType(MediaType.APPLICATION_JSON).content(body("ExponentPushToken[device-number-" + i + "]")))
                    .andExpect(status().isNoContent());
        }
        assertThat(tokens.findByUserId(a.getId())).hasSize(10)
                .extracting(ExpoPushToken::getToken)
                .doesNotContain("ExponentPushToken[device-number-0]", "ExponentPushToken[device-number-1]")
                .contains("ExponentPushToken[device-number-11]");
    }

    @Test
    void 해제는_내_토큰만_지운다() throws Exception {
        User a = user("expo-a");
        User b = user("expo-b");
        tokens.save(new ExpoPushToken(b.getId(), TOKEN));
        mockMvc.perform(delete("/api/push/expo-tokens").with(appToken(a))
                .contentType(MediaType.APPLICATION_JSON).content(body(TOKEN))).andExpect(status().isNoContent());
        assertThat(tokens.findByToken(TOKEN)).isPresent();
        mockMvc.perform(delete("/api/push/expo-tokens").with(appToken(b))
                .contentType(MediaType.APPLICATION_JSON).content(body(TOKEN))).andExpect(status().isNoContent());
        assertThat(tokens.findByToken(TOKEN)).isEmpty();
    }
}
