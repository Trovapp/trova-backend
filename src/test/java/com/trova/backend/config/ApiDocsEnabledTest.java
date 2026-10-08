package com.trova.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API_DOCS_ENABLED=true로 켜면 로그인 없이 문서를 보고, Swagger UI에서 JWT로 호출해 볼 수 있어야 한다(#149).
 */
@SpringBootTest(properties = "API_DOCS_ENABLED=true")
@AutoConfigureMockMvc
class ApiDocsEnabledTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void 켜면_로그인_없이_컨트롤러_엔드포인트가_담긴_문서가_열린다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/shares'].post").exists())
                .andExpect(jsonPath("$.paths['/api/places'].get").exists());
    }

    @Test
    void 문서에_JWT_인증_방식이_들어있다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.security[*].bearerAuth").exists());
    }

    @Test
    void 켜면_로그인_없이_Swagger_UI가_열린다() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    void 문서를_켜도_다른_API는_여전히_로그인이_필요하다() throws Exception {
        mockMvc.perform(get("/api/trips"))
                .andExpect(status().isUnauthorized());
    }
}
