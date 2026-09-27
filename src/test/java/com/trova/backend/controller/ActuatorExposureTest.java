package com.trova.backend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 설정(src/main/resources/application.yml)의 actuator 노출 기본값 검증(#27).
 * 테스트 클래스패스에는 별도 application.yml이 있어 운영 설정을 대신하므로, 운영 파일을 직접 읽는다.
 */
class ActuatorExposureTest {

    private String resolveExposure(Map<String, Object> env) throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("main", new FileSystemResource("src/main/resources/application.yml"));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-env", env));
        sources.forEach(environment.getPropertySources()::addLast);
        return environment.getProperty("management.endpoints.web.exposure.include");
    }

    @Test
    void 기본값은_health만_노출한다() throws Exception {
        assertThat(resolveExposure(Map.of())).isEqualTo("health");
    }

    @Test
    void ACTUATOR_EXPOSE로_prometheus를_켤_수_있다() throws Exception {
        assertThat(resolveExposure(Map.of("ACTUATOR_EXPOSE", "health,prometheus"))).isEqualTo("health,prometheus");
    }
}
