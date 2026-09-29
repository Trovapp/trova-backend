package com.trova.backend.pipeline;

import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PipelineRunnerTest {

    // 실제 파이프라인처럼 작업 폴더에 영상·오디오 파일을 만든 뒤, 인자로 받은 종료 코드로 끝나는 가짜 스크립트.
    private static final String FAKE_SCRIPT = """
            import json, pathlib, sys
            work = pathlib.Path(sys.argv[2])
            (work / "download").mkdir(parents=True, exist_ok=True)
            (work / "download" / "video.webm").write_bytes(b"x" * 1000)
            (work / "audio.mp3").write_bytes(b"y" * 100)
            exit_code = int(pathlib.Path(sys.argv[0]).with_suffix(".exit").read_text())
            if exit_code == 0:
                print(json.dumps({"title": "테스트 영상", "places": []}))
            sys.exit(exit_code)
            """;

    @TempDir
    Path tempDir;

    private PipelineRunner runnerExitingWith(int exitCode) throws IOException {
        Path script = tempDir.resolve("fake_pipeline.py");
        Files.writeString(script, FAKE_SCRIPT);
        Files.writeString(tempDir.resolve("fake_pipeline.exit"), String.valueOf(exitCode));
        return new PipelineRunner(script.toString(), tempDir.resolve("work").toString(), "test-key", mock(ApiCallLogService.class));
    }

    @Test
    void 성공하면_결과를_반환하고_내려받은_작업_파일을_지운다() throws IOException {
        PipelineOutput output = runnerExitingWith(0).run("https://youtu.be/test", 1L);

        assertThat(output.title()).isEqualTo("테스트 영상");
        assertThat(tempDir.resolve("work").resolve("job-1")).doesNotExist();
    }

    @Test
    void 실패해도_내려받은_작업_파일을_지운다() throws IOException {
        PipelineRunner runner = runnerExitingWith(1);

        assertThatThrownBy(() -> runner.run("https://youtu.be/test", 2L)).isInstanceOf(PipelineException.class);
        assertThat(tempDir.resolve("work").resolve("job-2")).doesNotExist();
    }

    @Test
    void 실행_중에_찍힌_제목과_장소_이름을_끝나기_전에_받는다() throws IOException {
        // 제목을 찍고 1초 쉰 뒤 장소 이름을 찍고, 다시 1초 뒤에 끝나는 가짜 파이프라인(#51).
        Path script = tempDir.resolve("progress_pipeline.py");
        Files.writeString(script, """
                import json, sys, time
                print("[pipeline] 다운로드 중", file=sys.stderr)
                print("TROVA_PROGRESS:" + json.dumps({"title": "부산 여행 브이로그"}, ensure_ascii=False), file=sys.stderr, flush=True)
                time.sleep(1)
                print("TROVA_PROGRESS:" + json.dumps({"placeNames": ["해운대 암소갈비집", "흰여울 카페"]}, ensure_ascii=False), file=sys.stderr, flush=True)
                print("TROVA_PROGRESS:{깨진 json", file=sys.stderr, flush=True)
                time.sleep(1)
                print(json.dumps({"title": "부산 여행 브이로그", "places": []}))
                """);
        PipelineRunner runner = new PipelineRunner(
                script.toString(), tempDir.resolve("work").toString(), "test-key", mock(ApiCallLogService.class));
        long start = System.currentTimeMillis();
        List<PipelineProgress> events = new ArrayList<>();
        List<Long> receivedAtMillis = new ArrayList<>();

        runner.run("https://youtu.be/test", 3L, progress -> {
            events.add(progress);
            receivedAtMillis.add(System.currentTimeMillis() - start);
        });
        long finishedAtMillis = System.currentTimeMillis() - start;

        assertThat(events).containsExactly(
                new PipelineProgress("부산 여행 브이로그", null),
                new PipelineProgress(null, List.of("해운대 암소갈비집", "흰여울 카페")));
        // 끝난 뒤 한꺼번에가 아니라 실행 중에 받아야 한다 — 장소 이름은 끝나기 최소 0.5초 전.
        assertThat(receivedAtMillis.get(1)).isLessThan(finishedAtMillis - 500);
    }
}
