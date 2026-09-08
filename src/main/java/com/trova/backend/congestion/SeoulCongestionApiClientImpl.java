package com.trova.backend.congestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;

/**
 * 서울 열린데이터광장 "서울시 실시간 도시데이터"(citydata) API — 서울 116~121곳
 * 주요 명소만 커버한다. 실패해도 대안 찾기 전체를 막지 않고 조용히 빈 값을
 * 반환한다(WeatherRecoveryService의 findIndoorAlternatives가 쓰던 것과 같은
 * "부가 기능은 실패해도 무시" 원칙).
 */
@Component
public class SeoulCongestionApiClientImpl implements SeoulCongestionApiClient {

    private static final Logger log = LoggerFactory.getLogger(SeoulCongestionApiClientImpl.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final String apiKey;

    public SeoulCongestionApiClientImpl(@Value("${app.congestion.seoul-opendata-api-key}") String apiKey) {
        this.apiKey = apiKey;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .baseUrl("http://openapi.seoul.go.kr:8088")
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public Optional<SeoulCongestionResponse> fetchCongestion(String areaName) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        try {
            SeoulCongestionResponse response = restClient.get()
                    .uri("/{key}/json/citydata/1/5/{areaName}", apiKey, areaName)
                    .retrieve()
                    .body(SeoulCongestionResponse.class);
            return Optional.ofNullable(response);
        } catch (Exception e) {
            log.warn("서울 혼잡도 API 조회 실패({}) — 배지 없이 진행", areaName, e);
            return Optional.empty();
        }
    }
}
