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

    // #420: 사용자 요청으로 20 → 10. 운영 .env에 QUOTA_SHARES_PER_DAY가 있으면 그 값이 이긴다.
    @Test
    void 설정이_없으면_사용자당_하루_링크_한도는_10개다() {
        assertThat(ReflectionTestUtils.getField(dailyQuotaService, "sharesPerDay")).isEqualTo(10);
    }

    @Test
    void 운영_설정_파일의_기본값도_10개다() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yml).contains("shares-per-day: ${QUOTA_SHARES_PER_DAY:10}");
    }
}
