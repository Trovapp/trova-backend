package com.trova.backend.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 좌표가 불확실한 장소들을 Gemini로 한 번에 검증한다(영상당 최대 1회 호출).
 * 입력: {@code List<VerificationCandidate>} — 이 영상 안에서 검증이 필요한 장소만.
 */
@Component
public class PlaceVerificationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlaceVerificationRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_MINUTES = 2;
    private static final long STDOUT_JOIN_TIMEOUT_MILLIS = 30_000;

    public record VerificationCandidate(Integer index, String name, String region) {
    }

    private final String scriptPath;
    private final String workDirBase;
    private final String geminiApiKey;
    private final ApiCallLogService apiCallLogService;

    public PlaceVerificationRunner(
            @Value("${app.pipeline.verify-script-path}") String scriptPath,
            @Value("${app.pipeline.work-dir}") String workDirBase,
            @Value("${app.pipeline.gemini-api-key}") String geminiApiKey,
            ApiCallLogService apiCallLogService
    ) {
        this.scriptPath = scriptPath;
        this.workDirBase = workDirBase;
        this.geminiApiKey = geminiApiKey;
        this.apiCallLogService = apiCallLogService;
    }

    public List<PlaceVerification> run(List<VerificationCandidate> candidates, Long jobId) {
        Path workDir = Path.of(workDirBase, "verify-" + jobId);
        Path inputFile = workDir.resolve("candidates.json");

        try {
            Files.createDirectories(workDir);
            List<Map<String, Object>> payload = candidates.stream()
                    .map(c -> Map.<String, Object>of(
                            "index", c.index(),
                            "name", c.name(),
                            "region", c.region() == null ? "" : c.region()
                    ))
                    .toList();
            Files.writeString(inputFile, MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PipelineException("장소 검증 입력 파일 작성 실패: " + e.getMessage(), e);
        }

        ProcessBuilder builder = new ProcessBuilder("python3", scriptPath, inputFile.toString());
        builder.environment().put("GEMINI_API_KEY", geminiApiKey);

        File stderrFile = null;
        try {
            stderrFile = File.createTempFile("trova-verify-", ".stderr");
            builder.redirectError(stderrFile);

            Process process = builder.start();

            StringBuilder stdoutBuffer = new StringBuilder();
            Thread stdoutReader = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    stdoutBuffer.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    log.warn("ProcessingJob {} 장소 검증 stdout 읽기 실패", jobId, e);
                }
            }, "verify-stdout-" + jobId);
            stdoutReader.setDaemon(true);
            stdoutReader.start();

            boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.error("ProcessingJob {} 장소 검증 실행 시간 초과({}분)", jobId, TIMEOUT_MINUTES);
                throw new PipelineException("장소 검증 실행 시간 초과: jobId=" + jobId);
            }

            stdoutReader.join(STDOUT_JOIN_TIMEOUT_MILLIS);

            String stderrContent = readStderr(stderrFile);
            apiCallLogService.recordFromStderr(stderrContent, jobId);

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("ProcessingJob {} 장소 검증 실행 실패(exit={}): {}", jobId, exitCode, stderrContent);
                throw new PipelineException("장소 검증 실행 실패(exit=" + exitCode + "): " + stderrContent);
            }

            return PlaceVerificationOutputParser.parse(stdoutBuffer.toString());
        } catch (IOException e) {
            throw new PipelineException("장소 검증 프로세스 시작 실패: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PipelineException("장소 검증 실행 중 인터럽트: " + e.getMessage(), e);
        } finally {
            if (stderrFile != null && !stderrFile.delete()) {
                log.warn("장소 검증 stderr 임시 파일 삭제 실패: {}", stderrFile.getAbsolutePath());
            }
            try {
                Files.deleteIfExists(inputFile);
                Files.deleteIfExists(workDir);
            } catch (IOException e) {
                log.warn("장소 검증 작업 디렉터리 정리 실패: {}", workDir, e);
            }
        }
    }

    private String readStderr(File stderrFile) {
        try {
            return Files.readString(stderrFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(stderr 읽기 실패: " + e.getMessage() + ")";
        }
    }
}
