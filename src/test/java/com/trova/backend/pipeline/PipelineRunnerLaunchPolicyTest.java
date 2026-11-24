package com.trova.backend.pipeline;

import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 분석 파이썬의 CPU 우선순위와 내려받기 분석 동시 실행 수(#345). */
class PipelineRunnerLaunchPolicyTest {

    // 시작·끝 시각과 자기 nice 값을 제목에 담아 돌려주는 가짜 파이프라인. 1초 동안 실행된다.
    private static final String TIMED_SCRIPT = """
            import json, os, sys, time
            start = time.time()
            time.sleep(1)
            print(json.dumps({"title": f"{start}|{time.time()}|{os.nice(0)}", "places": []}))
            """;

    @TempDir
    Path tempDir;

    private PipelineRunner runner(int niceLevel, int downloadConcurrency) throws IOException {
        Path script = tempDir.resolve("timed_pipeline.py");
        Files.writeString(script, TIMED_SCRIPT);
        return new PipelineRunner(script.toString(), tempDir.resolve("work").toString(), "test-key",
                mock(ApiCallLogService.class), Duration.ofSeconds(30), niceLevel, downloadConcurrency);
    }

    @Test
    void nice가_0보다_크면_nice로_감싸고_0이면_그대로_실행한다() {
        assertThat(PipelineRunner.command(10, "run.py", "u", "w"))
                .containsExactly("nice", "-n", "10", "python3", "run.py", "u", "w");
        assertThat(PipelineRunner.command(0, "run.py", "u", "w"))
                .containsExactly("python3", "run.py", "u", "w");
    }

    @Test
    void 유튜브가_아닌_링크만_내려받기_분석으로_본다() {
        assertThat(PipelineRunner.needsDownload("https://www.instagram.com/reel/abc/")).isTrue();
        assertThat(PipelineRunner.needsDownload("https://www.youtube.com/shorts/abc")).isFalse();
        assertThat(PipelineRunner.needsDownload("https://youtu.be/abc")).isFalse();
        assertThat(PipelineRunner.needsDownload("https://m.youtube.com/watch?v=abc")).isFalse();
    }

    // 운영은 리눅스, 프로세스 그룹/nice 전제
    @DisabledOnOs(OS.WINDOWS)
    @Test
    void 파이썬은_설정한_nice_값으로_실행된다() throws IOException {
        PipelineOutput output = runner(10, 0).run("https://youtu.be/test", 1L);

        assertThat(output.title().split("\\|")[2]).isEqualTo("10");
    }

    // 운영은 리눅스, 프로세스 그룹/nice 전제
    @DisabledOnOs(OS.WINDOWS)
    @Test
    void 인스타그램_분석은_동시에_하나씩만_실행된다() throws Exception {
        PipelineRunner runner = runner(0, 1);

        List<double[]> spans = runConcurrently(runner, "https://www.instagram.com/reel/a/", "https://www.instagram.com/reel/b/");

        double[] first = spans.get(0)[0] <= spans.get(1)[0] ? spans.get(0) : spans.get(1);
        double[] second = first == spans.get(0) ? spans.get(1) : spans.get(0);
        assertThat(second[0]).isGreaterThanOrEqualTo(first[1]);
    }

    // 운영은 리눅스, 프로세스 그룹/nice 전제
    @DisabledOnOs(OS.WINDOWS)
    @Test
    void 유튜브_분석은_제한_없이_같이_실행된다() throws Exception {
        PipelineRunner runner = runner(0, 1);

        List<double[]> spans = runConcurrently(runner, "https://youtu.be/a", "https://www.instagram.com/reel/b/");

        double laterStart = Math.max(spans.get(0)[0], spans.get(1)[0]);
        double earlierEnd = Math.min(spans.get(0)[1], spans.get(1)[1]);
        assertThat(laterStart).isLessThan(earlierEnd);
    }

    private List<double[]> runConcurrently(PipelineRunner runner, String firstUrl, String secondUrl) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<PipelineOutput> first = pool.submit(() -> runner.run(firstUrl, 11L));
            Future<PipelineOutput> second = pool.submit(() -> runner.run(secondUrl, 12L));
            return List.of(span(first.get()), span(second.get()));
        } finally {
            pool.shutdownNow();
        }
    }

    private static double[] span(PipelineOutput output) {
        String[] parts = output.title().split("\\|");
        return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
    }
}
