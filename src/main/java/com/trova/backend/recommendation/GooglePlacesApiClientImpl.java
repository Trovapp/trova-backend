package com.trova.backend.recommendation;

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
import java.util.Map;
import java.util.function.Supplier;

@Component
public class GooglePlacesApiClientImpl implements GooglePlacesApiClient {

    private static final Logger log = LoggerFactory.getLogger(GooglePlacesApiClientImpl.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MILLIS = 500;
    private static final int MAX_RESULT_COUNT = 20;

    // 비용을 낮은 티어(Basic + Pro Data)로만 유지하려고 최소 필드만 요청한다.
    // editorialSummary 등 Enterprise 티어 필드는 의도적으로 뺐다.
    private static final String SEARCH_FIELD_MASK = String.join(",",
            "places.id", "places.displayName", "places.types", "places.rating",
            "places.userRatingCount", "places.priceLevel", "places.location", "places.formattedAddress");

    // reviews는 Enterprise + Atmosphere 티어라 유료 — getDetails()에서만 요청한다.
    private static final String DETAILS_FIELD_MASK = "id,reviews";

    private final RestClient restClient;

    public GooglePlacesApiClientImpl(@Value("${app.google.places-api-key}") String apiKey) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        this.restClient = RestClient.builder()
                .baseUrl("https://places.googleapis.com")
                .defaultHeader("X-Goog-Api-Key", apiKey)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public GooglePlacesNearbySearchResponse searchNearby(double latitude, double longitude, double radiusMeters) {
        return withRetry(() -> doSearchNearby(latitude, longitude, radiusMeters));
    }

    @Override
    public GooglePlacesNearbySearchResponse searchText(String query) {
        return withRetry(() -> doSearchText(query));
    }

    @Override
    public GooglePlacesDetailsResponse getDetails(String googlePlaceId) {
        return withRetry(() -> doGetDetails(googlePlaceId));
    }

    private <T> T withRetry(Supplier<T> call) {
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
                log.warn("Google Places API 일시 오류({}/{}): {} — {}ms 후 재시도",
                        attempt + 1, MAX_ATTEMPTS, e.getMessage(), delay);
                sleep(delay);
            }
        }

        throw lastFailure;
    }

    private GooglePlacesNearbySearchResponse doSearchNearby(double latitude, double longitude, double radiusMeters) {
        Map<String, Object> body = Map.of(
                "maxResultCount", MAX_RESULT_COUNT,
                "languageCode", "ko",
                "regionCode", "KR",
                "locationRestriction", Map.of(
                        "circle", Map.of(
                                "center", Map.of("latitude", latitude, "longitude", longitude),
                                "radius", radiusMeters
                        )
                )
        );

        return restClient.post()
                .uri("/v1/places:searchNearby")
                .header("X-Goog-FieldMask", SEARCH_FIELD_MASK)
                .body(body)
                .retrieve()
                .body(GooglePlacesNearbySearchResponse.class);
    }

    private GooglePlacesNearbySearchResponse doSearchText(String query) {
        Map<String, Object> body = Map.of(
                "textQuery", query,
                "maxResultCount", MAX_RESULT_COUNT,
                "languageCode", "ko",
                "regionCode", "KR"
        );

        return restClient.post()
                .uri("/v1/places:searchText")
                .header("X-Goog-FieldMask", SEARCH_FIELD_MASK)
                .body(body)
                .retrieve()
                .body(GooglePlacesNearbySearchResponse.class);
    }

    private GooglePlacesDetailsResponse doGetDetails(String googlePlaceId) {
        return restClient.get()
                .uri("/v1/places/{id}?languageCode=ko&regionCode=KR", googlePlaceId)
                .header("X-Goog-FieldMask", DETAILS_FIELD_MASK)
                .retrieve()
                .body(GooglePlacesDetailsResponse.class);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Google Places API 재시도 대기 중 인터럽트", e);
        }
    }
}
