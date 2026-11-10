package com.trova.backend.config;

import io.micrometer.core.instrument.Clock;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 운영 지표를 서버가 직접 Grafana Cloud(OTLP)로 보낸다(#358, ADR 0039).
 * 956MiB 서버에서 alloy(약 60MiB)가 조회만으로도 스왑 읽기를 3회/초 → 199회/초로 늘려 alloy를 껐다(2026-11-10 운영 측정).
 *
 * - 운영에서만 켠다(APP_METRICS_OTLP_ENABLED=true). 로컬·테스트 지표가 운영 그래프에 섞이지 않게 기본은 끔.
 * - 보내는 값은 /actuator/prometheus와 같은 지표(공통 태그·HTTP 히스토그램 설정이 이 레지스트리에도 적용된다).
 * - 시간 단위는 초 — Prometheus 이름(_seconds)과 기존 대시보드 쿼리를 그대로 쓰기 위해(기본은 밀리초).
 * - service.name/service.instance.id가 Grafana에서 job/instance 라벨이 된다 — alloy 때와 같은 trova-prod.
 * - 서버가 죽으면 그냥 끊긴다(up 지표 없음). 다운 감지는 Grafana 외부 헬스 체크(trova-health)와 데이터 끊김 알림이 맡는다.
 * - 스프링 자동 설정(OtlpMetricsExportAutoConfiguration)은 OpenTelemetry SDK 모듈을 요구해 쓰지 않는다(TrovaBackendApplication에서 제외).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.metrics.otlp.enabled", havingValue = "true")
public class OtlpMetricsConfig {

    // alloy 수집 간격(60s)과 같게 — 대시보드의 rate 범위·무료 한도 계산이 그대로 맞는다.
    static final Duration STEP = Duration.ofMinutes(1);

    @Bean
    OtlpMeterRegistry otlpMeterRegistry(@Value("${app.metrics.otlp.url}") String url,
                                        @Value("${app.metrics.otlp.authorization}") String authorization,
                                        @Value("${app.metrics.otlp.service-name:trova-prod}") String serviceName) {
        return OtlpMeterRegistry.builder(config(url, authorization, serviceName, STEP)).clock(Clock.SYSTEM).build();
    }

    static OtlpConfig config(String url, String authorization, String serviceName, Duration step) {
        return new OtlpConfig() {
            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public String url() {
                return url;
            }

            @Override
            public Map<String, String> headers() {
                return Map.of("Authorization", authorization);
            }

            @Override
            public Map<String, String> resourceAttributes() {
                return Map.of("service.name", serviceName, "service.instance.id", serviceName);
            }

            @Override
            public Duration step() {
                return step;
            }

            @Override
            public TimeUnit baseTimeUnit() {
                return TimeUnit.SECONDS;
            }
        };
    }
}
