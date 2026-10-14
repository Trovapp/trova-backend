package com.trova.backend.push;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PushMessagesTest {

    @Test
    void 당일_초안_문구와_이동() {
        PushMessage m = PushMessages.draftReady(12L, 5, 1);
        assertThat(m.title()).isEqualTo("일정 초안이 준비됐어요");
        assertThat(m.body()).isEqualTo("영상 속 장소 5곳으로 당일 일정을 짰어요. 확인하고 여행에 담아 보세요.");
        assertThat(m.open()).isEqualTo("draft:12");
        assertThat(m.tag()).isEqualTo("draft-12");
    }

    @Test
    void 여러_날_초안은_박_일로() {
        assertThat(PushMessages.draftReady(1L, 9, 3).body())
                .isEqualTo("영상 속 장소 9곳으로 2박 3일 일정을 짰어요. 확인하고 여행에 담아 보세요.");
    }

    @Test
    void 비_소식_문구는_사람이_읽는_날짜로() {
        PushMessage m = PushMessages.rain("제주 2박 3일", LocalDate.of(2026, 10, 19), 0.7, "성산일출봉", 45L, 678L, 90L);
        assertThat(m.title()).isEqualTo("제주 2박 3일에 비 소식이 있어요");
        assertThat(m.body()).isEqualTo("10월 19일(월) 강수확률 70%예요. 성산일출봉 대신 갈 실내 장소를 볼까요?");
        assertThat(m.open()).isEqualTo("trip:45:place:678");
        assertThat(m.tag()).isEqualTo("rain-90");
    }
}
