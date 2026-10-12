package com.trova.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.admin.action.AdminAction;
import com.trova.backend.entity.AdminActionLog;
import com.trova.backend.entity.User;
import com.trova.backend.repository.AdminActionLogRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import com.trova.backend.security.SuspendedUsers;
import com.trova.backend.support.AdminWebAuth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.in;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 미리보기·실행 흐름을 실제 조치(정지 풀기·계정 삭제)로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.admin.users=google:act-admin,google:act-admin2")
class AdminActionsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SuspendedUsers suspendedUsers;

    @Autowired
    private AdminActionLogRepository logRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdUserIds) {
            suspendedUsers.remove(id);
            if (userRepository.existsById(id)) {
                userRepository.deleteById(id);
            }
        }
    }

    private RequestPostProcessor admin() {
        return AdminWebAuth.adminWebToken(jwtService, userRepository, "act-admin");
    }

    private User newUser() {
        User user = userRepository.save(new User("google", "act-" + UUID.randomUUID(), "대상", null));
        createdUserIds.add(user.getId());
        return user;
    }

    private User suspendedUser() {
        User user = newUser();
        suspend(user.getId());
        return user;
    }

    private void suspend(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        user.suspend();
        userRepository.save(user);
        suspendedUsers.add(userId);
    }

    private void assertStillSuspended(Long userId) {
        assertThat(userRepository.findById(userId).orElseThrow().isSuspended()).isTrue();
        assertThat(suspendedUsers.isSuspended(userId)).isTrue();
    }

    private String previewAt(String action, RequestPostProcessor who, Long targetId, String impact) throws Exception {
        String body = mockMvc.perform(post("/api/admin/actions/" + action + "/preview").with(who)
                        .contentType("application/json").content("{\"targetId\":" + targetId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.impact").value(impact))
                .andExpect(jsonPath("$.expiresInSeconds").value(300))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        return node.get("confirmToken").asText();
    }

    private String preview(RequestPostProcessor who, Long targetId) throws Exception {
        return previewAt("unsuspend-user", who, targetId, "계정 1");
    }

    private ResultActions execute(RequestPostProcessor who, String token, String reason) throws Exception {
        return executeAt("unsuspend-user", who, token, reason, null);
    }

    private ResultActions executeAt(String action, RequestPostProcessor who, String token, String reason,
                                    String confirmText) throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("confirmToken", token);
        body.put("reason", reason);
        body.put("confirmText", confirmText);
        return mockMvc.perform(post("/api/admin/actions/" + action + "/execute").with(who)
                .contentType("application/json").content(objectMapper.writeValueAsString(body)));
    }

    @Test
    void 미리보기_뒤_사유로_실행하고_기록한다() throws Exception {
        User user = suspendedUser();
        String token = preview(admin(), user.getId());

        execute(admin(), token, " 사용자 요청 ").andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("정지 풀기"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().isSuspended()).isFalse();
        assertThat(suspendedUsers.isSuspended(user.getId())).isFalse();
        assertThat(logRepository.findTop50ByTargetUserIdOrderByIdDesc(user.getId()))
                .anySatisfy(log -> {
                    assertThat(log.getAction()).isEqualTo("UNSUSPEND_USER");
                    assertThat(log.getReason()).isEqualTo("사용자 요청");
                    assertThat(log.getResult()).isEqualTo("정지 풀기");
                });
    }

    @Test
    void 같은_확인_토큰을_두_번_쓰면_409() throws Exception {
        User user = suspendedUser();
        String token = preview(admin(), user.getId());
        execute(admin(), token, "사유").andExpect(status().isOk());
        suspend(user.getId());

        execute(admin(), token, "사유").andExpect(status().isConflict());

        assertStillSuspended(user.getId());
    }

    @Test
    void 다른_관리자의_확인_토큰은_409() throws Exception {
        User user = suspendedUser();
        String token = preview(admin(), user.getId());

        execute(AdminWebAuth.adminWebToken(jwtService, userRepository, "act-admin2"), token, "사유")
                .andExpect(status().isConflict());

        assertStillSuspended(user.getId());
    }

    @Test
    void 다른_조치에_쓴_확인_토큰은_409이고_실행하지_않는다() throws Exception {
        User user = suspendedUser();
        String token = preview(admin(), user.getId());

        executeAt("suspend-user", admin(), token, "사유", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("확인이 만료됐어요. 미리보기부터 다시 해 주세요."));

        assertStillSuspended(user.getId());
    }

    @Test
    void 사유가_비거나_너무_길면_400() throws Exception {
        User user = suspendedUser();

        String message = "사유를 1~300자로 적어 주세요. 미리보기부터 다시 해 주세요.";
        execute(admin(), preview(admin(), user.getId()), "   ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(message));
        execute(admin(), preview(admin(), user.getId()), "가".repeat(301)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(message));

        assertStillSuspended(user.getId());
    }

    @Test
    void 확인_글자가_다르면_400이고_실행하지_않는다() throws Exception {
        User user = newUser();
        String impact = "계정 1·영상 0·장소 0·여행 0·초안 0";

        executeAt("delete-account", admin(), previewAt("delete-account", admin(), user.getId(), impact),
                "사유", "계정삭제").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("확인 글자가 달라요: 계정 삭제. 미리보기부터 다시 해 주세요."));
        executeAt("delete-account", admin(), previewAt("delete-account", admin(), user.getId(), impact),
                "사유", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("확인 글자가 달라요: 계정 삭제. 미리보기부터 다시 해 주세요."));

        assertThat(userRepository.existsById(user.getId())).isTrue();
    }

    @Test
    void 확인_글자는_앞뒤_공백만_다르면_통과한다() throws Exception {
        User user = newUser();
        String token = previewAt("delete-account", admin(), user.getId(), "계정 1·영상 0·장소 0·여행 0·초안 0");

        executeAt("delete-account", admin(), token, "사용자 요청", " 계정 삭제 ").andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("계정 1·영상 0·장소 0·여행 0·초안 0"));

        assertThat(userRepository.existsById(user.getId())).isFalse();
    }

    @Test
    void 로그인_10분이_지난_관리자_토큰은_REAUTH_REQUIRED() throws Exception {
        User user = suspendedUser();
        String token = preview(admin(), user.getId());
        RequestPostProcessor old = AdminWebAuth.adminWebTokenIssuedAt(jwtService, userRepository, "act-admin",
                Instant.now().minusSeconds(700));

        execute(old, token, "사유").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("REAUTH_REQUIRED"));

        assertStillSuspended(user.getId());
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
        User user = suspendedUser();
        execute(admin(), preview(admin(), user.getId()), "목록 확인").andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/actions").param("targetUserId", String.valueOf(user.getId())).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs[0].action").value("UNSUSPEND_USER"))
                .andExpect(jsonPath("$.logs[0].reason").value("목록 확인"));
    }

    private Long adminId() {
        return userRepository.findByProviderAndProviderUserId("google", "act-admin").orElseThrow().getId();
    }

    @Test
    void 사용자별_기록은_열람이_많아도_실제_조치를_보여준다() throws Exception {
        admin();
        User user = newUser();
        Long adminId = adminId();
        logRepository.save(new AdminActionLog(adminId, "SUSPEND_USER", user.getId(), user.getId(), "신고", "정지"));
        for (int i = 0; i < 60; i++) {
            logRepository.save(new AdminActionLog(adminId, AdminActionLog.ADMIN_USER_VIEW, user.getId(), user.getId(),
                    null, null));
        }

        mockMvc.perform(get("/api/admin/actions").param("targetUserId", String.valueOf(user.getId())).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs.length()").value(1))
                .andExpect(jsonPath("$.logs[0].action").value("SUSPEND_USER"))
                .andExpect(jsonPath("$.logs[0].reason").value("신고"));
    }

    @Test
    void kind_actions면_로그인_열람_검색_기록을_빼고_조치만_준다() throws Exception {
        admin();
        User user = newUser();
        Long adminId = adminId();
        logRepository.save(new AdminActionLog(adminId, "HIDE_DRAFT", 1L, user.getId(), "중복", "초안 1"));
        logRepository.save(new AdminActionLog(adminId, AdminActionLog.ADMIN_WEB_LOGIN, null, null, null));
        logRepository.save(new AdminActionLog(adminId, AdminActionLog.ADMIN_WEB_LOGIN_DENIED, null, null, null));
        logRepository.save(new AdminActionLog(adminId, AdminActionLog.ADMIN_USER_SEARCH, null, null, null, "결과 1명"));
        logRepository.save(new AdminActionLog(adminId, AdminActionLog.ADMIN_USER_VIEW, user.getId(), user.getId(),
                null, null));
        List<String> actions = Arrays.stream(AdminAction.values())
                .map(Enum::name).toList();

        mockMvc.perform(get("/api/admin/actions").param("kind", "actions").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs[0].action").value("HIDE_DRAFT"))
                .andExpect(jsonPath("$.logs[*].action", everyItem(in(actions))));
        mockMvc.perform(get("/api/admin/actions").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs[0].action").value(AdminActionLog.ADMIN_USER_VIEW))
                .andExpect(jsonPath("$.logs[*].action", hasItem(AdminActionLog.ADMIN_WEB_LOGIN)));
        mockMvc.perform(get("/api/admin/actions").param("kind", "all").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs[0].action").value(AdminActionLog.ADMIN_USER_VIEW));
        mockMvc.perform(get("/api/admin/actions").param("kind", "nope").with(admin()))
                .andExpect(status().isBadRequest());
    }
}
