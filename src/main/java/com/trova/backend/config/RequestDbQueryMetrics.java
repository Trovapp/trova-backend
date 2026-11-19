package com.trova.backend.config;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 동기 API 요청 스레드에서 Hibernate가 준비하는 SQL + 직접 JDBC batch에 제출하는 SQL 항목 수.
 * SQL 문자열은 저장하거나 변경하지 않는다. DB 내부 SQL·다른 스레드의 작업은 포함하지 않는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@ConditionalOnProperty(name = "app.metrics.db-queries.enabled", havingValue = "true", matchIfMissing = true)
public class RequestDbQueryMetrics extends OncePerRequestFilter
        implements StatementInspector, HibernatePropertiesCustomizer {

    static final String METRIC = "trova.db.queries.per.request";
    private final MeterRegistry registry;
    // 일반 ThreadLocal: @Async·Kafka·스케줄 작업에는 요청 카운터를 전달하지 않는다.
    private static final ThreadLocal<QueryCount> CURRENT = new ThreadLocal<>();
    private final ConcurrentMap<String, ConcurrentMap<String, DistributionSummary>> summaries = new ConcurrentHashMap<>();

    public RequestDbQueryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.STATEMENT_INSPECTOR, this);
    }

    @Override
    public String inspect(String sql) {
        recordJdbcStatements(1);
        return sql;
    }

    /** 직접 JDBC 저장소의 batch 항목 수. 요청이 없거나 계측이 꺼져 있으면 아무것도 하지 않는다. */
    public static void recordJdbcStatements(int statements) {
        QueryCount count = CURRENT.get();
        if (count != null) {
            count.value += statements;
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        QueryCount count = new QueryCount();
        CURRENT.set(count);
        try {
            chain.doFilter(request, response);
        } finally {
            // 예외·스레드 풀 재사용 때도 다음 요청에 값이 남지 않게 기록보다 먼저 제거한다.
            CURRENT.remove();
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (!request.isAsyncStarted() && pattern instanceof String uri && uri.startsWith("/api/")
                    && supportedMethod(request.getMethod())) {
                summary(uri, request.getMethod()).record(count.value);
            }
        }
    }

    private DistributionSummary summary(String uri, String method) {
        ConcurrentMap<String, DistributionSummary> methods = summaries.computeIfAbsent(uri, ignored -> new ConcurrentHashMap<>());
        DistributionSummary existing = methods.get(method);
        if (existing != null) {
            return existing;
        }
        return methods.computeIfAbsent(method, key -> DistributionSummary.builder(METRIC)
                .description("SQL statements submitted per synchronous mapped API request (Hibernate and direct JDBC batches)")
                .tags("uri", uri, "method", key)
                // 자동 생성 버킷·클라이언트 percentile 없이 고정 경계만 사용한다(0 SQL도 기록).
                .serviceLevelObjectives(1, 3, 5, 7, 10, 20)
                .register(registry));
    }

    private boolean supportedMethod(String method) {
        return switch (method) {
            case "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE" -> true;
            default -> false;
        };
    }

    private static final class QueryCount {
        private long value;
    }
}
