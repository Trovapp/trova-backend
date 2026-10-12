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

    @Test
    void api가_아닌_요청은_세지_않는다() throws Exception {
        // 헬스체크·정적 요청이 섞이면 요청 수와 p50·p90이 실제보다 좋아 보인다.
        RequestTimingFilter filter = new RequestTimingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        assertThat(filter.drain()).isEmpty();
    }

    @Test
    void 되돌린_표본은_다음_스냅샷_때_다시_가져간다() {
        RequestTimingFilter filter = new RequestTimingFilter();
        filter.add(new RequestTimingFilter.Sample(10, 200));
        List<RequestTimingFilter.Sample> drained = filter.drain();
        filter.add(new RequestTimingFilter.Sample(20, 200));

        filter.restore(drained);

        assertThat(filter.drain()).extracting(RequestTimingFilter.Sample::durationMs).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    void 되돌릴_때도_표본_상한을_넘기지_않는다() {
        RequestTimingFilter filter = new RequestTimingFilter();
        for (int i = 0; i < 49_999; i++) {
            filter.add(new RequestTimingFilter.Sample(1, 200));
        }

        filter.restore(List.of(new RequestTimingFilter.Sample(2, 200), new RequestTimingFilter.Sample(3, 200)));

        assertThat(filter.drain()).hasSize(50_000);
    }
}
