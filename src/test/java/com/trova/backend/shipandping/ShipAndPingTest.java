package com.trova.backend.shipandping;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.handler.HandlerExceptionResolverComposite;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShipAndPingTest {
    private final LinkedBlockingQueue<String> bodies = new LinkedBlockingQueue<>();
    private HttpServer server;
    private Sender sender;
    private URI endpoint;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach void 준비() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/ingest", exchange -> {
            if ("Bearer test-key".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                    && "application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/ingest");
    }

    @AfterEach void 정리() {
        if (sender != null) sender.close();
        server.stop(0);
    }

    private void 요청() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/trips/private-account");
        req.setQueryString("email=private-email");
        req.setContent("private-body".getBytes(StandardCharsets.UTF_8));
        req.setRemoteUser("private-user");
        req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/trips/{id}");
        new RequestFilter(sender).doFilter(req, new MockHttpServletResponse(), (r, s) -> {});
    }

    @Test void 오십번째_요청에서_개인정보_없이_보낸다() throws Exception {
        sender = new Sender("test-key", endpoint, "build", Duration.ofSeconds(30));
        for (int i = 0; i < 49; i++) 요청();
        assertThat(bodies.poll(100, TimeUnit.MILLISECONDS)).isNull();
        요청();
        String body = bodies.poll(5, TimeUnit.SECONDS);
        assertThat(body).contains("/api/trips/:id").doesNotContain("private", "error_kind", "error_message");
        assertThat(json.readTree(body).get("records").size()).isEqualTo(50);
    }

    @Test void 시간이_지나거나_종료하면_잔여_기록을_보낸다() throws Exception {
        sender = new Sender("test-key", endpoint, "build", Duration.ofMillis(200));
        요청();
        assertThat(bodies.poll(5, TimeUnit.SECONDS)).isNotNull();
        요청();
        sender.close();
        assertThat(bodies.poll()).isNotNull();
    }

    @Test void 키가_없으면_전송하지_않는다() throws Exception {
        sender = new Sender(" ", endpoint, "build", Duration.ofMillis(1));
        요청();
        sender.close();
        assertThat(bodies).isEmpty();
    }

    @Test void 오류는_클래스명만_보내고_수집주소와_SSE는_제외한다() throws Exception {
        sender = new Sender("test-key", endpoint, "build", Duration.ofSeconds(30));
        RequestFilter filter = new RequestFilter(sender);
        for (String path : new String[]{"/v1/ingest", "/v1/feature-map", "/api/trips/42/events"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
            filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> {});
        }
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/private-account");
        assertThatThrownBy(() -> filter.doFilter(req, new MockHttpServletResponse(), (r,s) -> {
            throw new IllegalStateException("private-message");
        })).isInstanceOf(IllegalStateException.class);
        sender.close();
        String body = bodies.poll();
        assertThat(body).contains("INTERNAL_SERVER_ERROR", "IllegalStateException").doesNotContain("private");
        assertThat(json.readTree(body).get("records").size()).isEqualTo(1);
    }

    @org.springframework.context.annotation.Profile("shipandping-standalone-test")
    @RestController
    static class ErrorEndpoint {
        @GetMapping("/api/trips/{id}")
        String trip() {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "private-detail");
        }
    }

    @Test void MVC에서_처리한_예외도_원래_클래스만_보낸다() throws Exception {
        sender = new Sender("test-key", endpoint, "build", Duration.ofSeconds(30));
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(new ErrorEndpoint())
                        .addFilters(new RequestFilter(sender)).build();
        HandlerExceptionResolverComposite composite =
                (HandlerExceptionResolverComposite)
                        mvc.getDispatcherServlet().getWebApplicationContext().getBean("handlerExceptionResolver");
        List<HandlerExceptionResolver> resolvers =
                new ArrayList<>(composite.getExceptionResolvers());
        new ShipAndPingConfig().extendHandlerExceptionResolvers(resolvers);
        composite.setExceptionResolvers(resolvers);
        mvc.perform(MockMvcRequestBuilders.get("/api/trips/private-id"))
                .andExpect(MockMvcResultMatchers.status().isConflict());
        sender.close();
        assertThat(bodies.poll()).contains("/api/trips/:id", "CONFLICT", "ResponseStatusException")
                .doesNotContain("private");
    }

    @Test void 보안_필터가_차단한_공유_요청도_토큰_없이_기록한다() throws Exception {
        sender = new Sender("test-key", endpoint, "build", Duration.ofSeconds(30));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/public/trips/private-token/join");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RequestFilter(sender).doFilter(req, response, (r, s) -> response.setStatus(403));
        sender.close();
        assertThat(bodies.poll()).contains("/api/public/trips/:id/join", "FORBIDDEN").doesNotContain("private-token");
    }

    @Test void 수집기가_응답을_보류해도_API는_완료되고_넘친_기록만_버린다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.removeContext("/v1/ingest");
        server.createContext("/v1/ingest", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        sender = new Sender("test-key", endpoint, "build", Duration.ofSeconds(30));
        RequestRecord record = new RequestRecord("request", "/api/trips", "GET", 200, 1, "now", null, null);
        for (int i = 0; i < 50; i++) sender.offer(record);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            요청();
            for (int i = 0; i < 1200; i++) sender.offer(record);
        } finally {
            release.countDown();
        }
        sender.close();
        assertThat(bodies.stream().mapToInt(body -> json.readTree(body).get("records").size()).sum()).isEqualTo(1050);
    }

    @Test void 크기와_건수_상한을_지키며_모든_기록을_보낸다() {
        sender = new Sender("test-key", endpoint, "build", Duration.ofSeconds(30));
        for (int i = 0; i < 180; i++) sender.offer(new RequestRecord("request", "/api/" + "가".repeat(700),
                "GET", 200, 5, "2026-11-26T00:00:00Z", null, null));
        sender.close();
        int total = 0;
        for (String body : bodies) {
            assertThat(body.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(65536);
            int count = json.readTree(body).get("records").size();
            assertThat(count).isLessThanOrEqualTo(100);
            total += count;
        }
        assertThat(total).isEqualTo(180);
    }
}
