package com.trova.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DailyQuotaDefaultTest {

    @Autowired
    private DailyQuotaService dailyQuotaService;

    // #420: 사용자 요청으로 20 → 10. #431: 하루 → 최근 7일 창(설정 이름도 바뀌어 운영 .env의 옛 QUOTA_SHARES_PER_DAY는 더 이상 안 먹는다).
    @Test
    void 설정이_없으면_사용자당_최근_7일_링크_한도는_10개다() {
        assertThat(ReflectionTestUtils.getField(dailyQuotaService, "sharesPerWeek")).isEqualTo(10);
    }

    @Test
    void 운영_설정_파일의_기본값도_10개다() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yml).contains("shares-per-week: ${QUOTA_SHARES_PER_WEEK:10}");
    }
}
