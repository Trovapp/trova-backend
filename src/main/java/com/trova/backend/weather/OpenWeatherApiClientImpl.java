package com.trova.backend.weather;

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

@Component
public class OpenWeatherApiClientImpl implements OpenWeatherApiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenWeatherApiClientImpl.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MILLIS = 500;

    private final RestClient restClient;
    private final String apiKey;

    public OpenWeatherApiClientImpl(@Value("${app.weather.openweather-api-key}") String apiKey) {
        this.apiKey = apiKey;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        this.restClient = RestClient.builder()
                .baseUrl("https://api.openweathermap.org")
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public OpenWeatherForecastResponse forecast(double latitude, double longitude) {
        RuntimeException lastFailure = null;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return doForecast(latitude, longitude);
            } catch (HttpClientErrorException.TooManyRequests
                     | HttpServerErrorException
                     | ResourceAccessException e) {
                lastFailure = e;
                if (attempt == MAX_ATTEMPTS - 1) {
                    break;
                }
                long delay = RETRY_BASE_DELAY_MILLIS * (attempt + 1);
                log.warn("OpenWeatherMap API 일시 오류({}/{}): {} — {}ms 후 재시도",
                        attempt + 1, MAX_ATTEMPTS, e.getMessage(), delay);
                sleep(delay);
            }
        }

        throw lastFailure;
    }

    private OpenWeatherForecastResponse doForecast(double latitude, double longitude) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/data/2.5/forecast")
                        .queryParam("lat", latitude)
                        .queryParam("lon", longitude)
                        .queryParam("appid", apiKey)
                        .queryParam("units", "metric")
                        .build())
                .retrieve()
                .body(OpenWeatherForecastResponse.class);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenWeatherMap API 재시도 대기 중 인터럽트", e);
        }
    }
}
