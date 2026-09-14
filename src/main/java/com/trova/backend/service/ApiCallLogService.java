package com.trova.backend.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.ApiCallLog;
import com.trova.backend.repository.ApiCallLogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 유료 전환 가능성이 있는 외부 API(Gemini, 카카오 등) 호출 1건당 지연시간·토큰·성공여부를
 * 기록한다. 무료 티어를 쓰고 있어도 기록해서, 나중에 트래픽당 예상 비용을 계산할 근거로
 * 남긴다. 로깅 실패가 파이프라인 본 동작을 절대 막아선 안 되므로, 이 클래스의 메서드는
 * 예외를 던지지 않고 문제가 있으면 경고 로그만 남긴다.
 *
 * DB 기록과 별도로 Micrometer 지표(trova.api_call)도 같이 남겨서, Postgres를 직접
 * 조회하지 않아도 Grafana에서 호출 현황을 바로 볼 수 있게 한다.
 */
@Service
public class ApiCallLogService {

    private static final Logger log = LoggerFactory.getLogger(ApiCallLogService.class);
    private static final String MARKER_PREFIX = "TROVA_API_LOG:";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ApiCallLogRepository apiCallLogRepository;
    private final MeterRegistry meterRegistry;

    public ApiCallLogService(ApiCallLogRepository apiCallLogRepository, MeterRegistry meterRegistry) {
        this.apiCallLogRepository = apiCallLogRepository;
        this.meterRegistry = meterRegistry;
    }

    public void record(
            String provider, String operation, Long jobId, long latencyMs, boolean success,
            String errorMessage, Integer promptTokens, Integer responseTokens, Integer totalTokens
    ) {
        apiCallLogRepository.save(new ApiCallLog(
                provider, operation, jobId, latencyMs, success, errorMessage,
                promptTokens, responseTokens, totalTokens));

        Timer.builder("trova.api_call")
                .tag("provider", provider)
                .tag("operation", operation)
                .tag("success", String.valueOf(success))
                .register(meterRegistry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Python 파이프라인 스크립트가 stderr에 {@code TROVA_API_LOG:{...json...}} 형태로 남긴
     * 줄만 골라 파싱해서 저장한다. 마커가 없는 일반 로그 줄은 무시하고, JSON이 깨진 줄은
     * 경고만 남기고 건너뛴다(다른 줄 처리에는 영향 없음).
     */
    public void recordFromStderr(String stderrContent, Long jobId) {
        if (stderrContent == null || stderrContent.isBlank()) {
            return;
        }

        for (String line : stderrContent.split("\\R")) {
            if (!line.startsWith(MARKER_PREFIX)) {
                continue;
            }

            String json = line.substring(MARKER_PREFIX.length());
            try {
                LogLinePayload payload = MAPPER.readValue(json, LogLinePayload.class);
                record(
                        payload.provider(), payload.operation(), jobId, payload.latencyMs(), payload.success(),
                        payload.errorMessage(), payload.promptTokens(), payload.responseTokens(), payload.totalTokens());
            } catch (Exception e) {
                log.warn("API 호출 로그 줄 파싱 실패, 건너뜁니다: {}", json, e);
            }
        }
    }

    private record LogLinePayload(
            String provider, String operation, long latencyMs, boolean success, String errorMessage,
            Integer promptTokens, Integer responseTokens, Integer totalTokens
    ) {
    }
}
