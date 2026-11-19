package com.trova.backend.config;

import com.trova.backend.entity.ApiCallLog;
import com.trova.backend.repository.ApiCallLogJdbcRepository;
import com.trova.backend.repository.PlaceEmbeddingJdbcRepository;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.servlet.ServletException;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.HandlerMapping;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RequestDbQueryMetricsTest {

    @Test
    void 직접_JDBC_배치도_SQL_항목_수를_더하고_빈_배치는_더하지_않는다() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            RequestDbQueryMetrics metrics = new RequestDbQueryMetrics(registry);
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            ApiCallLogJdbcRepository logs = new ApiCallLogJdbcRepository(jdbc);
            PlaceEmbeddingJdbcRepository embeddings = new PlaceEmbeddingJdbcRepository(jdbc);
            ApiCallLog log = new ApiCallLog("gemini", "test", null, 1, true, null, null, null, null);
            logs.insertCopies(log, 7);
            metrics.doFilter(request("/api/trips/1", "/api/trips/{id}"), new MockHttpServletResponse(), (req, res) -> {
                metrics.inspect("select 1");
                logs.insertCopies(log, 3);
                embeddings.updateEmbeddings(Map.of(1L, "[1.0]", 2L, "[0.5]"));
                logs.insertCopies(log, 0);
                embeddings.updateEmbeddings(Map.of());
            });
            assertThat(summary(registry).count()).isEqualTo(1);
            assertThat(summary(registry).totalAmount()).isEqualTo(6);
        } finally {
            registry.close();
        }
    }

    @Test
    void SQL을_변경하지_않고_요청_밖과_자식_스레드의_SQL은_세지_않는다() throws Exception {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            RequestDbQueryMetrics metrics = new RequestDbQueryMetrics(registry);
            String sql = "select 1";
            assertThat(metrics.inspect(sql)).isSameAs(sql);
            metrics.doFilter(request("/api/trips/123", "/api/trips/{id}"), new MockHttpServletResponse(), (req, res) -> {
                metrics.inspect(sql);
                Thread background = new Thread(() -> metrics.inspect("select background"));
                background.start();
                try {
                    background.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ServletException(e);
                }
                metrics.inspect(sql);
            });
            metrics.inspect("select after_request");
            DistributionSummary summary = summary(registry);
            assertThat(summary.count()).isEqualTo(1);
            assertThat(summary.totalAmount()).isEqualTo(2);
            Map<String, Object> settings = new HashMap<>();
            metrics.customize(settings);
            assertThat(settings).containsEntry(AvailableSettings.STATEMENT_INSPECTOR, metrics);
        } finally {
            registry.close();
        }
    }

    @Test
    void ID와_쿼리문자열이_달라도_템플릿과_method_두_태그와_여섯_경계만_만든다() throws Exception {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            RequestDbQueryMetrics metrics = new RequestDbQueryMetrics(registry);
            for (int id = 0; id < 100; id++) {
                MockHttpServletRequest request = request("/api/trips/" + id, "/api/trips/{id}");
                request.setQueryString("token=secret-" + id);
                metrics.doFilter(request, new MockHttpServletResponse(), (req, res) -> metrics.inspect("select 1"));
            }
            DistributionSummary summary = summary(registry);
            assertThat(summary.count()).isEqualTo(100);
            assertThat(summary.totalAmount()).isEqualTo(100);
            assertThat(registry.find(RequestDbQueryMetrics.METRIC).meters()).hasSize(1);
            assertThat(summary.getId().getTags()).containsExactlyInAnyOrder(
                    Tag.of("uri", "/api/trips/{id}"), Tag.of("method", "GET"));
            assertThat(summary.takeSnapshot().histogramCounts()).extracting(bucket -> bucket.bucket())
                    .containsExactly(1.0, 3.0, 5.0, 7.0, 10.0, 20.0);
            assertThat(registry.scrape()).contains("trova_db_queries_per_request_bucket").doesNotContain("secret-");
        } finally {
            registry.close();
        }
    }

    @Test
    void 예외가_나도_정리하고_같은_스레드의_다음_요청은_영_회를_기록한다() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            RequestDbQueryMetrics metrics = new RequestDbQueryMetrics(registry);
            assertThatThrownBy(() -> metrics.doFilter(request("/api/trips/1", "/api/trips/{id}"),
                    new MockHttpServletResponse(), (req, res) -> {
                        metrics.inspect("select 1");
                        throw new ServletException("failure");
                    })).isInstanceOf(ServletException.class);
            metrics.inspect("select outside");
            metrics.doFilter(request("/api/trips/2", "/api/trips/{id}"), new MockHttpServletResponse(), (req, res) -> { });
            assertThat(summary(registry).count()).isEqualTo(2);
            assertThat(summary(registry).totalAmount()).isEqualTo(1);
        } finally {
            registry.close();
        }
    }

    @Test
    void 매핑_없는_요청과_다른_경로와_알_수_없는_method와_서블릿_비동기는_기록하지_않는다() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            RequestDbQueryMetrics metrics = new RequestDbQueryMetrics(registry);
            metrics.doFilter(request("/api/not-found/secret", null), new MockHttpServletResponse(), (req, res) -> metrics.inspect("select 1"));
            metrics.doFilter(request("/actuator/health", "/actuator/health"), new MockHttpServletResponse(), (req, res) -> metrics.inspect("select 1"));
            MockHttpServletRequest unknownMethod = request("/api/trips/1", "/api/trips/{id}");
            unknownMethod.setMethod("unbounded-user-input");
            metrics.doFilter(unknownMethod, new MockHttpServletResponse(), (req, res) -> metrics.inspect("select 1"));
            MockHttpServletRequest async = request("/api/trips/1", "/api/trips/{id}");
            async.setAsyncSupported(true);
            metrics.doFilter(async, new MockHttpServletResponse(), (req, res) -> req.startAsync());
            assertThat(registry.find(RequestDbQueryMetrics.METRIC).meters()).isEmpty();
        } finally {
            registry.close();
        }
    }

    @Test
    void 설정으로_끄면_필터와_inspector를_함께_제거한다() {
        new ApplicationContextRunner().withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withUserConfiguration(RequestDbQueryMetrics.class)
                .withPropertyValues("app.metrics.db-queries.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RequestDbQueryMetrics.class));
    }

    @Test
    void 계측이_꺼져_있으면_JDBC_훅은_요청_카운터를_읽지_않고_바로_빠진다() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RequestDbQueryMetrics metrics = new RequestDbQueryMetrics(registry);
        boolean saved = RequestDbQueryMetrics.jdbcHookEnabled;
        try {
            metrics.doFilter(request("/api/trips/1", "/api/trips/{id}"), new MockHttpServletResponse(), (req, res) -> {
                RequestDbQueryMetrics.recordJdbcStatements(2);
                // 같은 JVM의 다른 테스트 컨텍스트가 빈을 갖고 있을 수 있어 플래그를 직접 내려 "빈 없음"을 흉내 낸다.
                RequestDbQueryMetrics.jdbcHookEnabled = false;
                // 요청 카운터가 ThreadLocal에 있어도 읽지 않으므로 더해지지 않는다.
                RequestDbQueryMetrics.recordJdbcStatements(5);
                RequestDbQueryMetrics.jdbcHookEnabled = true;
            });
            assertThat(summary(registry).count()).isEqualTo(1);
            assertThat(summary(registry).totalAmount()).isEqualTo(2);
        } finally {
            RequestDbQueryMetrics.jdbcHookEnabled = saved;
            metrics.destroy();
            registry.close();
        }
    }

    @Test
    void 빈이_만들어지면_훅이_켜지고_destroy가_두_번_불려도_한_번만_뺀다() {
        int before = RequestDbQueryMetrics.liveInstances();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RequestDbQueryMetrics metrics = new RequestDbQueryMetrics(registry);
        try {
            assertThat(RequestDbQueryMetrics.liveInstances()).isEqualTo(before + 1);
            assertThat(RequestDbQueryMetrics.jdbcHookEnabled).isTrue();
            metrics.destroy();
            metrics.destroy();
            assertThat(RequestDbQueryMetrics.liveInstances()).isEqualTo(before);
            assertThat(RequestDbQueryMetrics.jdbcHookEnabled).isEqualTo(before > 0);
        } finally {
            registry.close();
        }
    }

    @Test
    void application_yml_기본값이면_켜지고_환경값_false면_꺼진다() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withUserConfiguration(RequestDbQueryMetrics.class);
        // 테스트 리소스의 application.yml이 운영 설정을 가리므로 운영 파일을 직접 읽는다(Gradle 작업 폴더 = 프로젝트 루트).
        String mainConfig = "spring.config.location=file:src/main/resources/application.yml";
        runner.withSystemProperties(mainConfig).run(context -> {
            assertThat(context.getEnvironment().getProperty("app.metrics.db-queries.enabled")).isEqualTo("true");
            assertThat(context).hasSingleBean(RequestDbQueryMetrics.class);
        });
        // 시스템 속성은 relaxed binding을 거치지 않으므로 application.yml의 ${APP_METRICS_DB_QUERIES_ENABLED} 자리표시자로만 키에 닿는다.
        runner.withSystemProperties(mainConfig, "APP_METRICS_DB_QUERIES_ENABLED=false")
                .run(context -> {
                    assertThat(context.getEnvironment().getProperty("app.metrics.db-queries.enabled")).isEqualTo("false");
                    assertThat(context).doesNotHaveBean(RequestDbQueryMetrics.class);
                });
    }

    private MockHttpServletRequest request(String path, String template) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (template != null) {
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, template);
        }
        return request;
    }

    private DistributionSummary summary(MeterRegistry registry) {
        return registry.get(RequestDbQueryMetrics.METRIC).tags("uri", "/api/trips/{id}", "method", "GET").summary();
    }
}
