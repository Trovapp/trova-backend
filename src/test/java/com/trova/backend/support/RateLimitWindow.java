package com.trova.backend.support;

/**
 * 요청 횟수 제한은 1분 고정 창(RequestRateLimiter)이라, 테스트가 분이 바뀌는 순간에 걸리면 카운트가 새로 시작돼 429 단언이 가끔 실패한다.
 * 시계 빈을 고정하면 "오늘" 날짜를 쓰는 다른 테스트에 영향이 가므로, 분의 마지막 10초면 다음 분까지 기다렸다 시작한다(#163 리뷰 지적).
 */
public final class RateLimitWindow {

    private static final long MINUTE_MS = 60_000;
    private static final long SAFE_MS = 50_000;

    private RateLimitWindow() {
    }

    public static void awaitFreshMinute() throws InterruptedException {
        long intoMinute = System.currentTimeMillis() % MINUTE_MS;
        if (intoMinute >= SAFE_MS) {
            Thread.sleep(MINUTE_MS - intoMinute + 100);
        }
    }
}
