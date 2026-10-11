package com.trova.backend.controller;

import com.trova.backend.entity.User;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import com.trova.backend.support.AdminWebAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.admin.users=google:admin-sub")
class AdminMetricsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    // 임베딩 생성은 외부 API(Gemini)를 부르므로 목 처리 — 여기선 호출 여부만 본다.
    @MockitoBean
    private PlaceEmbeddingService placeEmbeddingService;

    @Autowired
    private JwtService jwtService;

    private RequestPostProcessor loginAs(String sub) {
        return AdminWebAuth.adminWebToken(jwtService, userRepository, sub);
    }

    @Test
    void 일반_사용자는_AI_지표를_조회할_수_없다() throws Exception {
        userRepository.save(new User("google", "normal-sub", "일반", null));

        mockMvc.perform(get("/api/admin/ai-metrics").with(loginAs("normal-sub")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 일반_사용자는_임베딩_백필을_실행할_수_없다() throws Exception {
        userRepository.save(new User("google", "normal-sub2", "일반2", null));

        mockMvc.perform(post("/api/admin/backfill-embeddings").with(loginAs("normal-sub2")))
                .andExpect(status().isForbidden());
        verify(placeEmbeddingService, never()).ensureEmbeddings(any());
    }

    @Test
    void 설정에_등록된_관리자는_AI_지표를_조회할_수_있다() throws Exception {
        userRepository.save(new User("google", "admin-sub", "관리자", null));

        mockMvc.perform(get("/api/admin/ai-metrics").with(loginAs("admin-sub")))
                .andExpect(status().isOk());
    }
}
