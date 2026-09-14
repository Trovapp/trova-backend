package com.trova.backend.service;

import com.trova.backend.entity.ApiCallLog;
import com.trova.backend.repository.ApiCallLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiCallMetricsServiceTest {

    @Mock
    private ApiCallLogRepository apiCallLogRepository;

    private ApiCallMetricsService service;

    @Test
    void 전체_요약과_operation별_통계를_계산한다() {
        service = new ApiCallMetricsService(apiCallLogRepository);
        when(apiCallLogRepository.findByCreatedAtAfter(any())).thenReturn(List.of(
                new ApiCallLog("gemini", "extract_places.collect", 1L, 800, true, null, 100, 20, 120),
                new ApiCallLog("gemini", "extract_places.collect", 2L, 1200, true, null, 150, 30, 180),
                new ApiCallLog("gemini", "verify_places", 3L, 500, false, "timeout", null, null, null),
                new ApiCallLog("kakao", "keyword_search", 1L, 50, true, null, null, null, null)
        ));

        ApiCallMetricsService.MetricsSummary summary = service.summarize(7);

        assertThat(summary.totalCalls()).isEqualTo(4);
        assertThat(summary.successCount()).isEqualTo(3);
        assertThat(summary.failureCount()).isEqualTo(1);
        assertThat(summary.avgLatencyMs()).isEqualTo((800 + 1200 + 500 + 50) / 4.0);
        assertThat(summary.totalPromptTokens()).isEqualTo(250);
        assertThat(summary.totalResponseTokens()).isEqualTo(50);
        assertThat(summary.totalTokens()).isEqualTo(300);

        assertThat(summary.byOperation()).hasSize(3);
        ApiCallMetricsService.OperationMetrics collect = summary.byOperation().stream()
                .filter(o -> o.operation().equals("extract_places.collect"))
                .findFirst().orElseThrow();
        assertThat(collect.count()).isEqualTo(2);
        assertThat(collect.successCount()).isEqualTo(2);
        assertThat(collect.provider()).isEqualTo("gemini");
        assertThat(collect.totalTokens()).isEqualTo(300);
    }

    @Test
    void 로그가_없으면_0으로_채워진_요약을_반환한다() {
        service = new ApiCallMetricsService(apiCallLogRepository);
        when(apiCallLogRepository.findByCreatedAtAfter(any())).thenReturn(List.of());

        ApiCallMetricsService.MetricsSummary summary = service.summarize(7);

        assertThat(summary.totalCalls()).isZero();
        assertThat(summary.avgLatencyMs()).isZero();
        assertThat(summary.byOperation()).isEmpty();
    }
}
