package com.trova.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 서버는 Caddy가 https를 받아 localhost:8080(http)으로 넘긴다. 서버가 X-Forwarded-Proto를 읽지 않으면
 * OAuth 돌아올 주소가 http://로 만들어져 구글이 redirect_uri_mismatch로 거부한다(#67).
 * 테스트용 application.yml이 본 설정을 대체하므로, 본 설정 값은 파일에서 직접 읽어 확인한다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.forward-headers-strategy=native")
class ForwardedHeadersIntegrationTest {

    @LocalServerPort private int port;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Test
    void 본_설정은_프록시가_보낸_헤더를_읽는다() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));
        Properties props = yaml.getObject();

        assertThat(props.getProperty("server.forward-headers-strategy")).isEqualTo("native");
    }

    @Test
    void 프록시가_https로_받은_요청이면_OAuth_돌아올_주소도_https다() throws Exception {
        assertThat(redirectUri("https")).startsWith("https://");
    }

    @Test
    void 프록시_헤더가_없으면_받은_그대로_http다() throws Exception {
        assertThat(redirectUri(null)).startsWith("http://");
    }

    private String redirectUri(String forwardedProto) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/oauth2/authorization/kakao"));
        if (forwardedProto != null) {
            request.header("X-Forwarded-Proto", forwardedProto);
        }
        HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(302);
        String location = response.headers().firstValue("Location").orElseThrow();
        String encoded = location.replaceAll(".*[?&]redirect_uri=([^&]*).*", "$1");
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }
}
