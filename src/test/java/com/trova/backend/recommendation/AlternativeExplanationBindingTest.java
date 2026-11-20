package com.trova.backend.recommendation;

import com.trova.backend.congestion.SeoulCongestionApiClient;
import com.trova.backend.pipeline.PlaceTaggingRunner;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AlternativeExplanationBindingTest {
    @ParameterizedTest
    @CsvSource({"unset,false", "true,true", "false,false"})
    void 운영_YAML의_환경_변수가_서비스_Value에_바인딩된다(String setting, boolean expected) throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // 호스트 환경/시스템 속성의 영향을 제거하고 실제 운영 YAML만 읽는다. 앱/DB는 시작하지 않는다.
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            Map<String, Object> environment = setting.equals("unset") ? Map.of()
                    : Map.of("ALTERNATIVES_GEMINI_EXPLANATION_ENABLED", setting);
            context.getEnvironment().getPropertySources().addFirst(
                    new SystemEnvironmentPropertySource("testEnvironment", environment));
            new YamlPropertySourceLoader().load("application", new FileSystemResource("src/main/resources/application.yml"))
                    .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
            context.registerBean(AlternativeFinderService.class, () -> new AlternativeFinderService(
                    mock(GooglePlacesApiClient.class), mock(PlaceCatalogService.class), mock(TripPlaceRepository.class),
                    mock(PlaceTaggingRunner.class), mock(SeoulCongestionApiClient.class), mock(ApiCallLogService.class),
                    mock(PlaceEmbeddingService.class), mock(PersonalizationService.class)));
            context.refresh();
            assertThat(ReflectionTestUtils.getField(context.getBean(AlternativeFinderService.class), "geminiExplanationEnabled"))
                    .isEqualTo(expected);
        }
    }
}
