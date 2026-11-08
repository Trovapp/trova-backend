package com.trova.backend.controller;

import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.PropertiesMeterFilter;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class HttpHistogramConfigurationTest {

    @Test
    void 운영설정으로_HTTP_타이머의_Prometheus_버킷을_내보낸다() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("main", new FileSystemResource("src/main/resources/application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        MetricsProperties properties = Binder.get(environment).bind("management.metrics", MetricsProperties.class).get();

        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            registry.config().meterFilter(new PropertiesMeterFilter(properties));
            Timer requests = Timer.builder("http.server.requests")
                    .tags("method", "GET", "uri", "/api/places", "status", "200")
                    .register(registry);
            requests.record(Duration.ofMillis(100));
            requests.record(Duration.ofMillis(800));
            Timer.builder("trova.unrelated").register(registry).record(Duration.ofMillis(20));

            assertThat(requests.count()).isEqualTo(2);
            assertThat(registry.scrape())
                    .contains("http_server_requests_seconds_bucket{")
                    .contains("le=\"+Inf\"")
                    .doesNotContain("trova_unrelated_seconds_bucket{");
        } finally {
            registry.close();
        }
    }
}
