package com.trova.backend.service;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.queue.DraftJobQueue;
import com.trova.backend.queue.ReplanJobQueue;
import com.trova.backend.queue.ShareJobQueue;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.TripReplanJobRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 작업 행은 저장(커밋)됐는데 대기열에 없는 작업을 다시 넣는다(ADR 0043).
 * 저장 직후·보내기 전에 서버가 죽거나, Kafka 실패로 서버 안 대기열에 넘긴 뒤 죽으면 행만 PENDING·PROCESSING으로 남는다.
 * 지금까지는 {@link OrphanedJobRecoveryService}가 15분 뒤 FAILED로 바꿀 뿐이었다. 그 전에 한 번 다시 보내 살린다.
 *
 * 다시 받아도 한 번만 실행되는 것은 각 처리(processNow)의 id별 잠금과 상태 확인이 보장한다(ADR 0024, 0021) — 원래 메시지와
 * 다시 보낸 메시지가 둘 다 와도 두 번째는 끝난 상태를 보고 건너뛴다. 단일 JVM 범위다.
 * 작업당 다시 보내기는 한 번(서버 메모리에 기록) — 계속 실패하는 작업이 Gemini를 반복해 부르지 않게 한다.
 * 서버가 재시작되면 기록이 사라져 한 번 더 보낼 수 있지만, 15분이 지나면 정리기가 FAILED로 바꾸므로 거기서 멈춘다.
 */
@Service
@ConditionalOnProperty(name = "app.queue.requeue.enabled", havingValue = "true", matchIfMissing = true)
public class StuckJobRequeueService {

    private static final Logger log = LoggerFactory.getLogger(StuckJobRequeueService.class);

    // 대기열에 정상으로 들어간 작업은 수 초 안에 PROCESSING이 된다(운영 대기 중앙값 0.4초). 3분이면 소비 지연과도 구분된다.
    static final Duration PENDING_AFTER = Duration.ofMinutes(3);
    // 처리 중 갱신이 없는 시간 — 운영 실측 처리 최대(분석 수 분, 재구성 38초)보다 길게.
    static final Duration PROCESSING_AFTER = Duration.ofMinutes(8);

    private final ProcessingJobRepository processingJobRepository;
    private final TripDraftRepository tripDraftRepository;
    private final TripReplanJobRepository tripReplanJobRepository;
    private final ShareJobQueue shareJobQueue;
    private final DraftJobQueue draftJobQueue;
    private final ReplanJobQueue replanJobQueue;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;
    // "share:12" → 다시 보낸 시각. 정리기 기준(15분)을 넘긴 기록은 지운다.
    private final Map<String, LocalDateTime> requeued = new ConcurrentHashMap<>();

    public StuckJobRequeueService(
            ProcessingJobRepository processingJobRepository, TripDraftRepository tripDraftRepository,
            TripReplanJobRepository tripReplanJobRepository, ShareJobQueue shareJobQueue, DraftJobQueue draftJobQueue,
            ReplanJobQueue replanJobQueue, MeterRegistry meterRegistry
    ) {
        this.processingJobRepository = processingJobRepository;
        this.tripDraftRepository = tripDraftRepository;
        this.tripReplanJobRepository = tripReplanJobRepository;
        this.shareJobQueue = shareJobQueue;
        this.draftJobQueue = draftJobQueue;
        this.replanJobQueue = replanJobQueue;
        this.meterRegistry = meterRegistry;
        // Prometheus increase()가 첫 1건도 세도록 0으로 미리 만든다.
        List.of("share", "draft", "replan").forEach(this::counter);
    }

    private Counter counter(String queue) {
        return counters.computeIfAbsent(queue, q -> Counter.builder("trova.queue.requeued")
                .description("저장됐지만 대기열에서 사라져 다시 보낸 작업 수")
                .tag("queue", q)
                .register(meterRegistry));
    }

    @Scheduled(fixedDelay = 1, initialDelay = 2, timeUnit = TimeUnit.MINUTES)
    public void requeueStuckJobs() {
        requeueStuckJobs(LocalDateTime.now());
    }

    /** 다시 보낸 작업 수를 돌려준다. updatedAt은 서버 기본 시간대 LocalDateTime이라 같은 기준으로 비교한다. */
    int requeueStuckJobs(LocalDateTime now) {
        LocalDateTime giveUp = now.minus(OrphanedJobRecoveryService.STALE_AFTER);
        requeued.values().removeIf(at -> at.isBefore(giveUp));
        LocalDateTime pendingBefore = now.minus(PENDING_AFTER);
        LocalDateTime processingBefore = now.minus(PROCESSING_AFTER);

        List<Long> shares = new ArrayList<>(processingJobRepository.findIdsByStatusAndUpdatedAtBetween(JobStatus.PENDING, giveUp, pendingBefore));
        shares.addAll(processingJobRepository.findIdsByStatusAndUpdatedAtBetween(JobStatus.PROCESSING, giveUp, processingBefore));
        List<Long> drafts = new ArrayList<>(tripDraftRepository.findIdsByStatusAndUpdatedAtBetween(TripDraftStatus.PENDING, giveUp, pendingBefore));
        drafts.addAll(tripDraftRepository.findIdsByStatusAndUpdatedAtBetween(TripDraftStatus.PROCESSING, giveUp, processingBefore));
        List<Long> replans = new ArrayList<>(tripReplanJobRepository.findIdsByStatusAndUpdatedAtBetween(JobStatus.PENDING, giveUp, pendingBefore));
        replans.addAll(tripReplanJobRepository.findIdsByStatusAndUpdatedAtBetween(JobStatus.PROCESSING, giveUp, processingBefore));

        int sent = send("share", shares, shareJobQueue::enqueue, now)
                + send("draft", drafts, draftJobQueue::enqueue, now)
                + send("replan", replans, replanJobQueue::enqueue, now);
        if (sent > 0) {
            log.warn("멈춘 작업 다시 보내기: {}건(영상 {}, 초안 {}, 재구성 {} 후보)", sent, shares.size(), drafts.size(), replans.size());
        }
        return sent;
    }

    private int send(String queue, List<Long> ids, Consumer<Long> enqueue, LocalDateTime now) {
        int sent = 0;
        for (Long id : ids) {
            if (requeued.putIfAbsent(queue + ":" + id, now) != null) {
                continue;
            }
            try {
                enqueue.accept(id);
                counter(queue).increment();
                sent++;
                log.info("멈춘 작업 다시 보냄: {} {}", queue, id);
            } catch (RuntimeException e) {
                // 한 건이 실패해도 나머지는 보낸다. 이 작업은 정리기가 15분 뒤 FAILED로 바꾼다.
                log.error("멈춘 작업 다시 보내기 실패: {} {}", queue, id, e);
            }
        }
        return sent;
    }
}
