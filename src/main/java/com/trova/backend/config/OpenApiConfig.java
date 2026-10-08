package com.trova.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 문서(#149)의 제목과 인증 방식. 엔드포인트 목록은 컨트롤러에서 자동으로 만들어진다.
 * 문서를 켜고 끄는 건 application.yml의 springdoc 설정(API_DOCS_ENABLED)이 정한다.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI trovaOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Trova API")
                        .description("여행 영상 링크에서 장소를 뽑아 지도·일정으로 정리하는 Trova 백엔드 API")
                        .version("v1"))
                // 앱은 JWT(Authorization: Bearer)로 부른다. Swagger UI의 Authorize에 앱 로그인 토큰을 넣으면 바로 호출해 볼 수 있다.
                // 웹은 세션 쿠키를 쓰지만, 문서에서 시험할 때는 토큰 방식 하나로 충분하다.
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
