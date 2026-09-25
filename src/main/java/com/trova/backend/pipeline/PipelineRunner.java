package com.trova.backend.pipeline;

import com.trova.backend.service.ApiCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Component
public class PipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineRunner.class);

    private static final long TIMEOUT_MINUTES = 5;
    private static final long STDOUT_JOIN_TIMEOUT_MILLIS = 30_000;

    private final String scriptPath;
    private final String workDirBase;
    private final String geminiApiKey;
    private final ApiCallLogService apiCallLogService;

    public PipelineRunner(
            @Value("${app.pipeline.script-path}") String scriptPath,
            @Value("${app.pipeline.work-dir}") String workDirBase,
            @Value("${app.pipeline.gemini-api-key}") String geminiApiKey,
            ApiCallLogService apiCallLogService
    ) {
        this.scriptPath = scriptPath;
        this.workDirBase = workDirBase;
        this.geminiApiKey = geminiApiKey;
        this.apiCallLogService = apiCallLogService;
    }

    public PipelineOutput run(String url, Long jobId) {
        Path workDir = Path.of(workDirBase, "job-" + jobId);
        ProcessBuilder builder = new ProcessBuilder("python3", scriptPath, url, workDir.toString());
        builder.environment().put("GEMINI_API_KEY", geminiApiKey);

        File stderrFile = null;
        try {
            stderrFile = File.createTempFile("trova-pipeline-", ".stderr");
            // stderr는 파이프 대신 임시 파일로 보낸다.
            // (redirectErrorStream(true)를 쓰면 stdout의 JSON이 오염되므로 사용하지 않는다)
            builder.redirectError(stderrFile);

            Process process = builder.start();

            StringBuilder stdoutBuffer = new StringBuilder();
            Thread stdoutReader = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    stdoutBuffer.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    log.warn("ProcessingJob {} 파이프라인 stdout 읽기 실패", jobId, e);
                }
            }, "pipeline-stdout-" + jobId);
            stdoutReader.setDaemon(true);
            stdoutReader.start();

            boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.error("ProcessingJob {} 파이프라인 실행 시간 초과({}분): {}", jobId, TIMEOUT_MINUTES, url);
                throw new PipelineException("파이프라인 실행 시간 초과: " + url);
            }

            stdoutReader.join(STDOUT_JOIN_TIMEOUT_MILLIS);

            String stderrContent = readStderr(stderrFile);
            apiCallLogService.recordFromStderr(stderrContent, jobId);

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("ProcessingJob {} 파이프라인 실행 실패(exit={}): {}", jobId, exitCode, stderrContent);
                throw new PipelineException("파이프라인 실행 실패(exit=" + exitCode + "): " + stderrContent);
            }

            return PipelineOutputParser.parse(stdoutBuffer.toString());
        } catch (IOException e) {
            throw new PipelineException("파이프라인 프로세스 시작 실패: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PipelineException("파이프라인 실행 중 인터럽트: " + e.getMessage(), e);
        } finally {
            if (stderrFile != null && !stderrFile.delete()) {
                log.warn("파이프라인 stderr 임시 파일 삭제 실패: {}", stderrFile.getAbsolutePath());
            }
            // 결과는 stdout(JSON)으로만 받으므로 내려받은 영상·자막·오디오·프레임은 더 쓰지 않는다.
            // 남겨두면 작업마다 수십 MB가 쌓이고(#17) 타인의 영상을 계속 보관하게 되므로 성공·실패·시간 초과 모두 지운다.
            deleteWorkDir(workDir, jobId);
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
