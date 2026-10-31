package com.trova.backend.pipeline;

import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PipelineRunnerProcessCleanupTest {
    @TempDir Path root;

    private Path script() throws Exception {
        Path script = root.resolve("pipeline.py");
        Files.writeString(script, """
                import json, os, pathlib, subprocess, sys, time
                root = pathlib.Path(__file__).resolve().parent
                work = pathlib.Path(sys.argv[2])
                work.mkdir(parents=True, exist_ok=True)
                (work / 'media').write_text('fixture')
                child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])
                (root / 'pids').write_text(str(os.getpid()) + '\\n' + str(child.pid))
                print('TROVA_PROGRESS:' + json.dumps({'title':'ready'}), file=sys.stderr, flush=True)
                time.sleep(60)
                """);
        return script;
    }

    private PipelineRunner runner(Path script, Duration timeout) {
        return new PipelineRunner(script.toString(), root.resolve("work").toString(), "test-key", mock(ApiCallLogService.class), timeout);
    }

    private List<ProcessHandle> ownedHandles() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<String> pids = List.of();
        while (pids.size() != 2 && System.nanoTime() < deadline) {
            if (Files.exists(root.resolve("pids"))) pids = Files.readAllLines(root.resolve("pids"));
            if (pids.size() != 2) Thread.sleep(10);
        }
        assertThat(pids).hasSize(2);
        return pids.stream().map(Long::parseLong).map(pid -> ProcessHandle.of(pid).orElseThrow()).toList();
    }

    private void cleanup(List<ProcessHandle> handles) {
        for (ProcessHandle handle : handles) if (handle.isAlive()) handle.destroyForcibly();
    }

    @Test
    void 인터럽트는_부모와_하위프로세스를_종료한_뒤_파일을_지우고_플래그를_보존한다() throws Exception {
        PipelineRunner runner = runner(script(), Duration.ofMinutes(1));
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try { runner.run("fixture", 1L); }
            catch (Throwable failure) { error.set(failure); }
            finally { interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        worker.start();
        List<ProcessHandle> handles = ownedHandles();
        try {
            worker.interrupt();
            worker.join(8_000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(error.get()).isInstanceOf(PipelineException.class).hasMessageContaining("인터럽트");
            assertThat(interrupted.get()).isTrue();
            assertThat(handles).allMatch(handle -> !handle.isAlive());
            assertThat(root.resolve("work/job-1")).doesNotExist();
        } finally {
            cleanup(handles);
            worker.interrupt();
            worker.join(8_000);
        }
    }

    @Test
    void 시간초과도_부모와_하위프로세스를_종료하고_파일을_지운다() throws Exception {
        PipelineRunner runner = runner(script(), Duration.ofSeconds(2));
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { runner.run("fixture", 2L); }
            catch (Throwable failure) { error.set(failure); }
        });
        worker.start();
        List<ProcessHandle> handles = ownedHandles();
        try {
            worker.join(9_000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(error.get()).isInstanceOf(PipelineException.class).hasMessageContaining("시간 초과");
            assertThat(handles).allMatch(handle -> !handle.isAlive());
            assertThat(root.resolve("work/job-2")).doesNotExist();
        } finally {
            cleanup(handles);
            worker.interrupt();
            worker.join(8_000);
        }
    }

    @Test
    void 진행_콜백이_실패해도_실행중_프로세스를_남기지_않는다() throws Exception {
        PipelineRunner runner = runner(script(), Duration.ofMinutes(1));
        CountDownLatch callback = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        IllegalStateException expected = new IllegalStateException("callback failure");
        Thread worker = new Thread(() -> {
            try { runner.run("fixture", 3L, progress -> { callback.countDown(); throw expected; }); }
            catch (Throwable failure) { error.set(failure); }
        });
        worker.start();
        List<ProcessHandle> handles = ownedHandles();
        try {
            assertThat(callback.await(5, TimeUnit.SECONDS)).isTrue();
            worker.join(8_000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(error.get()).isSameAs(expected);
            assertThat(handles).allMatch(handle -> !handle.isAlive());
            assertThat(root.resolve("work/job-3")).doesNotExist();
        } finally {
            cleanup(handles);
            worker.interrupt();
            worker.join(8_000);
        }
    }

    @Test
    void 큰_stdout도_파이프_막힘_없이_정상_JSON으로_수집한다() throws Exception {
        Path script = root.resolve("large.py");
        Files.writeString(script, "import json\nprint(json.dumps({'title':'가'*131072,'places':[]}))\n");
        PipelineOutput result = runner(script, Duration.ofSeconds(5)).run("fixture", 4L);
        assertThat(result.title()).hasSize(131072);
        assertThat(root.resolve("work/job-4")).doesNotExist();
    }

    @Test
    void 부모가_먼저_끝나도_관찰한_하위프로세스를_정리하고_stdout을_읽는다() throws Exception {
        Path script = script();
        String text = Files.readString(script).replace("time.sleep(60)\n", "time.sleep(2)\nprint(json.dumps({'title':'parent done','places':[]}))\n");
        Files.writeString(script, text);
        PipelineRunner runner = runner(script, Duration.ofSeconds(10));
        AtomicReference<PipelineOutput> output = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { output.set(runner.run("fixture", 5L)); }
            catch (Throwable failure) { error.set(failure); }
        });
        worker.start();
        List<ProcessHandle> handles = ownedHandles();
        try {
            worker.join(9_000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(error.get()).isNull();
            assertThat(output.get().title()).isEqualTo("parent done");
            assertThat(handles).allMatch(handle -> !handle.isAlive());
            assertThat(root.resolve("work/job-5")).doesNotExist();
        } finally {
            cleanup(handles);
            worker.interrupt();
            worker.join(8_000);
        }
    }
}
