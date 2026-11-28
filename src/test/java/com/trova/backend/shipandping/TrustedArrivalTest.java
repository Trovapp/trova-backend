package com.trova.backend.shipandping;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"SHIP_AND_PING_ALERT_TEST=true", "test.trust-loopback=true"})
@Import(AlertTestEndpointsTest.LocalCollector.class)
class TrustedArrivalTest {
    @LocalServerPort int port;
    @Autowired AlertTestEndpointsTest.LocalCollector collector;

    @Test void 루프백_피어의_프록시_수신시각을_우선한다() throws Exception {
        assertThat(queueFrom("127.0.0.1")).isGreaterThanOrEqualTo(5000L);
    }

    @Test void 다른_피어는_루프백을_주장해도_Tomcat_시각을_쓴다() throws Exception {
        InetAddress local = NetworkInterface.networkInterfaces().flatMap(NetworkInterface::inetAddresses)
                .filter(address -> address instanceof Inet4Address && !address.isLoopbackAddress())
                .findFirst().orElse(null);
        assumeTrue(local != null, "비루프백 로컬 인터페이스가 필요하다");
        assertThat(queueFrom(local.getHostAddress())).isLessThan(5000L);
    }

    private long queueFrom(String localAddress) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + localAddress + ":" + port + "/api/_test/slow"))
                    .header("X-Request-Start", Long.toString(System.currentTimeMillis() - 5000))
                    .header("X-Forwarded-For", "127.0.0.1")
                    .GET().timeout(Duration.ofSeconds(15)).build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            JsonNode row = collector.nextRecord();
            assertThat(row.get("route").asString()).isEqualTo("/api/_test/slow");
            assertThat(row.has("queue_ms")).isTrue();
            return row.get("queue_ms").asLong();
        }
    }
}
