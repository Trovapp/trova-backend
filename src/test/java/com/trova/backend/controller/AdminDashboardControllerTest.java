package com.trova.backend.controller;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 대시보드 집계 API(#161). 날짜를 정하려고 저장 뒤 created_at을 직접 고친다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"app.admin.users=google:dash-admin", "app.instance-name=test-a"})
class AdminDashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private RequestPostProcessor loginAs(String sub) {
        ClientRegistration google = ClientRegistration.withRegistrationId("google")
                .clientId("test-client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .build();
        return oauth2Login().clientRegistration(google).attributes(attrs -> {
            attrs.put("sub", sub);
            attrs.put("name", sub);
            attrs.put("picture", "https://example.com/p.jpg");
        });
    }

    private User admin() {
        return userRepository.findByProviderAndProviderUserId("google", "dash-admin")
                .orElseGet(() -> userRepository.save(new User("google", "dash-admin", "관리자", null)));
    }

    private void setCreatedAt(String table, Long id, LocalDateTime at) {
        jdbcTemplate.update("update " + table + " set created_at = ? where id = ?", at, id);
    }

    @Test
    void 관리자가_아니면_사용_데이터를_볼_수_없다() throws Exception {
        userRepository.save(new User("google", "dash-normal", "일반", null));
        mockMvc.perform(get("/api/admin/dashboard/usage").with(loginAs("dash-normal")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 기간은_7_14_30일만_받는다() throws Exception {
        admin();
        mockMvc.perform(get("/api/admin/dashboard/usage?days=0").with(loginAs("dash-admin"))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/dashboard/usage?days=31").with(loginAs("dash-admin"))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/dashboard/usage?days=abc").with(loginAs("dash-admin"))).andExpect(status().isBadRequest());
    }

    @Test
    void 사용_데이터는_빈_날을_0으로_채운_일별_가입자_수를_준다() throws Exception {
        admin();
        User older = userRepository.save(new User("google", "dash-u1", "가입1", null));
        User today = userRepository.save(new User("google", "dash-u2", "가입2", null));
        setCreatedAt("users", older.getId(), LocalDate.now().minusDays(2).atTime(10, 0));
        setCreatedAt("users", today.getId(), LocalDate.now().atTime(0, 5));
        // 관리자 계정도 오늘 가입으로 잡힌다 — 오늘 2명(가입2 + 관리자), 2일 전 1명.

        mockMvc.perform(get("/api/admin/dashboard/usage?days=7").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.newUsers.length()").value(7))
                .andExpect(jsonPath("$.newUsers[6].date").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.newUsers[6].count").value(2))
                .andExpect(jsonPath("$.newUsers[4].count").value(1))
                .andExpect(jsonPath("$.newUsers[5].count").value(0))
                .andExpect(jsonPath("$.trips.length()").value(7));
    }
}
