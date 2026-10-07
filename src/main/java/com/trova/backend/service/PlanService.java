package com.trova.backend.service;

import com.trova.backend.entity.MeteredFeature;
import com.trova.backend.entity.TravelPass;
import com.trova.backend.entity.UsageRecord;
import com.trova.backend.entity.User;
import com.trova.backend.planner.Josa;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.TravelPassRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.UsageRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 무료·여행 패스 한도(#130, BM 결정 2026-10-05: 모으기는 무료, 계획·AI는 여행 패스).
 *
 * - 무료: 한국 시간 달마다 영상 분석 20개, AI 일정 초안 1회, 비서·대안·전체 재구성 5회
 * - 여행 패스(30일): 영상 분석 달마다 100개, 초안 하루 10회, 비서·대안·재구성 하루 30회(공정 사용 한도)
 *
 * 결제를 열기 전에는 막지 않는다(app.billing.enforce=false) — 막기만 하고 풀 방법이 없으면 안 되므로,
 * 그때는 사용량만 세고 남은 횟수를 보여 준다. 하루 남용 방지(DailyQuotaService)와는 별개다.
 */
@Service
public class PlanService {

    static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    public enum Plan { FREE, PASS }

    public enum Period { MONTH, DAY }

    public record Limit(int count, Period period) {
    }

    public record FeatureUsage(long used, int limit, Period period) {
        public long remaining() {
            return Math.max(0, limit - used);
        }
    }

    public record Status(Plan plan, LocalDateTime passExpiresAt, boolean enforced, Map<MeteredFeature, FeatureUsage> usage) {
    }

    static final Map<MeteredFeature, Limit> FREE_LIMITS = Map.of(
            MeteredFeature.ANALYSIS, new Limit(20, Period.MONTH),
            MeteredFeature.DRAFT, new Limit(1, Period.MONTH),
            MeteredFeature.ASSIST, new Limit(5, Period.MONTH));

    static final Map<MeteredFeature, Limit> PASS_LIMITS = Map.of(
            MeteredFeature.ANALYSIS, new Limit(100, Period.MONTH),
            MeteredFeature.DRAFT, new Limit(10, Period.DAY),
            MeteredFeature.ASSIST, new Limit(30, Period.DAY));

    private static final Map<MeteredFeature, String> FEATURE_NAMES = Map.of(
            MeteredFeature.ANALYSIS, "영상 분석",
            MeteredFeature.DRAFT, "AI 일정 초안",
            MeteredFeature.ASSIST, "비서·대안 찾기");

    private final ProcessingJobRepository processingJobRepository;
    private final TripDraftRepository tripDraftRepository;
    private final UsageRecordRepository usageRecordRepository;
    private final TravelPassRepository travelPassRepository;
    private final boolean enforce;
    private final Clock clock;

    @Autowired
    public PlanService(ProcessingJobRepository processingJobRepository, TripDraftRepository tripDraftRepository,
                       UsageRecordRepository usageRecordRepository, TravelPassRepository travelPassRepository,
                       @Value("${app.billing.enforce:false}") boolean enforce) {
        this(processingJobRepository, tripDraftRepository, usageRecordRepository, travelPassRepository, enforce,
                Clock.systemDefaultZone());
    }

    PlanService(ProcessingJobRepository processingJobRepository, TripDraftRepository tripDraftRepository,
                UsageRecordRepository usageRecordRepository, TravelPassRepository travelPassRepository,
                boolean enforce, Clock clock) {
        this.processingJobRepository = processingJobRepository;
        this.tripDraftRepository = tripDraftRepository;
        this.usageRecordRepository = usageRecordRepository;
        this.travelPassRepository = travelPassRepository;
        this.enforce = enforce;
        this.clock = clock;
    }

    public Status status(User user) {
        Optional<TravelPass> pass = activePass(user);
        Map<MeteredFeature, Limit> limits = pass.isPresent() ? PASS_LIMITS : FREE_LIMITS;
        Map<MeteredFeature, FeatureUsage> usage = new LinkedHashMap<>();
        for (MeteredFeature f : MeteredFeature.values()) {
            Limit limit = limits.get(f);
            usage.put(f, new FeatureUsage(used(user, f, limit.period()), limit.count(), limit.period()));
        }
        return new Status(pass.isPresent() ? Plan.PASS : Plan.FREE, pass.map(TravelPass::getExpiresAt).orElse(null), enforce, usage);
    }

    /** 이 기능을 한 번 더 쓸 수 있는지 — 한도를 다 썼고 막기가 켜져 있으면 예외. */
    public void check(User user, MeteredFeature feature) {
        if (!enforce) {
            return;
        }
        Optional<TravelPass> pass = activePass(user);
        Limit limit = (pass.isPresent() ? PASS_LIMITS : FREE_LIMITS).get(feature);
        if (used(user, feature, limit.period()) >= limit.count()) {
            throw new PlanLimitExceededException(feature, pass.isPresent(), message(feature, pass.isPresent(), limit));
        }
    }

    /** 비서·대안·재구성은 기능 테이블이 따로 없어 여기 남긴다(분석·초안은 작업·초안 테이블에서 센다). */
    public void recordAssist(User user) {
        usageRecordRepository.save(new UsageRecord(user, MeteredFeature.ASSIST, LocalDateTime.now(clock)));
    }

    /** 확인 후 기록 — 비서·대안·재구성 진입점에서 쓴다. */
    public void checkAndRecordAssist(User user) {
        check(user, MeteredFeature.ASSIST);
        recordAssist(user);
    }

    public Optional<TravelPass> activePass(User user) {
        LocalDateTime now = LocalDateTime.now(clock);
        return travelPassRepository.findFirstByUserOrderByExpiresAtDesc(user).filter(p -> p.getExpiresAt().isAfter(now));
    }

    private long used(User user, MeteredFeature feature, Period period) {
        LocalDateTime from = periodStart(period);
        return switch (feature) {
            case ANALYSIS -> processingJobRepository.countByUserAndCreatedAtGreaterThanEqual(user, from);
            case DRAFT -> tripDraftRepository.countByUserAndCreatedAtGreaterThanEqual(user, from);
            case ASSIST -> usageRecordRepository.countByUserAndFeatureAndCreatedAtGreaterThanEqual(user, feature, from);
        };
    }

    /** 기간 시작(한국 시간 자정·1일)을 서버 시간대의 LocalDateTime으로 — 기록 시각이 서버 시간대로 저장된다. */
    LocalDateTime periodStart(Period period) {
        LocalDate today = LocalDate.now(clock.withZone(KOREA));
        LocalDate start = period == Period.MONTH ? today.withDayOfMonth(1) : today;
        return start.atStartOfDay(KOREA).withZoneSameInstant(clock.getZone()).toLocalDateTime();
    }

    private static String message(MeteredFeature feature, boolean onPass, Limit limit) {
        String name = FEATURE_NAMES.get(feature);
        String when = limit.period() == Period.MONTH ? "이번 달" : "오늘";
        return onPass
                ? when + " " + Josa.eulReul(name) + " 모두 썼어요(" + limit.count() + "회). " + (limit.period() == Period.DAY ? "내일" : "다음 달") + " 다시 쓸 수 있어요."
                : when + " 무료 " + Josa.eulReul(name) + " 모두 썼어요. 여행 패스로 이어서 쓸 수 있어요.";
    }

    public static class PlanLimitExceededException extends RuntimeException {
        private final MeteredFeature feature;
        private final boolean onPass;

        public PlanLimitExceededException(MeteredFeature feature, boolean onPass, String message) {
            super(message);
            this.feature = feature;
            this.onPass = onPass;
        }

        public MeteredFeature feature() { return feature; }
        public boolean onPass() { return onPass; }
    }
}
