package com.trova.backend.service;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 자동 초안 메시지의 일수 판단(#136): 장소 일차 → 제목의 "N박 M일" → 둘 다 없으면 당일치기. */
class AutoDraftServiceTest {

    // processingJob은 non-null이어야 SavedPlace 생성자가 sourceUrl 등을 읽을 수 있다 — autoMessage는 쓰지 않는다.
    private static final ProcessingJob JOB = new ProcessingJob(null, "https://test", SourcePlatform.YOUTUBE);

    private static SavedPlace place(Integer day) {
        return new SavedPlace(JOB, null, "곳", "제주", "attraction", 33.0, 126.0, day, 1);
    }

    @Test
    void 영상에_일차_구분이_있으면_그_일수() {
        assertThat(AutoDraftService.autoMessage(List.of(place(1), place(3), place(2)), "제주 여행")).isEqualTo("2박 3일");
    }

    @Test
    void 일차가_없으면_제목의_박일() {
        assertThat(AutoDraftService.autoMessage(List.of(place(null)), "부산 1박 2일 코스")).isEqualTo("1박 2일");
    }

    @Test
    void 둘_다_없으면_당일치기() {
        assertThat(AutoDraftService.autoMessage(List.of(place(1)), null)).isEqualTo("당일치기");
    }
}
