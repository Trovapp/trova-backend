package com.trova.backend.pipeline;

import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Timeout(20)
class AuxiliaryRunnerInterruptTest {
    @TempDir Path root;

    private Runnable invocation(String kind, Path script, Path base) {
        ApiCallLogService logs = mock(ApiCallLogService.class);
        String path = script.toString();
        String work = base.toString();
        return switch (kind) {
            case "review-summary" -> () -> new ReviewSummaryRunner(path, work, "test-key", logs).run(List.of("리뷰"), 1L);
            case "tag" -> () -> new PlaceTaggingRunner(path, work, "test-key", logs).run(List.of(), 1L);
            case "select" -> () -> new PlaceSelectionRunner(path, work, "test-key", logs).run(List.of(), 1L);
            case "verify" -> () -> new PlaceVerificationRunner(path, work, "test-key", logs).run(List.of(), 1L);
            case "itinerary" -> () -> new ItineraryPipelineRunner(path, work, "test-key", logs).run(List.of(), 1L);
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private List<ProcessHandle> handles() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<String> ids = List.of();
        while (System.nanoTime() < deadline) {
            if (Files.exists(root.resolve("pids"))) ids = Files.readAllLines(root.resolve("pids"));
            if (ids.size() == 2) break;
            Thread.sleep(10);
        }
        assertThat(ids).hasSize(2);
        return ids.stream().map(Long::parseLong).map(id -> ProcessHandle.of(id).orElseThrow()).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"review-summary", "tag", "select", "verify", "itinerary"})
    void 인터럽트시_각_실행기는_부모와_자식을_정리한뒤_파일을_삭제한다(String kind) throws Exception {
        Path script = root.resolve("fixture.py");
        Files.writeString(script, """
                import os, pathlib, subprocess, sys, time
                root = pathlib.Path(__file__).resolve().parent
                child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(90)'])
                (root / 'pids').write_text(str(os.getpid()) + '\\n' + str(child.pid))
                time.sleep(90)
                """);
        Path base = root.resolve("work");
        Runnable invoke = invocation(kind, script, base);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try { invoke.run(); }
            catch (Throwable failure) { error.set(failure); }
            finally { interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        List<ProcessHandle> owned = List.of();
        worker.start();
        try {
            owned = handles();
            worker.interrupt(); worker.join(8000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(error.get()).isInstanceOf(PipelineException.class).hasMessageContaining("인터럽트");
            assertThat(interrupted.get()).isTrue();
            assertThat(owned).allMatch(handle -> !handle.isAlive());
            assertThat(base.resolve(kind + "-1")).doesNotExist();
        } finally {
            for (ProcessHandle handle : owned) if (handle.isAlive()) handle.destroyForcibly();
            for (ProcessHandle handle : owned) {
                try { handle.onExit().get(3, TimeUnit.SECONDS); }
                catch (Exception ignored) { /* 테스트 소유 핸들만 정리한다. */ }
            }
            worker.interrupt(); worker.join(8000);
        }
    }
}
