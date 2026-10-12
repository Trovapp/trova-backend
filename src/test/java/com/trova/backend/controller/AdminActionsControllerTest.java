package com.trova.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

        execute(admin(), preview(admin(), user.getId()), "   ").andExpect(status().isBadRequest());
        execute(admin(), preview(admin(), user.getId()), "가".repeat(301)).andExpect(status().isBadRequest());

        assertStillSuspended(user.getId());
    }

    @Test
    void 확인_글자가_다르면_400이고_실행하지_않는다() throws Exception {
        User user = newUser();
        String impact = "계정 1·영상 0·장소 0·여행 0·초안 0";

        executeAt("delete-account", admin(), previewAt("delete-account", admin(), user.getId(), impact),
                "사유", "계정삭제").andExpect(status().isBadRequest());
        executeAt("delete-account", admin(), previewAt("delete-account", admin(), user.getId(), impact),
                "사유", null).andExpect(status().isBadRequest());

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
}
