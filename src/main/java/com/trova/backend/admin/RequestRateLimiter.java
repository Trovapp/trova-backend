package com.trova.backend.admin;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 키(IP·관리자 번호 등)별 분당 요청 수 제한 — 1분 고정 창 카운터(#159·#163). 서버 1대라 메모리로 충분하다.
 * 창 경계에서 최대 2배까지 몰릴 수 있지만, 관리자 웹 몇 명 규모에서는 막는 목적(무작위 대입·폭주)에 충분하다.
 */
@Component
public class RequestRateLimiter {

    // 이 수를 넘으면 지난 창의 키를 정리한다 — IP별 키가 끝없이 쌓이지 않게(감으로 정함, 실측 아님).
    private static final int CLEANUP_THRESHOLD = 10_000;

    private record Window(long minute, int count) {
    }

    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RequestRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean tryAcquire(String key, int limitPerMinute) {
        long minute = clock.millis() / 60_000;
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.values().removeIf(w -> w.minute() < minute);
        }
        Window updated = windows.compute(key, (k, w) ->
                w == null || w.minute() != minute ? new Window(minute, 1) : new Window(minute, w.count() + 1));
        return updated.count() <= limitPerMinute;
    }
}
