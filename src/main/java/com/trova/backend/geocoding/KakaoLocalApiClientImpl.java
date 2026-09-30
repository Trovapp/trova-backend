package com.trova.backend.geocoding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.function.Supplier;

@Component
public class KakaoLocalApiClientImpl implements KakaoLocalApiClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoLocalApiClientImpl.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MILLIS = 500;

    private final RestClient restClient;

    public KakaoLocalApiClientImpl(@Value("${app.kakao.rest-api-key}") String restApiKey) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        this.restClient = RestClient.builder()
                .baseUrl("https://dapi.kakao.com")
                .defaultHeader("Authorization", "KakaoAK " + restApiKey)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public KakaoKeywordSearchResponse searchKeyword(String query) {
        return withRetry(query, () -> doSearchKeyword(query));
    }

    @Override
    public KakaoAddressSearchResponse searchAddress(String query) {
        return withRetry(query, () -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/local/search/address.json")
                        .queryParam("query", query)
                        .build())
                .retrieve()
                .body(KakaoAddressSearchResponse.class));
    }

    @Override
    public KakaoKeywordSearchResponse searchKeywordNear(String query, double x, double y, int radiusMeters) {
        return withRetry(query, () -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/local/search/keyword.json")
                        .queryParam("query", query)
                        .queryParam("x", x)
                        .queryParam("y", y)
                        .queryParam("radius", radiusMeters)
                        .queryParam("sort", "distance")
                        .build())
                .retrieve()
                .body(KakaoKeywordSearchResponse.class));
    }

    private <T> T withRetry(String query, Supplier<T> call) {
        RuntimeException lastFailure = null;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return call.get();
            } catch (HttpClientErrorException.TooManyRequests
                     | HttpServerErrorException
                     | ResourceAccessException e) {
                lastFailure = e;
                if (attempt == MAX_ATTEMPTS - 1) {
                    break;
                }
                long delay = RETRY_BASE_DELAY_MILLIS * (attempt + 1);
                log.warn("카카오 로컬 API 일시 오류(query={}, {}/{}): {} — {}ms 후 재시도",
                        query, attempt + 1, MAX_ATTEMPTS, e.getMessage(), delay);
                sleep(delay);
            }
        }

        throw lastFailure;
    }

    private KakaoKeywordSearchResponse doSearchKeyword(String query) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/local/search/keyword.json")
                        .queryParam("query", query)
                        .build())
                .retrieve()
                .body(KakaoKeywordSearchResponse.class);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("카카오 로컬 API 재시도 대기 중 인터럽트", e);
        }
    }
}
