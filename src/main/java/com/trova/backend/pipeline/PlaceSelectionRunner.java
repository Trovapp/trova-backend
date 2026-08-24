package com.trova.backend.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.TimeUnit;

/**
 * 카카오 검색이 후보를 여러 개 반환했을 때, 1등을 무조건 채택하지 않고 영상 맥락에
 * 맞는 후보를 Gemini가 고르게 한다(영상당 최대 1회 호출).
 * 입력: {@code List<SelectionCandidate>} — 이 영상 안에서 재검토가 필요한 장소만.
 */
@Component
public class PlaceSelectionRunner {

    private static final Logger log = LoggerFactory.getLogger(PlaceSelectionRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_MINUTES = 2;
    private static final long STDOUT_JOIN_TIMEOUT_MILLIS = 30_000;

    public record CandidateOption(
            Integer candidateIndex, String placeName, String addressName,
            String roadAddressName, String categoryName
    ) {
    }

    public record SelectionCandidate(
            Integer index, String extractedName, String region, List<CandidateOption> candidates
    ) {
    }

    private final String scriptPath;
    private final String workDirBase;
    private final String geminiApiKey;

    public PlaceSelectionRunner(
            @Value("${app.pipeline.select-script-path}") String scriptPath,
            @Value("${app.pipeline.work-dir}") String workDirBase,
            @Value("${app.pipeline.gemini-api-key}") String geminiApiKey
    ) {
        this.scriptPath = scriptPath;
        this.workDirBase = workDirBase;
        this.geminiApiKey = geminiApiKey;
    }

    public List<PlaceSelection> run(List<SelectionCandidate> candidates, Long jobId) {
        Path workDir = Path.of(workDirBase, "select-" + jobId);
        Path inputFile = workDir.resolve("candidates.json");

        try {
            Files.createDirectories(workDir);
            Files.writeString(inputFile, MAPPER.writeValueAsString(candidates), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PipelineException("장소 선택 입력 파일 작성 실패: " + e.getMessage(), e);
        }

        ProcessBuilder builder = new ProcessBuilder("python3", scriptPath, inputFile.toString());
        builder.environment().put("GEMINI_API_KEY", geminiApiKey);

        File stderrFile = null;
        try {
            stderrFile = File.createTempFile("trova-select-", ".stderr");
            builder.redirectError(stderrFile);

            Process process = builder.start();

            StringBuilder stdoutBuffer = new StringBuilder();
            Thread stdoutReader = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    stdoutBuffer.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    log.warn("ProcessingJob {} 장소 선택 stdout 읽기 실패", jobId, e);
                }
            }, "select-stdout-" + jobId);
            stdoutReader.setDaemon(true);
            stdoutReader.start();

            boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.error("ProcessingJob {} 장소 선택 실행 시간 초과({}분)", jobId, TIMEOUT_MINUTES);
                throw new PipelineException("장소 선택 실행 시간 초과: jobId=" + jobId);
            }

            stdoutReader.join(STDOUT_JOIN_TIMEOUT_MILLIS);

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String stderr = readStderr(stderrFile);
                log.error("ProcessingJob {} 장소 선택 실행 실패(exit={}): {}", jobId, exitCode, stderr);
                throw new PipelineException("장소 선택 실행 실패(exit=" + exitCode + "): " + stderr);
            }

            return PlaceSelectionOutputParser.parse(stdoutBuffer.toString());
        } catch (IOException e) {
            throw new PipelineException("장소 선택 프로세스 시작 실패: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PipelineException("장소 선택 실행 중 인터럽트: " + e.getMessage(), e);
        } finally {
            if (stderrFile != null && !stderrFile.delete()) {
                log.warn("장소 선택 stderr 임시 파일 삭제 실패: {}", stderrFile.getAbsolutePath());
            }
            try {
                Files.deleteIfExists(inputFile);
                Files.deleteIfExists(workDir);
            } catch (IOException e) {
                log.warn("장소 선택 작업 디렉터리 정리 실패: {}", workDir, e);
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
