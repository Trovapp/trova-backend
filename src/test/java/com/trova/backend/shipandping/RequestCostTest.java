package com.trova.backend.shipandping;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"SHIP_AND_PING_ALERT_TEST=true", "spring.application.name=local-cost-test"})
@Import(AlertTestEndpointsTest.LocalCollector.class)
@EnabledIfEnvironmentVariable(named = "TROVA_SP_BENCH", matches = "1")
class RequestCostTest {
    @LocalServerPort private int port;

    @Test void 내장_Tomcat의_요청당_왕복_시간을_잰다() throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + "/api/_test/slow");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).build();
        long[] samples = new long[7];
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            for (int round = -3; round < samples.length; round++) {
                long start = System.nanoTime();
                for (int i = 0; i < 1000; i++) {
                    int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                    assertThat(status).isEqualTo(200);
                }
                if (round >= 0) samples[round] = (System.nanoTime() - start) / 1000;
            }
        }
        Arrays.sort(samples);
        System.out.println("MEDIAN_HTTP_NS_PER_REQUEST=" + samples[samples.length / 2]);
    }
}
