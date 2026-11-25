package com.trova.backend.conversation;

import com.trova.backend.entity.User;
import com.trova.backend.service.ApiCallLogService;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.verifyNoInteractions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationSerializationTest {
    private final GeminiChatClient gemini = mock(GeminiChatClient.class);
    private final ConversationToolExecutor tools = mock(ConversationToolExecutor.class);
    private final ConversationService service = new ConversationService(gemini, tools, mock(ApiCallLogService.class));
    private final User user = new User("google", "u", "사용자", null);
    private final ConversationState state = new ConversationState(1L, 10L, 100L, null, null);

    @Test void 가상_스레드의_외부_호출은_세션_모니터를_잡거나_캐리어를_고정하지_않는다() throws Exception {
        Path recording = Files.createTempFile("conversation-pinning-", ".jfr");
        try (Recording flight = new Recording(); ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            flight.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO).withStackTrace();
            flight.start();
            AtomicBoolean monitorHeld = new AtomicBoolean();
            when(gemini.sendMessage(any(), any(), any())).thenAnswer(invocation -> {
                monitorHeld.compareAndSet(false, Thread.holdsLock(state));
                Thread.sleep(100);
                return new GeminiChatClient.ChatResult(new GeminiChatClient.FunctionCall(
                        "find_alternatives", Map.of(), "sig"), null);
            });
            when(tools.execute(any(), any(), any(), any())).thenAnswer(invocation -> {
                monitorHeld.compareAndSet(false, Thread.holdsLock(state));
                Thread.sleep(100);
                return new ConversationToolExecutor.ToolExecutionResult(List.of(), Map.of());
            });
            when(gemini.sendFunctionResult(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                monitorHeld.compareAndSet(false, Thread.holdsLock(state));
                Thread.sleep(100);
                return new GeminiChatClient.ChatResult(null, "완료");
            });
            workers.submit(() -> service.sendMessage(user, state, "안녕")).get(5, TimeUnit.SECONDS);
            flight.stop();
            flight.dump(recording);
            assertThat(RecordingFile.readAllEvents(recording).stream()
                    .filter(event -> event.getEventType().getName().equals("jdk.VirtualThreadPinned"))
                    .filter(event -> event.getStackTrace() != null && event.getStackTrace().getFrames().stream()
                            .anyMatch(frame -> frame.getMethod().getType().getName().equals(ConversationService.class.getName()))))
                    .isEmpty();
            assertThat(monitorHeld).isFalse();
            System.out.printf("T44 pinning: JDK=%s, processors=%d, service VirtualThreadPinned=0%n",
                    System.getProperty("java.version"), Runtime.getRuntime().availableProcessors());
        } finally { Files.deleteIfExists(recording); }
    }

    @Test void 같은_세션의_둘째_요청은_첫_이차_호출과_저장_뒤에_진행한다() throws Exception {
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch nextStarted = new CountDownLatch(1);
        CountDownLatch nextGemini = new CountDownLatch(1);
        when(gemini.sendMessage(any(), eq("첫째"), any())).thenReturn(new GeminiChatClient.ChatResult(
                new GeminiChatClient.FunctionCall("find_alternatives", Map.of(), "sig"), null));
        when(tools.execute(any(), any(), any(), any())).thenReturn(
                new ConversationToolExecutor.ToolExecutionResult(List.of(), Map.of()));
        when(gemini.sendFunctionResult(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            secondEntered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return new GeminiChatClient.ChatResult(null, "첫 답변");
        });
        when(gemini.sendMessage(any(), eq("둘째"), any())).thenAnswer(invocation -> {
            nextGemini.countDown();
            assertThat(state.getHistory()).extracting(GeminiChatClient.HistoryTurn::text)
                    .containsExactly("첫째", "첫 답변");
            return new GeminiChatClient.ChatResult(null, "둘째 답변");
        });
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = workers.submit(() -> service.sendMessage(user, state, "첫째", cards -> true));
            try {
                assertThat(secondEntered.await(3, TimeUnit.SECONDS)).isTrue();
                Future<?> next = workers.submit(() -> {
                    nextStarted.countDown();
                    return service.sendMessage(user, state, "둘째");
                });
                assertThat(nextStarted.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(nextGemini.await(200, TimeUnit.MILLISECONDS)).isFalse();
                release.countDown();
                first.get(3, TimeUnit.SECONDS);
                next.get(3, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        assertThat(state.getHistory()).extracting(GeminiChatClient.HistoryTurn::text)
                .containsExactly("첫째", "첫 답변", "둘째", "둘째 답변");
    }
    @Test void 세션_잠금은_삼십초까지만_기다리고_시간이_지나면_호출하지_않는다() throws Exception {
        state.turnLock().lock();
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> waiting = workers.submit(() -> {
                try {
                    service.sendMessage(user, state, "대기");
                    return 200;
                } catch (ResponseStatusException timeout) {
                    return timeout.getStatusCode().value();
                }
            });
            assertThat(waiting.get(35, TimeUnit.SECONDS)).isEqualTo(429);
            verifyNoInteractions(gemini, tools);
            assertThat(state.getHistory()).isEmpty();
        } finally { state.turnLock().unlock(); }
    }

}
