package com.trova.backend.pipeline;

import com.trova.backend.service.ApiCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class PipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineRunner.class);

    private static final long TIMEOUT_MINUTES = 5;
    /** 파이프라인(extract_places.py)이 Gemini 하루 한도 소진 때 남기는 표식(#63) — 두 곳의 문자열이 같아야 한다. */
    public static final String DAILY_QUOTA_MARKER = "GEMINI_DAILY_QUOTA_EXCEEDED";
    /** 파이프라인(run_pipeline.py)이 인스타그램 속도 제한(429) 때 남기는 표식(#65). */
    public static final String SOURCE_RATE_LIMIT_MARKER = "SOURCE_RATE_LIMITED";
    // 인스타 게시물에서 영상도 사진도 받지 못함(#196) — run_pipeline.py NO_MEDIA_MARKER와 같아야 한다.
    public static final String NO_MEDIA_MARKER = "NO_MEDIA";
    // 장소를 소개하지 않는 콘텐츠(#206) — extract_places.py NOT_PLACE_CONTENT_MARKER와 같아야 한다.
    public static final String NOT_PLACE_CONTENT_MARKER = "NOT_PLACE_CONTENT";
    // 비공개·삭제·로그인 필요라 다시 해도 받을 수 없는 게시물·영상(#257) — run_pipeline.py SOURCE_UNAVAILABLE_MARKER와 같아야 한다.
    public static final String SOURCE_UNAVAILABLE_MARKER = "SOURCE_UNAVAILABLE";
    private static final long PROGRESS_POLL_MILLIS = 500;
    /** run_pipeline.py YOUTUBE_URL과 같은 기준 — 유튜브는 Gemini가 링크를 직접 보고, 나머지(인스타그램)는 서버가 내려받는다. */
    private static final Pattern YOUTUBE_URL = Pattern.compile("^https?://(?:www\\.|m\\.)?(?:youtube\\.com|youtu\\.be)/",
            Pattern.CASE_INSENSITIVE);

    private final String scriptPath;
    private final String workDirBase;
    private final String geminiApiKey;
    private final ApiCallLogService apiCallLogService;
    private final Duration processTimeout;
    // 분석이 몰려도 조회 요청이 먼저 CPU를 받게(#345). 0이면 그대로 실행한다. yt-dlp·ffmpeg는 우선순위를 물려받는다.
    private final int niceLevel;
    // 서버가 영상을 내려받아 프레임을 뽑는 분석(인스타그램)의 동시 실행 수(#345). null이면 제한 없음.
    private final Semaphore downloadSlots;

    @Autowired
    public PipelineRunner(
            @Value("${app.pipeline.script-path}") String scriptPath,
            @Value("${app.pipeline.work-dir}") String workDirBase,
            @Value("${app.pipeline.gemini-api-key}") String geminiApiKey,
            ApiCallLogService apiCallLogService,
            @Value("${app.pipeline.nice:10}") int niceLevel,
            @Value("${app.pipeline.download-concurrency:1}") int downloadConcurrency
    ) {
        this(scriptPath, workDirBase, geminiApiKey, apiCallLogService, Duration.ofMinutes(TIMEOUT_MINUTES),
                niceLevel, downloadConcurrency);
    }

    public PipelineRunner(String scriptPath, String workDirBase, String geminiApiKey, ApiCallLogService apiCallLogService) {
        this(scriptPath, workDirBase, geminiApiKey, apiCallLogService, Duration.ofMinutes(TIMEOUT_MINUTES));
    }

    PipelineRunner(String scriptPath, String workDirBase, String geminiApiKey,
                   ApiCallLogService apiCallLogService, Duration processTimeout) {
        this(scriptPath, workDirBase, geminiApiKey, apiCallLogService, processTimeout, 0, 0);
    }

    PipelineRunner(String scriptPath, String workDirBase, String geminiApiKey,
                   ApiCallLogService apiCallLogService, Duration processTimeout,
                   int niceLevel, int downloadConcurrency) {
        if (processTimeout.isNegative() || processTimeout.isZero()) {
            throw new IllegalArgumentException("process timeout must be positive");
        }
        if (niceLevel < 0 || niceLevel > 19) {
            throw new IllegalArgumentException("nice level must be 0..19");
        }
        this.scriptPath = scriptPath;
        this.workDirBase = workDirBase;
        this.geminiApiKey = geminiApiKey;
        this.apiCallLogService = apiCallLogService;
        this.processTimeout = processTimeout;
        this.niceLevel = niceLevel;
        this.downloadSlots = downloadConcurrency > 0 ? new Semaphore(downloadConcurrency, true) : null;
    }

    /** 파이썬 실행 명령. niceLevel이 0보다 크면 nice로 감싼다(리눅스·macOS 모두 /usr/bin/nice). */
    static List<String> command(int niceLevel, String scriptPath, String url, String workDir) {
        List<String> command = new ArrayList<>();
        if (niceLevel > 0) {
            command.add("nice");
            command.add("-n");
            command.add(String.valueOf(niceLevel));
        }
        command.add("python3");
        command.add(scriptPath);
        command.add(url);
        command.add(workDir);
        return command;
    }

    static boolean needsDownload(String url) {
        return !YOUTUBE_URL.matcher(url).find();
    }

    public PipelineOutput run(String url, Long jobId) {
        return run(url, jobId, progress -> { });
    }

    /** onProgress: 실행 중 파이프라인이 알려준 제목·찾은 장소 이름(#51). 이 메서드를 부른 스레드에서 호출된다. */
    public PipelineOutput run(String url, Long jobId, Consumer<PipelineProgress> onProgress) {
        Semaphore slots = downloadSlots != null && needsDownload(url) ? downloadSlots : null;
        if (slots == null) {
            return runProcess(url, jobId, onProgress);
        }
        long waitStartedAt = System.nanoTime();
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PipelineException("내려받기 분석 차례를 기다리다 인터럽트: " + jobId, e);
        }
        try {
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStartedAt);
            if (waitedMs >= 1000) {
                log.info("ProcessingJob {} 내려받기 분석 차례 대기 {}ms", jobId, waitedMs);
            }
            // 실행 시간 제한은 차례를 받은 뒤부터 센다.
            return runProcess(url, jobId, onProgress);
        } finally {
            slots.release();
        }
    }

    private PipelineOutput runProcess(String url, Long jobId, Consumer<PipelineProgress> onProgress) {
        Path workDir = Path.of(workDirBase, "job-" + jobId);
        ProcessBuilder builder = new ProcessBuilder(command(niceLevel, scriptPath, url, workDir.toString()));
        builder.environment().put("GEMINI_API_KEY", geminiApiKey);

        File stderrFile = null;
        File stdoutFile = null;
        Process process = null;
        Map<Long, ProcessHandle> descendants = new LinkedHashMap<>();
        boolean terminationAttempted = false;
        boolean terminationConfirmed = false;
        try {
            stderrFile = Files.createTempFile("trova-pipeline-", ".stderr").toFile();
            // stderr는 파이프 대신 임시 파일로 보낸다.
            // (redirectErrorStream(true)를 쓰면 stdout의 JSON이 오염되므로 사용하지 않는다)
            builder.redirectError(stderrFile);

            // 최종 JSON은 파일로 받는다. 자식이 stdout을 상속해도 reader 스레드가 남지 않는다.
            stdoutFile = Files.createTempFile("trova-pipeline-stdout-", ".json").toFile();
            builder.redirectOutput(stdoutFile);
            long processStartedAt = System.nanoTime();
            process = builder.start();

            // 끝날 때까지 기다리면서 0.5초마다 stderr에 새로 찍힌 진행 표식을 읽는다.
            StderrProgressTail tail = new StderrProgressTail(stderrFile.toPath(), onProgress);
            long deadline = System.nanoTime() + processTimeout.toNanos();
            boolean finished = false;
            while (!finished && System.nanoTime() < deadline) {
                captureDescendants(process, descendants);
                finished = process.waitFor(PROGRESS_POLL_MILLIS, TimeUnit.MILLISECONDS);
                tail.poll();
            }
            if (!finished) {
                log.error("ProcessingJob {} 파이프라인 실행 시간 초과({}ms): {}", jobId, processTimeout.toMillis(), url);
                throw new PipelineException("파이프라인 실행 시간 초과: " + url);
            }
            // 프로세스 시작~끝난 것을 확인한 때까지(#311). waitFor는 끝나면 바로 돌아오고, 그 뒤 진행 표식 읽기만 조금 더해진다.
            // Python이 잰 workerWallMs와의 차이가 인터프리터 시작·모듈 읽기·종료 비용이다.
            long processWallMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - processStartedAt);

            terminationAttempted = true;
            terminationConfirmed = terminateOwnedProcesses(process, descendants, jobId);
            if (!terminationConfirmed) {
                throw new PipelineException("파이프라인 프로세스 종료를 확인하지 못했습니다: " + jobId);
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("프로세스 정리 중 인터럽트");
            }
            String stderrContent = readStderr(stderrFile);
            apiCallLogService.recordFromStderr(stderrContent, jobId);

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("ProcessingJob {} 파이프라인 실행 실패(exit={}): {}", jobId, exitCode, stderrContent);
                throw new PipelineException("파이프라인 실행 실패(exit=" + exitCode + "): " + stderrContent);
            }

            return PipelineOutputParser.parse(Files.readString(stdoutFile.toPath(), StandardCharsets.UTF_8))
                    .withProcessWallMs(processWallMs);
        } catch (IOException e) {
            throw new PipelineException("파이프라인 실행/출력 처리 실패: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PipelineException("파이프라인 실행 중 인터럽트: " + e.getMessage(), e);
        } finally {
            // 인터럽트/콜백 오류/시간 초과에도 프로세스 종료를 먼저 확인한 뒤 파일을 지운다.
            if (!terminationAttempted) {
                terminationConfirmed = terminateOwnedProcesses(process, descendants, jobId);
            }
            if (terminationConfirmed) {
                deleteTemporaryFile(stderrFile);
                deleteTemporaryFile(stdoutFile);
                deleteWorkDir(workDir, jobId);
            } else {
                log.error("ProcessingJob {} 프로세스 종료 미확인 — 파일 보존: workDir={}, stdout={}, stderr={}",
                        jobId, workDir, stdoutFile, stderrFile);
            }
        }
    }

    private void captureDescendants(Process process, Map<Long, ProcessHandle> descendants) {
        OwnedProcessTerminator.capture(process, descendants);
    }

    boolean terminateOwnedProcesses(Process process, Map<Long, ProcessHandle> descendants, Long jobId) {
        return new OwnedProcessTerminator().terminate(process, descendants, jobId);
    }

    private void deleteTemporaryFile(File file) {
        if (file != null && !file.delete()) {
            log.warn("파이프라인 임시 파일 삭제 실패: {}", file.getAbsolutePath());
        }
    }

    private void deleteWorkDir(Path workDir, Long jobId) {
        if (!Files.exists(workDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(workDir)) {
            // 안쪽 파일부터 지워야 폴더를 지울 수 있다.
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("ProcessingJob {} 작업 파일 삭제 실패: {}", jobId, path, e);
                }
            });
        } catch (IOException e) {
            log.warn("ProcessingJob {} 작업 폴더 정리 실패: {}", jobId, workDir, e);
        }
    }

    private String readStderr(File stderrFile) {
        try {
            return Files.readString(stderrFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("파이프라인 stderr 파일 읽기 실패: {}", stderrFile.getAbsolutePath(), e);
            return "(stderr 읽기 실패: " + e.getMessage() + ")";
        }
    }
}
