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
    @Test
    void 비정상_티켓은_성공이_아니며_원래_순서의_실패로_남는다() {
        String body = """
                {"data":[{},null,"text",4,[],{"status":null},{"status":2},{"status":true},
                  {"status":"unknown"},{"status":"OK"},{"status":" ok "},{"status":"ok","id":"last"}]}
                """;
        assertThat(HttpExpoPushClient.parseTickets(body)).containsExactly(
                "InvalidTicket", "InvalidTicket", "InvalidTicket", "InvalidTicket", "InvalidTicket",
                "InvalidTicket", "InvalidTicket", "InvalidTicket", "InvalidTicket", "InvalidTicket", "InvalidTicket", null);
    }

    @Test
    void 오류_구조가_이상해도_실패를_유지하고_뒤의_정상_티켓을_해석한다() {
        String body = """
                {"data":[{"status":"error"},{"status":"error","details":{"error":null},"message":" "},
                 {"status":"error","details":{"error":{}},"message":[]},
                 {"status":"error","details":{"error":12},"message":"fallback"},
                 {"status":"error","details":{"error":"DeviceNotRegistered"}},
                 {"status":"ok","id":"last"}]}
                """;
        assertThat(HttpExpoPushClient.parseTickets(body)).containsExactly("error", "error", "error", "fallback", "DeviceNotRegistered", null);
    }

    @Test
    void 깨진_JSON과_없는_data는_성공_티켓을_만들지_않는다() {
        for (String body : Arrays.asList("", "not-json", "null", "[]", "{}", "{\"data\":null}")) {
            assertThat(HttpExpoPushClient.parseTickets(body)).isEmpty();
        }
    }

}
