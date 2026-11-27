package com.trova.backend.shipandping;

import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "SHIP_AND_PING_ALERT_TEST=true", "server.tomcat.threads.max=1", "server.tomcat.threads.min-spare=1"
})
@Import(AlertTestEndpointsTest.LocalCollector.class)
class AlertTestEndpointsTest {
    @LocalServerPort int port;
    @Autowired LocalCollector collector;

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalCollector implements AutoCloseable, WebMvcConfigurer {
        private final CountDownLatch slowDispatched = new CountDownLatch(1);
        private final HttpServer server;
        private final LinkedBlockingQueue<JsonNode> records = new LinkedBlockingQueue<>();

        LocalCollector() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/ingest", exchange -> {
                try (exchange) {
                    JsonNode body = JsonMapper.builder().build().readTree(exchange.getRequestBody());
                    body.get("records").forEach(records::add);
                    exchange.sendResponseHeaders(202, -1);
                }
            });
            server.start();
        }

        @Override public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new AsyncHandlerInterceptor() {
                @Override public void afterConcurrentHandlingStarted(HttpServletRequest request,
                        HttpServletResponse response, Object handler) {
                    slowDispatched.countDown();
                }
            }).addPathPatterns("/api/_test/slow");
        }

        @Bean Sender localSender() {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/ingest");
            return new Sender("test", uri, "test", Duration.ofMillis(50));
        }

        @Bean FilterRegistrationBean<RequestFilter> localRequestFilter(Sender sender) {
            return new ShipAndPingConfig().shipAndPingFilter(sender);
        }

        @Override public void close() {
            server.stop(0);
        }
    }

    @Test
    void 익명_오류와_지연_요청을_실제_경로와_완료_시간으로_전송한다() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            long start = System.nanoTime();
            CompletableFuture<HttpResponse<Void>> delayed = client.sendAsync(
                    request("/api/_test/slow"), HttpResponse.BodyHandlers.discarding());
            assertThat(collector.slowDispatched.await(10, TimeUnit.SECONDS)).isTrue();
            // slow가 대기하는 동안 유일한 서블릿 스레드가 fail을 먼저 처리한다.
            HttpResponse<String> failed = client.send(request("/api/_test/fail?sample=private-fixture"),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(failed.statusCode()).isEqualTo(500);
            JsonNode body = JsonMapper.builder().build().readTree(failed.body());
            assertThat(body.get("code").asString()).isEqualTo("INTERNAL_SERVER_ERROR");
            assertThat(body.get("status").asInt()).isEqualTo(500);
            assertThat(body.get("message").asString()).isNotBlank();
            assertThat(body.get("traceId").asString()).isNotBlank();
            assertThat(failed.headers().firstValue("X-Trace-Id")).contains(body.get("traceId").asString());
            assertThat(failed.body()).doesNotContain("private-fixture");
            assertThat(assertRecord("/api/_test/fail", 500).toString()).doesNotContain("private-fixture");

            assertThat(delayed.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofSeconds(6));
            JsonNode slow = assertRecord("/api/_test/slow", 200);
            assertThat(slow.get("duration_ms").asLong()).isGreaterThanOrEqualTo(6000);
        }
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).GET().build();
    }

    private JsonNode assertRecord(String route, int status) throws InterruptedException {
        JsonNode row = collector.records.poll(5, TimeUnit.SECONDS);
        assertThat(row).isNotNull();
        assertThat(row.get("route").asString()).isEqualTo(route);
        assertThat(row.get("method").asString()).isEqualTo("GET");
        assertThat(row.get("status").asInt()).isEqualTo(status);
        return row;
    }
}
