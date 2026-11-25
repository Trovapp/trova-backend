package com.trova.backend.conversation;

import com.trova.backend.controller.ConversationController.CandidateResponse;
import com.trova.backend.controller.ConversationStreamErrors;
import com.trova.backend.entity.User;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** HTTP 연결과 작업 수명을 분리한다. 끊어진 연결도 작업이 끝날 때까지 처리 상한에 포함한다. */
@Component
public class ConversationStreams {
    public static final String PENDING = ConversationStreams.class.getName();
    private static final Logger log = LoggerFactory.getLogger(ConversationStreams.class);
    private final ConversationService service;
    private final MeterRegistry meters;
    private final int perUser;
    private final int total;
    private final Map<Long, Integer> active = new HashMap<>();
    private int running;
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("conversation-sse-", 0).factory());

    public ConversationStreams(ConversationService service, MeterRegistry meters,
                               @Value("${app.conversation-stream.per-user:3}") int perUser,
                               @Value("${app.conversation-stream.total:256}") int total) {
        this.service = service;
        this.meters = meters;
        this.perUser = perUser;
        this.total = total;
    }

    public synchronized Pending prepare(User user, ConversationState state, String message, HttpServletRequest request) {
        if (running >= total || active.getOrDefault(user.getId(), 0) >= perUser) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        running++;
        active.merge(user.getId(), 1, Integer::sum);
        return new Pending(user, state, message, request.getRequestURI());
    }

    private synchronized void release(Long user) {
        running--;
        active.computeIfPresent(user, (id, count) -> count == 1 ? null : count - 1);
    }

    public record Cards(List<CandidateResponse> candidates) {}
    public record Reply(String reply, int turnCount, boolean turnLimitReached) {}
    public record Done(int turnCount, boolean turnLimitReached) {}

    public final class Pending {
        private final SseEmitter emitter = new SseEmitter(60_000L);
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean connected = new AtomicBoolean(true);
        private final User user;
        private final ConversationState state;
        private final String message;
        private final String path;
        private final String traceId;
        private final Map<String, String> context;
        private final Runnable task;
        private final Timer.Sample elapsed = Timer.start(meters);

        private Pending(User user, ConversationState state, String message, String path) {
            this.user = user;
            this.state = state;
            this.message = message;
            this.path = path;
            this.context = MDC.getCopyOfContextMap();
            String existing = MDC.get("traceId");
            this.traceId = existing == null ? UUID.randomUUID().toString() : existing;
            this.task = new DelegatingSecurityContextRunnable(this::process);
            emitter.onCompletion(() -> connected.set(false));
            emitter.onTimeout(() -> { connected.set(false); complete(); });
            emitter.onError(error -> connected.set(false));
        }

        public SseEmitter emitter() { return emitter; }
        public String traceId() { return traceId; }

        public void abortBeforeStart() {
            if (started.compareAndSet(false, true)) {
                try { complete(); }
                finally { release(user.getId()); }
            }
        }

        private void complete() {
            try { emitter.complete(); }
            catch (IllegalStateException alreadyClosed) { /* 컨테이너가 응답을 이미 회수했다. */ }
        }

        /** MVC 초기화와 Security 헤더 쓰기가 끝난 후에만 시작한다(TripSseMvc와 같은 원칙). */
        public void start() {
            if (!started.compareAndSet(false, true)) return;
            try {
                workers.execute(task);
            } catch (RuntimeException rejected) {
                try { sendError(rejected); }
                finally {
                    elapsed.stop(meters.timer("conversation.stream.complete", "outcome", "error"));
                    try { complete(); }
                    finally { release(user.getId()); }
                }
            }
        }

        private void process() {
            if (context != null) MDC.setContextMap(context);
            MDC.put("traceId", traceId);
            String outcome = "success";
            try {
                ConversationService.TurnResult result = service.sendMessage(user, state, message, candidates -> {
                    if (send("cards", new Cards(candidates.stream().map(CandidateResponse::from).toList()))) {
                        elapsed.stop(meters.timer("conversation.stream.cards"));
                    }
                });
                // 서비스는 히스토리·후보 ID·턴수 저장을 마친 다음 반환한다.
                send("reply", new Reply(result.reply(), result.turnCount(), result.turnLimitReached()));
                send("done", new Done(result.turnCount(), result.turnLimitReached()));
            } catch (Exception failure) {
                outcome = "error";
                log.warn("대화 스트림 처리 실패 traceId={}", traceId, failure);
                sendError(failure);
            } finally {
                elapsed.stop(meters.timer("conversation.stream.complete", "outcome", outcome));
                try { complete(); }
                finally {
                    release(user.getId());
                    MDC.clear();
                }
            }
        }

        private void sendError(Exception failure) {
            send("error", ConversationStreamErrors.body(failure, path, traceId));
        }

        private boolean send(String name, Object payload) {
            if (!connected.get()) return false;
            try {
                emitter.send(SseEmitter.event().name(name).data(payload, MediaType.APPLICATION_JSON));
                return true;
            } catch (Exception disconnected) {
                // IOException/완료된 emitter 예외는 모델 호출·세션 저장에 영향을 주지 않는다.
                connected.set(false);
                return false;
            }
        }
    }

    @PreDestroy
    void shutdown() { workers.shutdown(); }
}
