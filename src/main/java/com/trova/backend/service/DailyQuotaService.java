package com.trova.backend.service;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 사용자별 호출 한도(#33). Gemini 무료 한도(하루 1,000회)는 전체 사용자가 함께 쓰고, Google Places는
 * 무료 크레딧을 넘으면 과금되므로 한 사용자가 이를 소진하지 못하게 막는다.
 *
 * - 링크 제출: 최근 7일(굴러가는 창) 동안 만든 처리 작업 가운데 실패하지 않은 것(진행 중·완료)을 DB에서 센다
 *   (#431, 서버를 재시작해도 유지). 실패로 끝난 분석은 세지 않는다 — 사용자 결정(2026-11-13). 진행 중인 작업은
 *   완료될 것으로 보고 세어 한 번에 몰아 넣는 것을 막고, 실패로 끝나면 그 자리가 다시 풀린다.
 *   이전에는 한국 자정 기준 하루 한도였다(#33 20건 → #420 10건).
 * - 장소 API(Google Places를 부르는 조회): 한국 시간 하루 기준으로 메모리에서 센다 — 서버 재시작 시 초기화되지만
 *   남용을 막는 데는 충분하다.
 * - 공유 여행 복사: 장소 호출과 별도 카운터로 한국 시간 하루 기준 메모리에서 센다(#446).
 */
@Service
public class DailyQuotaService {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    /** 링크 제출 한도를 세는 창(일). 달력 주가 아니라 지금부터 거슬러 올라가는 7일이다. */
    static final int SHARE_WINDOW_DAYS = 7;

    private final ProcessingJobRepository processingJobRepository;
    private final int sharesPerWeek;
    private final int placeCallsPerDay;
    private final int copiesPerDay;
    private final Clock clock;
    private final Map<String, AtomicInteger> placeCallCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> copyCounts = new ConcurrentHashMap<>();

    @Autowired
    public DailyQuotaService(
            ProcessingJobRepository processingJobRepository,
            @Value("${app.quota.shares-per-week:10}") int sharesPerWeek,
            @Value("${app.quota.place-calls-per-day:100}") int placeCallsPerDay,
            @Value("${app.share-link.copy-per-day:20}") int copiesPerDay
    ) {
        this(processingJobRepository, sharesPerWeek, placeCallsPerDay, copiesPerDay, Clock.systemDefaultZone());
    }

    DailyQuotaService(
            ProcessingJobRepository processingJobRepository, int sharesPerWeek, int placeCallsPerDay,
            int copiesPerDay, Clock clock
    ) {
        this.processingJobRepository = processingJobRepository;
        this.sharesPerWeek = sharesPerWeek;
        this.placeCallsPerDay = placeCallsPerDay;
        this.copiesPerDay = copiesPerDay;
        this.clock = clock;
    }

    /** 링크를 하나 더 제출할 수 있는지 — 최근 7일 동안 실패하지 않은 처리 작업이 한도 이상이면 예외. */
    public void checkShare(User user) {
        // createdAt은 서버 기본 시간대의 LocalDateTime으로 저장되므로, 같은 시간대의 지금에서 7일을 뺀 시각과 비교한다.
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime from = now.minusDays(SHARE_WINDOW_DAYS);
        List<ProcessingJob> counted = processingJobRepository
                .findByUserAndCreatedAtGreaterThanEqualAndStatusNotOrderByCreatedAtAsc(user, from, JobStatus.FAILED);
        if (counted.size() >= sharesPerWeek) {
            // 가장 오래된 작업이 창 밖으로 나가는 날 자리가 하나 풀린다 — 그날까지 며칠인지 올림해 알려 준다(최소 1일).
            LocalDateTime freesAt = counted.get(0).getCreatedAt().plusDays(SHARE_WINDOW_DAYS);
            long minutesLeft = Math.max(0, Duration.between(now, freesAt).toMinutes());
            long daysLeft = Math.max(1, (minutesLeft + 24 * 60 - 1) / (24 * 60));
            throw new QuotaExceededException("최근 7일 동안 링크를 " + sharesPerWeek + "개까지 추가할 수 있어요. "
                    + daysLeft + "일 뒤 다시 시도해주세요.");
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

    /** 공유 여행 복사 1회를 쓴다 — 장소 호출과 분리하고 한국 자정에 초기화한다(#446). */
    public void consumeTripCopy(User user) {
        LocalDate today = LocalDate.now(clock.withZone(KOREA));
        String todayPrefix = today + ":";
        copyCounts.keySet().removeIf(key -> !key.startsWith(todayPrefix));
        AtomicInteger count = copyCounts.computeIfAbsent(todayPrefix + user.getId(), key -> new AtomicInteger());
        if (count.incrementAndGet() > copiesPerDay) {
            count.decrementAndGet();
            throw new QuotaExceededException("오늘 여행 복사를 모두 사용했어요. 내일 다시 시도해주세요.");
        }
    }

    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(String message) {
            super(message);
        }
    }
}
