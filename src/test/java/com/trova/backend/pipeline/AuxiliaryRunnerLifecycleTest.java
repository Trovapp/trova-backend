package com.trova.backend.pipeline;

import com.trova.backend.entity.SavedPlace;
import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(20)
class AuxiliaryRunnerLifecycleTest {
    @TempDir Path root;
    private final ApiCallLogService logs = mock(ApiCallLogService.class);

    private Object invoke(String kind, Path script, Duration timeout) {
        String path = script.toString();
        String base = root.resolve("work").toString();
        return switch (kind) {
            case "review-summary" -> new ReviewSummaryRunner(path, base, "test-key", logs, timeout).run(List.of("리뷰 🌊"), 1L);
            case "tag" -> new PlaceTaggingRunner(path, base, "test-key", logs, timeout).run(List.of(
                    new PlaceTaggingRunner.TagCandidate(0, "장소", null, 4.0, 5, null)), 1L);
            case "select" -> new PlaceSelectionRunner(path, base, "test-key", logs, timeout).run(List.of(
                    new PlaceSelectionRunner.SelectionCandidate(0, "장소", null, List.of(
                            new PlaceSelectionRunner.CandidateOption(0, "후보", "주소", null, "카페")))), 1L);
            case "verify" -> new PlaceVerificationRunner(path, base, "test-key", logs, timeout).run(List.of(
                    new PlaceVerificationRunner.VerificationCandidate(0, "장소", null)), 1L);
            case "itinerary" -> {
                SavedPlace place = mock(SavedPlace.class);
                when(place.getId()).thenReturn(7L); when(place.getPlaceName()).thenReturn("장소");
                yield new ItineraryPipelineRunner(path, base, "test-key", logs, timeout).run(List.of(place), 1L);
            }
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private Path script(String body) throws Exception {
        Path script = root.resolve("fixture.py"); Files.writeString(script, body); return script;
    }

    @ParameterizedTest
    @ValueSource(strings = {"review-summary", "tag", "select", "verify", "itinerary"})
    void 입력과_출력과_API기록을_유지하고_큰_한국어_JSON도_정리후_반환한다(String kind) throws Exception {
        Path script = script("kind='" + kind + "'\n" + """
                import json, os, sys
                assert os.environ['GEMINI_API_KEY'] == 'test-key'
                with open(sys.argv[1]) as stream: data = json.load(stream)
                if kind == 'review-summary':
                    assert data == ['리뷰 🌊']
                    output = {'highlights':'제주🌊'*32768, 'pros':[], 'cons':[], 'tips':[], 'checklist':[]}
                elif kind == 'tag':
                    assert data == [{'index':0,'name':'장소','category':None,'rating':4.0,'userRatingCount':5,'priceLevel':None}]
                    output = [{'index':0,'mood':'평온','space':'INDOOR'}]
                elif kind == 'select':
                    assert data == [{'index':0,'extractedName':'장소','region':None,'candidates':[
                        {'candidateIndex':0,'placeName':'후보','addressName':'주소','roadAddressName':None,'categoryName':'카페'}]}]
                    output = [{'index':0,'selectedCandidateIndex':0}]
                elif kind == 'verify':
                    assert data == [{'index':0,'name':'장소','region':''}]
                    output = [{'index':0,'valid':True}]
                else:
                    assert data == [{'id':7,'name':'장소','region':'','category':''}]
                    output = [{'id':7,'dayNumber':1,'orderInDay':1}]
                print('fixture-api-record', file=sys.stderr)
                print(json.dumps(output, ensure_ascii=False))
                """);
        Object output = invoke(kind, script, Duration.ofSeconds(5));
        switch (kind) {
            case "review-summary" -> assertThat(((ReviewSummary) output).highlights()).isEqualTo("제주🌊".repeat(32768));
            case "tag" -> assertThat(output).isEqualTo(List.of(new PlaceTag(0, "평온", "INDOOR")));
            case "select" -> assertThat(output).isEqualTo(List.of(new PlaceSelection(0, 0)));
            case "verify" -> assertThat(output).isEqualTo(List.of(new PlaceVerification(0, true)));
            case "itinerary" -> assertThat(output).isEqualTo(List.of(new ItineraryAssignment(7L, 1, 1)));
            default -> throw new IllegalArgumentException(kind);
        }
        verify(logs).recordFromStderr(contains("fixture-api-record"), eq(1L));
        assertThat(root.resolve("work/" + kind + "-1")).doesNotExist();
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

    private Path parentChild(boolean parentExits) throws Exception {
        return script("""
                import os, pathlib, subprocess, sys, time
                root = pathlib.Path(__file__).resolve().parent
                child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(90)'])
                (root / 'pids').write_text(str(os.getpid()) + '\\n' + str(child.pid))
                print('{"highlights":"완료"}', flush=True)
                """ + (parentExits ? "time.sleep(1)\n" : "time.sleep(90)\n"));
    }

    private void kill(List<ProcessHandle> owned) {
        for (ProcessHandle handle : owned) if (handle.isAlive()) handle.destroyForcibly();
    }

    // 운영은 리눅스, 프로세스 그룹/nice 전제
    @DisabledOnOs(OS.WINDOWS)
    @Test
    void 시간초과는_리뷰요약_실행기의_소유_부모와_자식을_종료한다() throws Exception {
        assertTimedOutRunnerCleanup("review-summary");
    }

    // 운영은 리눅스, 프로세스 그룹/nice 전제
    @DisabledOnOs(OS.WINDOWS)
    @Test
    void 시간초과는_태깅_실행기의_소유_부모와_자식을_종료한다() throws Exception {
        assertTimedOutRunnerCleanup("tag");
    }

    // 운영은 리눅스, 프로세스 그룹/nice 전제
    @DisabledOnOs(OS.WINDOWS)
    @Test
    void 시간초과는_장소선택_실행기의_소유_부모와_자식을_종료한다() throws Exception {
        assertTimedOutRunnerCleanup("select");
    }

    // 운영은 리눅스, 프로세스 그룹/nice 전제
    @DisabledOnOs(OS.WINDOWS)
    @Test
    void 시간초과는_장소검증_실행기의_소유_부모와_자식을_종료한다() throws Exception {
        assertTimedOutRunnerCleanup("verify");
    }

    // 운영은 리눅스, 프로세스 그룹/nice 전제
    @DisabledOnOs(OS.WINDOWS)
    @Test
    void 시간초과는_일정_실행기의_소유_부모와_자식을_종료한다() throws Exception {
        assertTimedOutRunnerCleanup("itinerary");
    }

    private void assertTimedOutRunnerCleanup(String kind) throws Exception {
        Path script = parentChild(false);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { invoke(kind, script, Duration.ofSeconds(2)); }
            catch (Throwable failure) { error.set(failure); }
        });
        List<ProcessHandle> owned = List.of(); worker.start();
        try {
            owned = handles(); worker.join(8000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(error.get()).isInstanceOf(PipelineException.class).hasMessageContaining("시간 초과");
            assertThat(owned).allMatch(handle -> !handle.isAlive());
            assertThat(root.resolve("work/" + kind + "-1")).doesNotExist();
        } finally { kill(owned); worker.interrupt(); worker.join(8000); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"review-summary", "tag", "select", "verify", "itinerary"})
    void 비정상_종료도_API기록_후_오류를_전달하고_파일을_지운다(String kind) throws Exception {
        Path script = script("import sys\nprint('fixture-api-record',file=sys.stderr)\nsys.exit(7)\n");
        assertThatThrownBy(() -> invoke(kind, script, Duration.ofSeconds(5)))
                .isInstanceOf(PipelineException.class).hasMessageContaining("exit=7");
        verify(logs).recordFromStderr(contains("fixture-api-record"), eq(1L));
        assertThat(root.resolve("work/" + kind + "-1")).doesNotExist();
    }

    @Test
    void 부모가_먼저_끝나도_관찰한_자식과_상속_stdout을_정리하고_반환한다() throws Exception {
        Path script = parentChild(true);
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { result.set(invoke("review-summary", script, Duration.ofSeconds(5))); }
            catch (Throwable failure) { error.set(failure); }
        });
        List<ProcessHandle> owned = List.of(); worker.start();
        try {
            owned = handles(); worker.join(8000);
            assertThat(worker.isAlive()).isFalse(); assertThat(error.get()).isNull();
            assertThat(((ReviewSummary) result.get()).highlights()).isEqualTo("완료");
            assertThat(owned).allMatch(handle -> !handle.isAlive());
        } finally { kill(owned); worker.interrupt(); worker.join(8000); }
    }

    @Test
    void 종료_미확인_반환은_입력과_출력을_모두_보존한다() throws Exception {
        OwnedProcessTerminator terminator = mock(OwnedProcessTerminator.class);
        when(terminator.terminate(any(), any(), anyLong())).thenReturn(false);
        JsonPythonProcessRunner runner = new JsonPythonProcessRunner(Duration.ofSeconds(5), terminator);
        Path script = script("print('{}')\n");
        Path input = root.resolve("unknown/input.json");
        assertThatThrownBy(() -> runner.run(script.toString(), "test-key", input, "[]", "fixture", 1L))
                .isInstanceOf(PipelineException.class).hasMessageContaining("종료를 확인하지 못했습니다");
        assertThat(input).exists();
        try (java.util.stream.Stream<Path> files = Files.list(input.getParent())) { assertThat(files.count()).isEqualTo(3); }
        verify(terminator).terminate(any(), any(), eq(1L));
    }

    @Test
    void 기존_작업_폴더는_덮거나_삭제하지_않는다() throws Exception {
        Path folder = Files.createDirectories(root.resolve("existing"));
        Path marker = folder.resolve("keep"); Files.writeString(marker, "preserve");
        assertThatThrownBy(() -> new JsonPythonProcessRunner(Duration.ofSeconds(1))
                .run("unused", "test-key", folder.resolve("input.json"), "[]", "fixture", 1L))
                .isInstanceOf(PipelineException.class);
        assertThat(Files.readString(marker)).isEqualTo("preserve");
        assertThat(folder.resolve("input.json")).doesNotExist();
    }

    @Test
    void 작업_폴더_생성_실패는_기존_파일을_삭제하지_않는다() throws Exception {
        Path file = root.resolve("blocked"); Files.writeString(file, "preserve");
        assertThatThrownBy(() -> new JsonPythonProcessRunner(Duration.ofSeconds(1))
                .run("unused", "test-key", file.resolve("input.json"), "[]", "fixture", 1L))
                .isInstanceOf(PipelineException.class);
        assertThat(Files.readString(file)).isEqualTo("preserve");
    }

    @Test
    void API기록_실패_시점에도_프로세스와_파일은_이미_정리돼_있다() throws Exception {
        Path script = script("print('{\"highlights\":\"완료\"}')\n");
        doThrow(new IllegalStateException("audit failure")).when(logs).recordFromStderr(any(), anyLong());
        assertThatThrownBy(() -> invoke("review-summary", script, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class).hasMessage("audit failure");
        assertThat(root.resolve("work/review-summary-1")).doesNotExist();
    }
}
