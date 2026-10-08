package com.trova.backend.config;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 문서는 기본으로 꺼져 있어야 한다(#149) — 운영 서버에서 API 구조가 로그인 없이 공개되지 않게.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiDocsDisabledByDefaultTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    @Test
    void 설정하지_않으면_API_문서가_열리지_않는다() throws Exception {
        MvcResult apiDocs = mockMvc.perform(get("/v3/api-docs")).andReturn();
        MvcResult swaggerUi = mockMvc.perform(get("/swagger-ui/index.html")).andReturn();

        assertThat(apiDocs.getResponse().getStatus()).isNotEqualTo(200);
        assertThat(swaggerUi.getResponse().getStatus()).isNotEqualTo(200);
    }

    @Test
    void 설정하지_않으면_로그인해도_문서_자체가_없다() throws Exception {
        User user = userRepository.save(new User("google", "api-docs-off-1", "문서", null));

        mockMvc.perform(get("/v3/api-docs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(user)))
                .andExpect(status().isNotFound());
    }
}
