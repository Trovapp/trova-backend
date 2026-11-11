package com.trova.backend.config;

import com.trova.backend.entity.Trip;
import com.trova.backend.entity.User;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 서버를 켠 직후 첫 홈 조회가 5~10배 느렸다(#356, 2026-11-09 운영: /api/auth/me 1.42초 → 두 번째 0.12초,
 * 홈 API 5개 1.5~2.2초 → 0.2~0.4초). 처음 한 번 지나가는 경로(보안 필터·컨트롤러·JPA 쿼리·JSON 변환)의
 * 클래스 적재·JIT가 원인이라 보고, 요청을 받기 시작하자마자 같은 GET들을 이 서버(127.0.0.1)에 직접 보내 미리 지나가게 한다.
 *
 * - 읽기 전용 GET만 부른다(쓰기·외부 API 없음). 토큰은 메모리에서만 만들고 남기지 않는다.
 * - 사용자는 가장 최근 여행의 주인 — 데이터가 없는 사용자면 목록이 비어 일찍 끝나 엔티티 읽기·JSON 변환이 데워지지 않는다
 *   (운영 가장 작은 번호 사용자는 여행·찜 0개였다). 여행이 하나도 없으면 가장 작은 번호의 사용자.
 * - 127.0.0.1이라 IP 요청 제한(IpRateLimitFilter)에서 빠진다. 요청 수 지표(/actuator/prometheus)에는 재시작마다 이만큼 더해진다.
 * - 별도 스레드에서 돌려 시작을 늦추지 않는다. 실패해도 로그만 남긴다.
 */
@Component
public class StartupWarmup {

    private static final Logger log = LoggerFactory.getLogger(StartupWarmup.class);

    // 앱 홈을 열 때 부르는 조회(로그인 확인 → 홈 5개 → 알림·찜 폴더).
    static final List<String> PATHS = List.of(
            "/api/auth/me", "/api/bookmarks", "/api/trips", "/api/trip-drafts/auto", "/api/places",
            "/api/places/pending", "/api/notifications", "/api/bookmarks/folders");
    // 앱처럼 동시에·5회로 늘린 적이 있다(#363). 첫 화면 효과가 없어 순서대로·2회로 되돌렸다(#368).
    // 영상 분석 중 조회가 무너지는 것도 예열 탓으로 봤지만, 예열을 끈 운영 측정에서도 똑같이 무너져 원인이 아니었다(#370, 2026-11-11).
    static final int ROUNDS = 2;

    private final UserRepository userRepository;
    private final TripRepository tripRepository;
    private final JwtService jwtService;
    private final Environment environment;
    private final boolean enabled;

    public StartupWarmup(UserRepository userRepository, TripRepository tripRepository, JwtService jwtService, Environment environment,
                         @Value("${app.warmup.enabled:true}") boolean enabled) {
        this.userRepository = userRepository;
        this.tripRepository = tripRepository;
        this.jwtService = jwtService;
        this.environment = environment;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Integer port = environment.getProperty("local.server.port", Integer.class);
        if (!enabled || port == null || port <= 0) {
            return;
        }
        Thread thread = new Thread(() -> run(port), "startup-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    /** 홈 조회를 ROUNDS번 보내고 회차별 걸린 시간을 남긴다. 테스트에서 직접 부른다. */
    List<Result> run(int port) {
        List<Result> results = new ArrayList<>();
        User user = warmupUser();
        if (user == null) {
            log.info("예열 건너뜀: 사용자 없음");
            return results;
        }
        String token = jwtService.issue(user);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        for (int round = 1; round <= ROUNDS; round++) {
            long roundStart = System.nanoTime();
            for (String path : PATHS) {
                results.add(call(client, port, token, round, path));
            }
            log.info("예열 {}회차: {}ms ({})", round, (System.nanoTime() - roundStart) / 1_000_000, summary(results, round));
        }
        return results;
    }

    User warmupUser() {
        List<Trip> latestTrip = tripRepository.findAll(PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "id"))).getContent();
        if (!latestTrip.isEmpty()) {
            return latestTrip.get(0).getUser();
        }
        List<User> first = userRepository.findAll(PageRequest.of(0, 1, Sort.by("id"))).getContent();
        return first.isEmpty() ? null : first.get(0);
    }

    private Result call(HttpClient client, int port, String token, int round, String path) {
        long start = System.nanoTime();
        int status;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .GET().build();
            status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            status = -1;
            log.warn("예열 요청 실패: {} {}", path, e.getClass().getSimpleName());
        }
        return new Result(round, path, status, (System.nanoTime() - start) / 1_000_000);
    }

    private static String summary(List<Result> results, int round) {
        StringBuilder sb = new StringBuilder();
        for (Result r : results) {
            if (r.round() == round) {
                sb.append(r.path()).append('=').append(r.status()).append('/').append(r.millis()).append("ms ");
            }
        }
        return sb.toString().trim();
    }

    record Result(int round, String path, int status, long millis) {
    }
}
