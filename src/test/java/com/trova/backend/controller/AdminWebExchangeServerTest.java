package com.trova.backend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 서버(Tomcat)로 확인한다 — 오류 응답은 /error로 다시 넘겨 만들어지는데, 그 경로가 로그인 필요면 로그인 없이 부르는
 * 교환 API의 400·429가 모두 401로 바뀐다. MockMvc는 /error로 넘기지 않아 이걸 못 잡았다(#163 로컬 확인에서 발견).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.admin.rate-limit.exchange-per-minute=2")
class AdminWebExchangeServerTest {

    @LocalServerPort private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private int exchange(String code) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/exchange"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"" + code + "\"}"))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void 로그인_없이_부른_교환의_오류는_400과_429로_그대로_나간다() throws Exception {
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            statuses.add(exchange("wrong-" + i));
        }

        assertThat(statuses).containsExactly(400, 400, 429);
    }
}
