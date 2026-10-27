package com.trova.backend.config;

import com.trova.backend.admin.RequestRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IP별 분당 요청 제한(#239). 설정을 바꾼 Spring 컨텍스트를 새로 띄우면 테스트 컨텍스트 캐시에서 다른 컨텍스트가 밀려나며
 * 공용 H2 스키마를 지워 다른 테스트가 깨져서, 필터만 직접 부른다. 기준은 3건으로 낮춘다.
 */
class IpRateLimitFilterTest {

    private final IpRateLimitFilter filter = new IpRateLimitFilter(
            new RequestRateLimiter(Clock.fixed(Instant.parse("2026-10-27T05:00:00Z"), ZoneId.of("UTC"))), 3);

    private MockHttpServletResponse call(String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/places");
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void 같은_IP가_기준을_넘으면_429로_끊는다() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(call("203.0.113.7").getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse blocked = call("203.0.113.7");
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("60");
        assertThat(blocked.getContentAsString()).contains("요청이 너무 많아요");
    }

    @Test
    void 다른_IP는_따로_센다() throws Exception {
        for (int i = 0; i < 3; i++) {
            call("203.0.113.8");
        }
        assertThat(call("203.0.113.9").getStatus()).isEqualTo(200);
    }

    @Test
    void 서버_안에서_부르는_요청은_세지_않는다() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(call("127.0.0.1").getStatus()).isEqualTo(200);
            assertThat(call("::1").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void 기준이_0이면_끈다() throws Exception {
        IpRateLimitFilter off = new IpRateLimitFilter(new RequestRateLimiter(Clock.systemUTC()), 0);
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/places");
            request.setRemoteAddr("203.0.113.10");
            MockHttpServletResponse response = new MockHttpServletResponse();
            off.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }
}
