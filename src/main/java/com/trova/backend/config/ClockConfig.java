package com.trova.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * "오늘" 같은 날짜 판단의 기준 시간대. 서비스 대상이 한국(카카오 지오코딩)이라 한국 시간으로 고정한다 —
 * 배포 서버의 기본 시간대가 UTC여도 한국 자정 기준으로 날짜가 바뀌어야 한다(#39).
 */
@Configuration
public class ClockConfig {

    public static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(SERVICE_ZONE);
    }
}
