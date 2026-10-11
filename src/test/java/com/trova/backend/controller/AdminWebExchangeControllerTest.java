package com.trova.backend.controller;

import com.trova.backend.admin.AdminExchangeCodeStore;
import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import com.trova.backend.support.AdminWebAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 웹 교환 API·CORS·요청 횟수 제한(#163). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "app.admin.users=google:ex-admin",
        "app.admin-web-url=https://trova-admin.example.dev",
        "app.admin.rate-limit.exchange-per-minute=10",
        "app.admin.rate-limit.admin-api-per-minute=3"})
class AdminWebExchangeControllerTest {

    private static final String ADMIN_WEB = "https://trova-admin.example.dev";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminExchangeCodeStore codeStore;

    @Autowired
    private JwtService jwtService;

    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private String body(String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    @Test
    void 로그인_없이_교환_코드로_관리자_웹_토큰을_받고_그_토큰으로_관리자_API를_쓴다() throws Exception {
        User admin = userRepository.save(new User("google", "ex-admin", "관리자", null));
        String code = codeStore.issue(admin.getId());

        String response = mockMvc.perform(post("/api/auth/exchange").with(fromIp("10.0.0.1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresInSeconds").value(3600))
                .andReturn().getResponse().getContentAsString();
        String token = response.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/admin/dashboard/summary").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void 잘못된_코드는_400이다() throws Exception {
        mockMvc.perform(post("/api/auth/exchange").with(fromIp("10.0.0.2"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("wrong")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 교환은_IP당_분당_10번까지만_받는다() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/auth/exchange").with(fromIp("10.0.0.3"))
                            .contentType(MediaType.APPLICATION_JSON).content(body("guess-" + i)))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/auth/exchange").with(fromIp("10.0.0.3"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("guess-10")))
                .andExpect(status().isTooManyRequests());
        mockMvc.perform(post("/api/auth/exchange").with(fromIp("10.0.0.4"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("other-ip")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 관리자_API는_관리자당_분당_한도를_넘으면_429다() throws Exception {
        userRepository.save(new User("google", "ex-admin", "관리자", null));
        RequestPostProcessor admin = AdminWebAuth.adminWebToken(jwtService, userRepository, "ex-admin");
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/admin/dashboard/summary").with(admin)).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/admin/dashboard/summary").with(admin)).andExpect(status().isTooManyRequests());
    }

    @Test
    void CORS는_관리자_웹_주소를_허용하고_다른_주소는_막는다() throws Exception {
        mockMvc.perform(options("/api/auth/exchange")
                        .header(HttpHeaders.ORIGIN, ADMIN_WEB)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ADMIN_WEB));
        mockMvc.perform(options("/api/auth/exchange")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }
}
