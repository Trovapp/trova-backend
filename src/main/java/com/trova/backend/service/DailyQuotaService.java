package com.trova.backend.service;

import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 사용자별 하루 호출 한도(#33). Gemini 무료 한도(하루 1,000회)는 전체 사용자가 함께 쓰고, Google Places는
 * 무료 크레딧을 넘으면 과금되므로 한 사용자가 이를 소진하지 못하게 막는다. 하루의 기준은 한국 시간 자정.
 *
 * - 링크 제출: 오늘 만든 처리 작업 수를 DB에서 센다(서버를 재시작해도 유지).
 * - 장소 API(Google Places를 부르는 조회): 메모리에서 센다 — 서버 재시작 시 초기화되지만 남용을 막는 데는 충분하다.
 */
@Service
public class DailyQuotaService {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private final ProcessingJobRepository processingJobRepository;
    private final int sharesPerDay;
    private final int placeCallsPerDay;
    private final Clock clock;
    private final Map<String, AtomicInteger> placeCallCounts = new ConcurrentHashMap<>();

    @Autowired
    public DailyQuotaService(
            ProcessingJobRepository processingJobRepository,
            @Value("${app.quota.shares-per-day:20}") int sharesPerDay,
            @Value("${app.quota.place-calls-per-day:100}") int placeCallsPerDay
    ) {
        this(processingJobRepository, sharesPerDay, placeCallsPerDay, Clock.systemDefaultZone());
    }

    DailyQuotaService(ProcessingJobRepository processingJobRepository, int sharesPerDay, int placeCallsPerDay, Clock clock) {
        this.processingJobRepository = processingJobRepository;
        this.sharesPerDay = sharesPerDay;
        this.placeCallsPerDay = placeCallsPerDay;
        this.clock = clock;
    }

    /** 링크를 하나 더 제출할 수 있는지 — 오늘 만든 처리 작업이 한도 이상이면 예외. */
    public void checkShare(User user) {
        // createdAt은 서버 기본 시간대의 LocalDateTime으로 저장되므로, 한국 자정을 서버 시간대로 바꿔 비교한다.
        LocalDateTime startOfToday = LocalDate.now(clock.withZone(KOREA)).atStartOfDay(KOREA)
                .withZoneSameInstant(clock.getZone()).toLocalDateTime();
        long todayCount = processingJobRepository.countByUserAndCreatedAtGreaterThanEqual(user, startOfToday);
        if (todayCount >= sharesPerDay) {
            throw new QuotaExceededException("오늘은 링크를 " + sharesPerDay + "개까지 추가할 수 있어요. 내일 다시 시도해주세요.");
        }
    }

    /** 장소 API 호출 1회를 쓴다 — 한도를 넘으면 예외(이번 호출은 세지 않는다). */
    public void consumePlaceCall(User user) {
        LocalDate today = LocalDate.now(clock.withZone(KOREA));
        String todayPrefix = today + ":";
        // 지난 날짜 키는 버린다(메모리가 계속 늘지 않게).
        placeCallCounts.keySet().removeIf(key -> !key.startsWith(todayPrefix));
        AtomicInteger count = placeCallCounts.computeIfAbsent(todayPrefix + user.getId(), key -> new AtomicInteger());
        if (count.incrementAndGet() > placeCallsPerDay) {
            count.decrementAndGet();
            throw new QuotaExceededException("오늘 장소 검색·추천을 모두 사용했어요. 내일 다시 시도해주세요.");
        }
    }

    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(String message) {
            super(message);
        }
    }
}
