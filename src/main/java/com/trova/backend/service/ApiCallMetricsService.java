package com.trova.backend.service;

import com.trova.backend.entity.ApiCallLog;
import com.trova.backend.repository.ApiCallLogRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ApiCallLog(0-4 원칙으로 처음부터 기록해둔 Gemini/카카오 호출 로그)를 집계해서
 * 포트폴리오용 트래픽/비용 근거를 바로 볼 수 있게 한다. Postgres를 직접 조회하지
 * 않아도 이 하나의 엔드포인트로 요약을 볼 수 있음(Grafana는 시계열, 이건 스냅샷).
 */
@Service
public class ApiCallMetricsService {

    private final ApiCallLogRepository apiCallLogRepository;

    public ApiCallMetricsService(ApiCallLogRepository apiCallLogRepository) {
        this.apiCallLogRepository = apiCallLogRepository;
    }

    public record OperationMetrics(
            String provider, String operation, long count, long successCount,
            double avgLatencyMs, long totalTokens
    ) {
    }

    public record MetricsSummary(
            long totalCalls, long successCount, long failureCount, double avgLatencyMs,
            long totalPromptTokens, long totalResponseTokens, long totalTokens,
            List<OperationMetrics> byOperation
    ) {
    }

    public MetricsSummary summarize(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<ApiCallLog> logs = apiCallLogRepository.findByCreatedAtAfter(since);

        long totalCalls = logs.size();
        long successCount = logs.stream().filter(ApiCallLog::isSuccess).count();
        double avgLatency = logs.stream().mapToLong(ApiCallLog::getLatencyMs).average().orElse(0.0);
        long totalPromptTokens = sumNullable(logs, ApiCallLog::getPromptTokens);
        long totalResponseTokens = sumNullable(logs, ApiCallLog::getResponseTokens);
        long totalTokens = sumNullable(logs, ApiCallLog::getTotalTokens);

        Map<String, List<ApiCallLog>> grouped = logs.stream()
                .collect(Collectors.groupingBy(l -> l.getProvider() + "|" + l.getOperation()));

        List<OperationMetrics> byOperation = grouped.entrySet().stream()
                .map(entry -> {
                    String[] key = entry.getKey().split("\\|", 2);
                    List<ApiCallLog> group = entry.getValue();
                    return new OperationMetrics(
                            key[0], key[1], group.size(),
                            group.stream().filter(ApiCallLog::isSuccess).count(),
                            group.stream().mapToLong(ApiCallLog::getLatencyMs).average().orElse(0.0),
                            sumNullable(group, ApiCallLog::getTotalTokens));
                })
                .sorted(Comparator.comparingLong(OperationMetrics::count).reversed())
                .toList();

        return new MetricsSummary(
                totalCalls, successCount, totalCalls - successCount, avgLatency,
                totalPromptTokens, totalResponseTokens, totalTokens, byOperation);
    }

    private long sumNullable(List<ApiCallLog> logs, java.util.function.Function<ApiCallLog, Integer> extractor) {
        return logs.stream().mapToLong(l -> {
            Integer v = extractor.apply(l);
            return v != null ? v : 0;
        }).sum();
    }
}
