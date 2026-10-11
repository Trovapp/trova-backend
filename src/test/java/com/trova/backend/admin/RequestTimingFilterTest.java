package com.trova.backend.admin;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestTimingFilterTest {

    @Test
    void 처리되지_않은_예외로_끝난_요청은_500으로_센다() {
        // 컨트롤러 예외는 이 필터를 지날 때 응답 상태가 아직 200이고, 500은 그 뒤 오류 처리에서 붙는다.
        // 그대로 두면 장애 중 5xx 수가 0으로 남는다(리뷰 지적, #161).
        RequestTimingFilter filter = new RequestTimingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/places");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("DB 연결 끊김");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, failing)).isInstanceOf(IllegalStateException.class);

        List<RequestTimingFilter.Sample> samples = filter.drain();
        assertThat(samples).hasSize(1);
        assertThat(samples.get(0).status()).isEqualTo(500);
    }

    @Test
    void 정상_응답은_응답_상태_그대로_센다() throws Exception {
        RequestTimingFilter filter = new RequestTimingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/trips");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(404));

        assertThat(filter.drain()).extracting(RequestTimingFilter.Sample::status).containsExactly(404);
    }
}
