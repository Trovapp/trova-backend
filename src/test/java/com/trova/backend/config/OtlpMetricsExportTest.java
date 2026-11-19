package com.trova.backend.config;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 지표를 alloy 없이 서버가 직접 Grafana Cloud로 보낸다(#358).
 * 스프링 컨텍스트 전체를 띄우지 않고 지표 자동 설정 + OtlpMetricsConfig만 얹어 본다(테스트 컨텍스트 캐시를 밀어내지 않게).
 */
class OtlpMetricsExportTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class,
                    CompositeMeterRegistryAutoConfiguration.class, PrometheusMetricsExportAutoConfiguration.class))
            .withUserConfiguration(OtlpMetricsConfig.class, RequestDbQueryMetrics.class)
            .withPropertyValues(
                    "app.metrics.otlp.url=https://example.invalid/otlp/v1/metrics",
                    "app.metrics.otlp.authorization=Basic test",
                    "app.metrics.otlp.service-name=trova-prod",
                    "management.metrics.tags.application=trova-backend",
                    "management.metrics.distribution.percentiles-histogram.http.server.requests=true");

    @Test
    void 켜면_프로메테우스와_함께_직접_보내는_레지스트리가_생기고_공통_설정이_같이_적용된다() {
        runner.withPropertyValues("app.metrics.otlp.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(OtlpMeterRegistry.class);
            OtlpMeterRegistry otlp = context.getBean(OtlpMeterRegistry.class);
            MeterRegistry composite = context.getBean(CompositeMeterRegistry.class);
            Timer.builder("http.server.requests").tag("uri", "/api/places").register(composite).record(Duration.ofMillis(120));

            Timer sent = otlp.get("http.server.requests").timer();
            assertThat(sent.getId().getTag("application")).isEqualTo("trova-backend");
            assertThat(sent.takeSnapshot().histogramCounts()).as("p95 계산용 구간별 개수").isNotEmpty();
        });
    }

    @Test
    void 요청_SQL_분포와_기존_JVM_구성_지표가_OTLP_레지스트리에도_등록된다() {
        runner.withPropertyValues("app.metrics.otlp.enabled=true").run(context -> {
            MeterRegistry composite = context.getBean(MeterRegistry.class);
            RequestDbQueryMetrics metrics = context.getBean(RequestDbQueryMetrics.class);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/trips/123");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/trips/{id}");
            metrics.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                for (int i = 0; i < 5; i++) {
                    metrics.inspect("select 1");
                }
            });
            OtlpMeterRegistry otlp = context.getBean(OtlpMeterRegistry.class);
            DistributionSummary summary = otlp.get(RequestDbQueryMetrics.METRIC).summary();
            assertThat(summary.count()).isEqualTo(1);
            assertThat(summary.totalAmount()).isEqualTo(5);
            assertThat(summary.getId().getTag("uri")).isEqualTo("/api/trips/{id}");
            assertThat(summary.getId().getTag("application")).isEqualTo("trova-backend");
            // OTLP 스냅샷은 고정 경계 6개 뒤에 +Inf 버킷을 하나 더 낸다.
            assertThat(summary.takeSnapshot().histogramCounts()).extracting(bucket -> bucket.bucket())
                    .containsExactly(1.0, 3.0, 5.0, 7.0, 10.0, 20.0, Double.POSITIVE_INFINITY);

            // 운영은 Boot의 기존 binder를 재사용한다. 이 작은 컨텍스트에서만 직접 붙인다.
            new JvmMemoryMetrics().bindTo(composite);
            new JvmThreadMetrics().bindTo(composite);
            assertThat(otlp.find("jvm.memory.used").tag("area", "nonheap").gauges()).isNotEmpty();
            assertThat(otlp.get("jvm.buffer.memory.used").tag("id", "direct").gauge().value()).isGreaterThanOrEqualTo(0);
            assertThat(otlp.get("jvm.buffer.memory.used").tag("id", "mapped").gauge().value()).isGreaterThanOrEqualTo(0);
            assertThat(otlp.get("jvm.threads.live").gauge().value()).isPositive();
        });
    }

    @Test
    void 끄면_보내는_레지스트리를_만들지_않는다() {
        runner.withPropertyValues("app.metrics.otlp.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OtlpMeterRegistry.class));
    }

    @Test
    void 대시보드와_같은_이름_라벨이_되도록_초_단위와_trova_prod로_보낸다() {
        OtlpConfig config = OtlpMetricsConfig.config("https://example.invalid/otlp/v1/metrics", "Basic test", "trova-prod", Duration.ofMinutes(1));

        assertThat(config.validate().isValid()).isTrue();
        assertThat(config.baseTimeUnit()).isEqualTo(TimeUnit.SECONDS);
        assertThat(config.step()).isEqualTo(Duration.ofMinutes(1));
        assertThat(config.headers()).containsEntry("Authorization", "Basic test");
        assertThat(config.resourceAttributes())
                .containsEntry("service.name", "trova-prod")
                .containsEntry("service.instance.id", "trova-prod");
    }
}
