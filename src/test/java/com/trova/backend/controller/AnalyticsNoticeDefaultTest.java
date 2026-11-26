package com.trova.backend.controller;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtAuthenticationToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AnalyticsNoticeDefaultTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;

    @Test
    void 고지_기본값은_false이며_켜기를_차단한다() throws Exception {
        User user = users.save(new User("google", "analytics-default", "테스트", null));
        mvc.perform(get("/api/me/analytics-consent").with(authentication(new JwtAuthenticationToken(user.getId()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.noticeReady").value(false));
        mvc.perform(put("/api/me/analytics-consent").with(authentication(new JwtAuthenticationToken(user.getId())))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"consentVersion":"analytics-2026-11-26-v1","expectedRevision":0,
                         "usageConsent":true,"transferConsent":true}
                        """))
                .andExpect(status().isConflict());
    }
}
