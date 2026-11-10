package com.trova.backend.config;

import com.sun.net.httpserver.HttpServer;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.User;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 예열이 홈 조회를 회차마다 순서대로, 고른 사용자의 토큰으로 보내는지 본다(#356).
 * 스프링 컨텍스트를 새로 띄우지 않는다 — 새 컨텍스트가 테스트 컨텍스트 캐시를 밀어내면 같은 메모리 DB를 쓰는
 * 다른 컨텍스트의 표가 create-drop으로 지워져 다른 테스트가 깨졌다(전체 빌드에서 확인).
 * 실제 서버로 8개 경로가 모두 200인 것은 RANDOM_PORT 통합 실행에서 확인했다(docs/experience-notes.md #356).
 */
class StartupWarmupTest {

    private static final String SECRET = "startup-warmup-test-secret-0123456789abcdef";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final TripRepository tripRepository = mock(TripRepository.class);
    private final JwtService jwtService = new JwtService(SECRET);
    private final StartupWarmup warmup = new StartupWarmup(
            userRepository, tripRepository, jwtService, mock(Environment.class), true);

    private HttpServer server;
    private final List<String> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            Long userId = jwtService.verify(auth.substring("Bearer ".length()));
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " user=" + userId);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool()); // 기본은 한 스레드라 동시 요청을 볼 수 없다
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static User user(long id) throws Exception {
        User user = new User("google", "u" + id, "사용자", null);
        Field field = User.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(user, id);
        return user;
    }

    @Test
    void 가장_최근_여행의_주인_토큰으로_홈_조회를_두_번씩_GET으로_보낸다() throws Exception {
        when(tripRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(new Trip(user(7), "최근 여행", null, null))));

        List<StartupWarmup.Result> results = warmup.run(server.getAddress().getPort());

        assertThat(results).hasSize(StartupWarmup.PATHS.size() * StartupWarmup.ROUNDS);
        assertThat(results).extracting(StartupWarmup.Result::status).containsOnly(200);
        assertThat(seen).hasSize(results.size()).allMatch(s -> s.startsWith("GET ") && s.endsWith(" user=7"));
        assertThat(seen.get(0)).as("로그인 확인이 먼저").isEqualTo("GET /api/auth/me user=7");
        assertThat(seen.subList(0, StartupWarmup.PATHS.size()))
                .containsExactlyInAnyOrderElementsOf(StartupWarmup.PATHS.stream().map(p -> "GET " + p + " user=7").toList());
    }

    @Test
    void 홈_API는_앱처럼_동시에_보낸다() throws Exception {
        when(tripRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(new Trip(user(7), "최근 여행", null, null))));
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        server.removeContext("/");
        server.createContext("/", exchange -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        warmup.run(server.getAddress().getPort());

        assertThat(maxInFlight.get()).isGreaterThan(1);
    }

    @Test
    void 여행이_없으면_가장_작은_번호의_사용자로() throws Exception {
        when(tripRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(userRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(user(1))));

        warmup.run(server.getAddress().getPort());

        assertThat(seen).isNotEmpty().allMatch(s -> s.endsWith(" user=1"));
    }

    @Test
    void 사용자가_없으면_요청하지_않는다() {
        when(tripRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(userRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        assertThat(warmup.run(server.getAddress().getPort())).isEmpty();
        assertThat(seen).isEmpty();
    }

    @Test
    void 서버_포트가_없거나_꺼져_있으면_시작하지_않는다() {
        new StartupWarmup(userRepository, tripRepository, jwtService, mock(Environment.class), true).onReady();
        Environment withPort = mock(Environment.class);
        when(withPort.getProperty("local.server.port", Integer.class)).thenReturn(server.getAddress().getPort());
        new StartupWarmup(userRepository, tripRepository, jwtService, withPort, false).onReady();

        assertThat(seen).isEmpty();
    }
}
