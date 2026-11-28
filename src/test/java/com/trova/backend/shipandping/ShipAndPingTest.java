package com.trova.backend.shipandping;

import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.cors.CorsConfiguration;
import java.util.Properties;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Path;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockAsyncContext;
import tools.jackson.databind.JsonNode;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.test.util.ReflectionTestUtils;
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

    @Test void 프로필은_같은_인증으로_변경시에만_보내고_쉬는_분도_기록한다(
            @TempDir Path dir) throws Exception {
        String credential = UUID.randomUUID().toString();
        LinkedBlockingQueue<String> profiles = new LinkedBlockingQueue<>();
        server.createContext("/v1/profile", ex -> {
            if ("POST".equals(ex.getRequestMethod()) &&
                    ("Bearer " + credential).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                profiles.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.removeContext("/v1/ingest");
        server.createContext("/v1/ingest", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(503, -1);
            ex.close();
        });
        for (int startup = 0; startup < 2; startup++) {
            sender = new Sender(credential, endpoint, "build", Duration.ofSeconds(30));
            sender.connect(AppProfile.current(), dir.resolve("receipt"), Map::of, Duration.ofMillis(35));
            String batch = bodies.poll(5, TimeUnit.SECONDS);
            assertThat(batch).isNotNull();
            assertThat(json.readTree(batch).get("records").size()).isZero();
            assertThat(json.readTree(batch).get("resources").isObject()).isTrue();
            assertThat(json.readTree(batch).get("platform").asString()).isEqualTo("server");
            sender.close();
            bodies.clear();
        }
        assertThat(profiles).hasSize(1);
        String profile = profiles.remove();
        assertThat(profile).doesNotContain(credential, "://", "@");
        JsonNode tree = json.readTree(profile);
        assertThat(tree.get("services").size()).isLessThanOrEqualTo(40);
        for (JsonNode service : tree.get("services")) {
            for (String field : List.of("feature", "name")) {
                assertThat(service.get(field).asString()).matches("[가-힣A-Za-z0-9 ]{1,40}");
            }
            assertThat(service.get("kind").asString()).isIn("map", "payment", "ai", "mail", "storage", "other");
        }
        assertThat(tree.get("rollback").asString()).matches("[가-힣A-Za-z0-9 ]{1,80}");
        assertThat(tree.get("metrics").asString()).matches("[가-힣A-Za-z0-9 ]{1,120}");
        assertThat(json.readTree(profile)).isEqualTo(json.readTree("""
                {"language":"Java","framework":"Spring Boot","database":"postgres",
                 "deploy":"JAR 교체 후 서비스 재시작","rollback":"직전 백엔드 JAR 복원","metrics":"Grafana에서 서버 지표 확인",
                 "services":[{"feature":"장소 검색","kind":"map","name":"Google Places"},
                             {"feature":"대화 메시지 보내기","kind":"ai","name":"Gemini"},
                             {"feature":"공유 영상 분석 요청","kind":"map","name":"Kakao Local"},
                             {"feature":"테스트 푸시 보내기","kind":"other","name":"Expo Push"}]}
                """));
        sender = new Sender(credential, endpoint, "build", Duration.ofSeconds(30));
        sender.connect(Map.of("language", "Java"), dir.resolve("receipt"), Map::of, Duration.ofMillis(35));
        assertThat(json.readTree(profiles.poll(5, TimeUnit.SECONDS)).size()).isOne();
    }

    @Test void 자원은_실측한_개수와_구간의_GC_정지합을_보낸다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            Timer pauses = registry.timer("jvm.gc.pause");
            pauses.record(30, TimeUnit.MILLISECONDS);
            ServerResources sampler = new ServerResources(registry);
            registry.gauge("hikaricp.connections.active", new AtomicInteger(3));
            pauses.record(7, TimeUnit.MILLISECONDS);
            Map<String, Number> values = sampler.get();
            assertThat(values.get("gc_pause_ms").doubleValue()).isEqualTo(7);
            assertThat(values.get("db_pool_in_use").intValue()).isEqualTo(3);
            assertThat(values).doesNotContainKeys("db_pool_max", "event_loop_lag_ms");
            for (Map.Entry<String, Number> field : values.entrySet()) {
                if (field.getKey().endsWith("_pct")) assertThat(field.getValue().doubleValue()).isBetween(0.0, 100.0);
            }
        } finally {
            registry.close();
        }
    }

    @Test void 요청별_DB와_외부_시간은_합친_뒤_상한을_적용한다() {
        RequestIoTime.Totals times = new RequestIoTime.Totals();
        assertThat(times.dbMillis()).isNull();
        assertThat(times.externalMillis()).isNull();
        times.db(950000);
        times.db(950000);
        times.external(5);
        times.external(6);
        assertThat(times.dbMillis()).isEqualTo(1);
        assertThat(times.externalMillis()).isEqualTo(11);
        times.db(TimeUnit.MINUTES.toNanos(20));
        times.external(Long.MAX_VALUE);
        assertThat(times.dbMillis()).isEqualTo(600000);
        assertThat(times.externalMillis()).isEqualTo(600000);
    }

    @Test void 프록시의_유효한_시각만_사용한다() {
        long now = 1800000000123L;
        assertThat(ArrivalValve.arrival(now, now - 15, "t=1800000000.001")).isEqualTo(now - 122);
        assertThat(ArrivalValve.arrival(now, now - 15, "1800000000000")).isEqualTo(now - 123);
        for (String invalid : List.of("0", "1800000000124", "1799999400122", "junk", "1e12", "-1")) {
            assertThat(ArrivalValve.arrival(now, now - 15, invalid)).isEqualTo(now - 15);
        }
        assertThat(ArrivalValve.arrival(now, -1, null)).isZero();
    }

    @Test void 수신부터_기한을_재고_늦은_완료를_중복_기록하지_않는다() throws Exception {
        sender = new Sender("test-key", endpoint, "build", Duration.ofMillis(15));
        RequestFilter filter = new RequestFilter(sender, 220);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/trips/1");
        request.setAttribute(ArrivalValve.RECEIVED, System.currentTimeMillis() - 180);
        filter.doFilter(request, new MockHttpServletResponse(), (r, s) -> {
            try {
                String batch = bodies.poll(4, TimeUnit.SECONDS);
                assertThat(batch).isNotNull();
                JsonNode row = json.readTree(batch).get("records").get(0);
                assertThat(row.get("timeout_ms").asInt()).isEqualTo(220);
                assertThat(row.get("error_kind").asString()).isEqualTo("timeout");
                assertThat(row.get("status").asInt()).isZero();
                assertThat(row.get("queue_ms").asLong()).isGreaterThanOrEqualTo(180);
                assertThat(row.get("duration_ms").asLong()).isGreaterThanOrEqualTo(220);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });
        sender.close();
        assertThat(bodies).isEmpty();
    }

    @Test void 수신부터_응답까지_대기시간을_한번만_포함한다() throws Exception {
        sender = new Sender("test-key", endpoint, "build", Duration.ofSeconds(30));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/trips/1");
        long received = System.currentTimeMillis() - 500;
        request.setAttribute(ArrivalValve.RECEIVED, received);
        new RequestFilter(sender).doFilter(request, new MockHttpServletResponse(), (r, s) ->
                ((RequestFilter.Observation) r.getAttribute(RequestFilter.ACTIVE)).handlerStarted());
        long upper = System.currentTimeMillis() - received + 2;
        sender.close();
        JsonNode record = json.readTree(bodies.remove()).get("records").get(0);
        assertThat(record.get("queue_ms").asLong()).isBetween(499L, upper);
        assertThat(record.get("duration_ms").asLong()).isBetween(record.get("queue_ms").asLong(), upper);
    }

    @Test void 수신시각_없는_요청과_비동기_끊김을_한번씩_기록한다() throws Exception {
        sender = new Sender("test-key", endpoint, "build", Duration.ofSeconds(30));
        요청();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/trips/1");
        request.setAsyncSupported(true);
        new RequestFilter(sender).doFilter(request, new MockHttpServletResponse(), (r, s) -> r.startAsync());
        MockAsyncContext context = (MockAsyncContext) request.getAsyncContext();
        for (AsyncListener listener : context.getListeners()) {
            listener.onError(new AsyncEvent(context, null, null,
                    new ClientAbortException("private")));
        }
        context.complete();
        sender.close();
        JsonNode records = json.readTree(bodies.remove()).get("records");
        assertThat(records.size()).isEqualTo(2);
        assertThat(records.get(0).has("queue_ms")).isFalse();
        assertThat(records.get(0).get("timeout_ms").asInt()).isEqualTo(10000);
        assertThat(records.get(1).get("error_kind").asString()).isEqualTo("aborted");
        assertThat(records.get(1).get("status").asInt()).isZero();
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

    @Test void 오류는_클래스명만_보내고_수집주소는_제외한다() throws Exception {
        sender = new Sender("test-key", endpoint, "build", Duration.ofSeconds(30));
        RequestFilter filter = new RequestFilter(sender);
        for (String path : new String[]{"/v1/ingest", "/v1/feature-map"}) {
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

    @Test void 스트림_경로에서_거절과_JSON_응답과_연결_수립을_기록한다() throws Exception {
        sender = new Sender("test-key", endpoint, "build", Duration.ofSeconds(30));
        RequestFilter filter = new RequestFilter(sender);
        for (int status : List.of(429, 503, 200, 201)) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/trips/private-id/events");
            MockHttpServletResponse res = new MockHttpServletResponse();
            req.setAsyncSupported(true);
            filter.doFilter(req, res, (r, s) -> {
                res.setStatus(status);
                res.setContentType(status == 200 ? "application/json" : "text/event-stream;charset=UTF-8");
                if (status == 201) req.startAsync();
            });
            if (req.isAsyncStarted()) {
                ((MockAsyncContext) req.getAsyncContext()).complete();
            }
        }
        sender.close();
        String body = bodies.poll();
        assertThat(body).isNotNull().contains("/api/trips/:id/events").doesNotContain("private-id");
        assertThat(json.readTree(body).get("records").valueStream()
                .map(row -> row.get("status").asInt()).toList()).containsExactly(429, 503, 200, 201);
    }

    @Test void 로그아웃은_인증_경로로_기록하고_OPTIONS는_제외한다() throws Exception {
        sender = new Sender("test-key", endpoint, "build", Duration.ofSeconds(30));
        RequestFilter filter = new RequestFilter(sender);
        LogoutFilter logout =
                new LogoutFilter(
                        (req, res, auth) -> res.setStatus(204), (req, res, auth) -> {});
        logout.setLogoutRequestMatcher(req -> "POST".equals(req.getMethod())
                && "/api/auth/logout".equals(req.getRequestURI()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/auth/logout"), response,
                (req, res) -> logout.doFilter(req, res, (r, s) -> {}));
        assertThat(response.getStatus()).isEqualTo(204);
        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/api/public/trips/private-token");
        preflight.addHeader("Origin", "https://example.org");
        preflight.addHeader("Access-Control-Request-Method", "GET");
        CorsFilter cors = new CorsFilter(
                req -> new CorsConfiguration().applyPermitDefaultValues());
        filter.doFilter(preflight, new MockHttpServletResponse(), (req, res) -> cors.doFilter(req, res, (r, s) -> {}));
        sender.close();
        String body = bodies.poll();
        assertThat(body).contains("/api/auth/logout").doesNotContain("private-token", "OPTIONS");
        assertThat(json.readTree(body).get("records").size()).isOne();
    }

    @Test void 같은_커밋으로_빈을_다시_만들어도_커밋_해시를_쓴다() {
        Properties properties = new Properties();
        properties.setProperty("commit", "ae2d1f0");
        BuildProperties build = new BuildProperties(properties);
        ShipAndPingConfig config = new ShipAndPingConfig();
        try (Sender first = config.shipAndPingSender(build); Sender second = config.shipAndPingSender(build)) {
            assertThat(ReflectionTestUtils.getField(first, "version")).isEqualTo("ae2d1f0");
            assertThat(ReflectionTestUtils.getField(second, "version")).isEqualTo("ae2d1f0");
        }
    }

    @Test void 커밋_속성이_없으면_unknown을_쓴다() {
        BuildProperties build = new BuildProperties(new Properties());
        try (Sender sender = new ShipAndPingConfig().shipAndPingSender(build)) {
            assertThat(ReflectionTestUtils.getField(sender, "version")).isEqualTo("unknown");
        }
    }

    @Test void 빌드에_기록된_커밋은_일곱_자리_해시_또는_unknown이다() throws Exception {
        Properties properties = PropertiesLoaderUtils.loadProperties(new ClassPathResource("META-INF/build-info.properties"));
        assertThat(properties.getProperty("build.commit")).matches("[0-9a-f]{7}|unknown");
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
                Thread.sleep(550);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        sender = new Sender("test-key", endpoint, "build", Duration.ofSeconds(30));
        RequestRecord record = new RequestRecord("request", "/api/trips", "GET", 200, 1, "now", null, null, null, 10000, null, null);
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

    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    @Test void 종료_기한이_지나면_대기와_전송중_건수만_남긴다(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.removeContext("/v1/ingest");
        server.createContext("/v1/ingest", exchange -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        sender = new Sender("test-key", endpoint, "build", Duration.ofSeconds(30), Duration.ofMillis(100));
        RequestRecord record = new RequestRecord("request", "/api/trips", "GET", 200, 1, "now", null, null, null, 10000, null, null);
        try {
            for (int i = 0; i < 50; i++) sender.offer(record);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            sender.offer(record);
            long start = System.nanoTime();
            sender.close();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            assertThat(output.getOut()).contains("전송 미확인 51건 폐기");
        } finally {
            release.countDown();
        }
    }

    @Test void 크기와_건수_상한을_지키며_모든_기록을_보낸다() {
        sender = new Sender("test-key", endpoint, "build", Duration.ofSeconds(30));
        for (int i = 0; i < 180; i++) sender.offer(new RequestRecord("request", "/api/" + "가".repeat(700),
                "GET", 200, 5, "2026-11-26T00:00:00Z", null, null, null, 10000, null, null));
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
