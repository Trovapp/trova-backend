package com.trova.backend.controller;

import com.trova.backend.entity.AdminActionLog;
import com.trova.backend.push.PushNotificationService;
import com.trova.backend.repository.AdminActionLogRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import com.trova.backend.support.AdminWebAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 본인 기기로 웹 푸시 테스트 발송(#185) — 관리자만, 본인에게만, 기록을 남긴다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.admin.users=google:push-admin")
class AdminPushControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminActionLogRepository logRepository;

    @MockitoBean
    private PushNotificationService pushNotificationService;

    @Test
    void 관리자가_아니면_403() throws Exception {
        mockMvc.perform(post("/api/admin/push/test")
                        .with(AdminWebAuth.adminWebToken(jwtService, userRepository, "push-someone"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"draft\"}"))
                .andExpect(status().isForbidden());
        verify(pushNotificationService, never()).sendToUser(anyLong(), anyString(), any());
    }

    @Test
    void 관리자_본인에게_고른_문구로_보내고_기록한다() throws Exception {
        when(pushNotificationService.sendToUser(anyLong(), eq("test-rain"), any()))
                .thenReturn(new PushNotificationService.Result(1, 0, 0));
        mockMvc.perform(post("/api/admin/push/test")
                        .with(AdminWebAuth.adminWebToken(jwtService, userRepository, "push-admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"rain\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(1));

        Long adminId = userRepository.findByProviderAndProviderUserId("google", "push-admin").orElseThrow().getId();
        verify(pushNotificationService).sendToUser(eq(adminId), eq("test-rain"), any());
        assertThat(logRepository.findTop50ByOrderByIdDesc())
                .anyMatch(l -> AdminActionLog.ADMIN_PUSH_TEST.equals(l.getAction()) && adminId.equals(l.getActorUserId()));
    }

    @Test
    void 종류가_틀리면_400() throws Exception {
        mockMvc.perform(post("/api/admin/push/test")
                        .with(AdminWebAuth.adminWebToken(jwtService, userRepository, "push-admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }
}
