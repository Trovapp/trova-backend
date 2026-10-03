package com.trova.backend.planner;

import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GeminiJsonClientImplTest {

    private static final String URL = GeminiJsonClientImpl.BASE_URL + "/v1beta/models/" + GeminiJsonClientImpl.MODEL + ":generateContent";
    private static final String OK_BODY = """
            {"candidates":[{"content":{"parts":[{"text":"{\\"days\\": 2}"}]}}],
             "usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":8,"totalTokenCount":128}}
            """;

    @Test
    void JSON_응답을_요청하고_429면_기다렸다_다시_시도한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));
        ApiCallLogService logs = mock(ApiCallLogService.class);
        List<Long> sleeps = new ArrayList<>();
        GeminiJsonClientImpl client = new GeminiJsonClientImpl("k", builder, GeminiJsonClientImpl.BASE_URL, logs, sleeps::add);

        assertThat(client.generateJson("p", "trip-plan.test")).contains("{\"days\": 2}");
        assertThat(sleeps).containsExactly(2_000L);
        verify(logs).record(eq("gemini"), eq("trip-plan.test"), isNull(), anyLong(), eq(true), isNull(), eq(120), eq(8), eq(128));
        server.verify();
    }

    @Test
    void 다시_시도해도_소용없는_오류는_바로_실패로_기록한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        ApiCallLogService logs = mock(ApiCallLogService.class);
        List<Long> sleeps = new ArrayList<>();
        GeminiJsonClientImpl client = new GeminiJsonClientImpl("k", builder, GeminiJsonClientImpl.BASE_URL, logs, sleeps::add);

        assertThat(client.generateJson("p", "trip-plan.test")).isEmpty();
        assertThat(sleeps).isEmpty();
        verify(logs).record(eq("gemini"), eq("trip-plan.test"), isNull(), anyLong(), eq(false), any(), isNull(), isNull(), isNull());
        server.verify();
    }

    @Test
    void 대기_시간은_30초를_넘지_않는다() {
        // 상한 확인: 2초 × 2^n 이 30초를 넘는 회차에서도 30초
        assertThat(Math.min(GeminiJsonClientImpl.MAX_DELAY_MS, GeminiJsonClientImpl.BASE_DELAY_MS * (1L << 5))).isEqualTo(30_000L);
    }
}
