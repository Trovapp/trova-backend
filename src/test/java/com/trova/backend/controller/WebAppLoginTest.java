package com.trova.backend.controller;

import com.trova.backend.admin.AdminExchangeCodeStore;
import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import com.trova.backend.service.WebAppLoginService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PWA(웹앱) 로그인(#165): 1회용 코드 → 앱 토큰, 관리자 웹 코드와 섞이지 않음, 웹앱 주소 CORS(쿠키 없음). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "app.web-app-url=https://trova.example.dev/",
        "app.admin-web-url=https://trova-admin.example.dev",
        "app.admin.users=google:wa-admin"})
class WebAppLoginTest {

    private static final String WEB_APP = "https://trova.example.dev";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WebAppLoginService webAppLoginService;

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

    private String code(String redirect) {
        return redirect.substring(redirect.indexOf("?code=") + "?code=".length());
    }

    @Test
    void 로그인하면_웹앱_콜백으로_코드를_보내고_교환하면_앱_토큰을_준다() throws Exception {
        User user = userRepository.save(new User("google", "wa-user", "웹앱", null));

        String redirect = webAppLoginService.loginRedirect(user);

        assertThat(redirect).startsWith(WEB_APP + "/auth/callback/?code=");
        String response = mockMvc.perform(post("/api/auth/web-exchange").with(fromIp("10.1.0.1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code(redirect) + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = response.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        JwtService.VerifiedToken verified = jwtService.parse(token);
        assertThat(verified.userId()).isEqualTo(user.getId());
        assertThat(verified.adminWeb()).isFalse();
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void 같은_코드는_다시_못_쓰고_관리자_웹_코드와_섞이지_않는다() throws Exception {
        User user = userRepository.save(new User("google", "wa-admin", "관리자", null));
        String webCode = code(webAppLoginService.loginRedirect(user));
        String adminCode = codeStore.issue(user.getId(), AdminExchangeCodeStore.Purpose.ADMIN_WEB);

        mockMvc.perform(post("/api/auth/web-exchange").with(fromIp("10.1.0.2"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + adminCode + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/exchange").with(fromIp("10.1.0.2"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + webCode + "\"}"))
                .andExpect(status().isBadRequest());
        // 관리자 교환에 잘못 써 본 웹 코드는 이미 지워졌다 — 1회용이라 시도 자체가 코드를 소모한다.
        mockMvc.perform(post("/api/auth/web-exchange").with(fromIp("10.1.0.2"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + webCode + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 실패한_로그인은_웹앱_오류_화면으로_보낸다() {
        assertThat(webAppLoginService.failureRedirect()).isEqualTo(WEB_APP + "/auth/callback/?error=oauth_failed");
    }

    @Test
    void 웹앱_주소는_쿠키_없이_API를_부를_수_있다() throws Exception {
        mockMvc.perform(options("/api/trips")
                        .header(HttpHeaders.ORIGIN, WEB_APP)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WEB_APP))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        // 관리자 API는 관리자 웹 주소만
        mockMvc.perform(options("/api/admin/dashboard/summary")
                        .header(HttpHeaders.ORIGIN, WEB_APP)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }
}
