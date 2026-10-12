package com.trova.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.admin.action.ActionPreview;
import com.trova.backend.admin.action.AdminAction;
import com.trova.backend.admin.action.AdminActionHandler;
import com.trova.backend.repository.AdminActionLogRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import com.trova.backend.support.AdminWebAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.admin.users=google:act-admin,google:act-admin2")
class AdminActionsControllerTest {

    static final AtomicInteger EXECUTED = new AtomicInteger();

    /** 실제 조치(Task 6~8) 전에 흐름만 확인하는 가짜 해제 조치. 실제 UNSUSPEND_USER가 생기면 이 설정을 지운다. */
    @TestConfiguration
    static class FakeHandlerConfig {
        @Bean
        AdminActionHandler fakeUnsuspend() {
            return new AdminActionHandler() {
                @Override
                public AdminAction action() {
                    return AdminAction.UNSUSPEND_USER;
                }

                @Override
                public ActionPreview preview(Long targetId, Long adminUserId) {
                    return new ActionPreview(AdminAction.UNSUSPEND_USER, targetId, targetId, "계정 1", "확인");
                }

                @Override
                public String execute(Long targetId, Long adminUserId) {
                    EXECUTED.incrementAndGet();
                    return "풀었음";
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminActionLogRepository logRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RequestPostProcessor admin() {
        return AdminWebAuth.adminWebToken(jwtService, userRepository, "act-admin");
    }

    private String preview(RequestPostProcessor who) throws Exception {
        String body = mockMvc.perform(post("/api/admin/actions/unsuspend-user/preview").with(who)
                        .contentType("application/json").content("{\"targetId\":77}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.impact").value("계정 1"))
                .andExpect(jsonPath("$.expiresInSeconds").value(300))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        return node.get("confirmToken").asText();
    }

    private ResultActions execute(RequestPostProcessor who, String token, String reason, String confirmText)
            throws Exception {
        return executeAt("unsuspend-user", who, token, reason, confirmText);
    }

    private ResultActions executeAt(String action, RequestPostProcessor who, String token, String reason,
                                    String confirmText) throws Exception {
        String json = objectMapper.writeValueAsString(Map.of(
                "confirmToken", token, "reason", reason, "confirmText", confirmText));
        return mockMvc.perform(post("/api/admin/actions/" + action + "/execute").with(who)
                .contentType("application/json").content(json));
    }

    @Test
    void 미리보기_뒤_사유와_확인_글자로_실행하고_기록한다() throws Exception {
        int before = EXECUTED.get();
        String token = preview(admin());

        execute(admin(), token, "사용자 요청", " 확인 ").andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("풀었음"));

        assertThat(EXECUTED.get()).isEqualTo(before + 1);
        assertThat(logRepository.findTop50ByTargetUserIdOrderByIdDesc(77L))
                .anySatisfy(log -> {
                    assertThat(log.getAction()).isEqualTo("UNSUSPEND_USER");
                    assertThat(log.getReason()).isEqualTo("사용자 요청");
                    assertThat(log.getResult()).isEqualTo("풀었음");
                });
    }

    @Test
    void 같은_확인_토큰을_두_번_쓰면_409() throws Exception {
        String token = preview(admin());
        execute(admin(), token, "사유", "확인").andExpect(status().isOk());
        int after = EXECUTED.get();

        execute(admin(), token, "사유", "확인").andExpect(status().isConflict());

        assertThat(EXECUTED.get()).isEqualTo(after);
    }

    @Test
    void 다른_관리자의_확인_토큰은_409() throws Exception {
        String token = preview(admin());
        execute(AdminWebAuth.adminWebToken(jwtService, userRepository, "act-admin2"), token, "사유", "확인")
                .andExpect(status().isConflict());
    }

    @Test
    void 다른_조치에_쓴_확인_토큰은_409이고_실행하지_않는다() throws Exception {
        int before = EXECUTED.get();
        String token = preview(admin());

        executeAt("suspend-user", admin(), token, "사유", "확인")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("확인이 만료됐어요. 미리보기부터 다시 해 주세요."));

        assertThat(EXECUTED.get()).isEqualTo(before);
    }

    @Test
    void 사유가_비거나_너무_길면_400() throws Exception {
        execute(admin(), preview(admin()), "   ", "확인").andExpect(status().isBadRequest());
        execute(admin(), preview(admin()), "가".repeat(301), "확인").andExpect(status().isBadRequest());
    }

    @Test
    void 확인_글자가_다르면_400이고_실행하지_않는다() throws Exception {
        int before = EXECUTED.get();
        execute(admin(), preview(admin()), "사유", "확 인").andExpect(status().isBadRequest());
        assertThat(EXECUTED.get()).isEqualTo(before);
    }

    @Test
    void 로그인_10분이_지난_관리자_토큰은_REAUTH_REQUIRED() throws Exception {
        String token = preview(admin());
        RequestPostProcessor old = AdminWebAuth.adminWebTokenIssuedAt(jwtService, userRepository, "act-admin",
                Instant.now().minusSeconds(700));
        execute(old, token, "사유", "확인").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("REAUTH_REQUIRED"));
    }

    @Test
    void 모르는_조치는_404() throws Exception {
        mockMvc.perform(post("/api/admin/actions/drop-table/preview").with(admin())
                .contentType("application/json").content("{\"targetId\":1}")).andExpect(status().isNotFound());
    }

    @Test
    void 관리자가_본문_없이_부르면_400() throws Exception {
        mockMvc.perform(post("/api/admin/actions/unsuspend-user/preview").with(admin()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/actions/unsuspend-user/execute").with(admin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 감사_기록_목록을_최근순으로_준다() throws Exception {
        execute(admin(), preview(admin()), "목록 확인", "확인").andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/actions").param("targetUserId", "77").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs[0].action").value("UNSUSPEND_USER"))
                .andExpect(jsonPath("$.logs[0].reason").value("목록 확인"));
    }
}
