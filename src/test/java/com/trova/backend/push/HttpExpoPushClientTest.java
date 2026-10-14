package com.trova.backend.push;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class HttpExpoPushClientTest {

    @Test
    void 티켓을_보낸_순서대로_오류_코드로_바꾼다() {
        String body = """
                {"data":[{"status":"ok","id":"a"},{"status":"error","message":"x","details":{"error":"DeviceNotRegistered"}}]}
                """;
        assertThat(HttpExpoPushClient.parseTickets(body)).isEqualTo(Arrays.asList(null, "DeviceNotRegistered"));
    }

    @Test
    void data가_배열이_아니면_빈_목록이라_모두_실패로_센다() {
        // 배열이 아닌 하나짜리 객체가 오면 그 칸들을 티켓처럼 세어 성공으로 잘못 셀 수 있었다(#200).
        assertThat(HttpExpoPushClient.parseTickets("{\"data\":{\"status\":\"ok\",\"id\":\"a\"}}")).isEmpty();
        assertThat(HttpExpoPushClient.parseTickets("{\"errors\":[{\"code\":\"VALIDATION_ERROR\"}]}")).isEmpty();
    }
}
