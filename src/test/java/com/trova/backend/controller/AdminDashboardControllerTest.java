package com.trova.backend.controller;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
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

    @Autowired
    private ProcessingJobRepository processingJobRepository;

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

    private ProcessingJob job(User user, String url, String status, String error, LocalDateTime createdAt, LocalDateTime updatedAt) {
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(user, url, SourcePlatform.YOUTUBE));
        jdbcTemplate.update("update processing_jobs set status = ?, error_message = ?, created_at = ?, updated_at = ? where id = ?",
                status, error, createdAt, updatedAt, job.getId());
        return job;
    }

    @Test
    void 파이프라인은_일별_성공_실패와_처리_시간_실패_이유를_준다() throws Exception {
        User admin = admin();
        LocalDateTime today = LocalDate.now().atTime(0, 10);
        job(admin, "https://youtu.be/dashA1", "DONE", null, today, today.plusSeconds(20));
        job(admin, "https://youtu.be/dashA2", "DONE", null, today, today.plusSeconds(40));
        job(admin, "https://youtu.be/dashA3", "FAILED", ProcessingJob.SOURCE_RATE_LIMIT_MESSAGE, today, today.plusSeconds(5));
        job(admin, "https://youtu.be/dashA4", "FAILED", null, today, today.plusSeconds(5));

        mockMvc.perform(get("/api/admin/dashboard/pipeline?days=7").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily.length()").value(7))
                .andExpect(jsonPath("$.daily[6].total").value(4))
                .andExpect(jsonPath("$.daily[6].done").value(2))
                .andExpect(jsonPath("$.daily[6].failed").value(2))
                .andExpect(jsonPath("$.durationP50Seconds").value(20))
                .andExpect(jsonPath("$.durationP90Seconds").value(40))
                .andExpect(jsonPath("$.failureReasons[?(@.reason == 'SOURCE_RATE_LIMITED')].count").value(1))
                .andExpect(jsonPath("$.failureReasons[?(@.reason == 'OTHER')].count").value(1));
    }

    @Test
    void 오래_갱신되지_않은_진행_중_작업은_멈춘_작업으로_보여주되_개인정보는_빼고_준다() throws Exception {
        User admin = admin();
        LocalDateTime old = LocalDateTime.now().minusMinutes(9);
        ProcessingJob stuck = job(admin, "https://youtu.be/dashStuck", "PROCESSING", null, old, old);
        job(admin, "https://youtu.be/dashFresh", "PROCESSING", null, LocalDateTime.now(), LocalDateTime.now());

        mockMvc.perform(get("/api/admin/dashboard/pipeline?days=7").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stuckJobs.length()").value(1))
                .andExpect(jsonPath("$.stuckJobs[0].id").value(stuck.getId()))
                .andExpect(jsonPath("$.stuckJobs[0].sourceUrl").doesNotExist());
    }

    @Test
    void 작업이_없으면_파이프라인은_0과_빈_목록을_준다() throws Exception {
        admin();
        mockMvc.perform(get("/api/admin/dashboard/pipeline?days=7").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily.length()").value(7))
                .andExpect(jsonPath("$.durationP90Seconds").doesNotExist());
    }
}
