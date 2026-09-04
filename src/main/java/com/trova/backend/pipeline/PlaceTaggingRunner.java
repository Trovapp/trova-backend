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
 * 추천엔진 후보 장소들에 Gemini로 mood/space 태그를 붙인다(요청당 최대 1회 호출).
 */
@Component
public class PlaceTaggingRunner {

    private static final Logger log = LoggerFactory.getLogger(PlaceTaggingRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_MINUTES = 2;
    private static final long STDOUT_JOIN_TIMEOUT_MILLIS = 30_000;

    public record TagCandidate(
            Integer index, String name, String category, Double rating,
            Integer userRatingCount, String priceLevel
    ) {
    }

    private final String scriptPath;
    private final String workDirBase;
    private final String geminiApiKey;
    private final ApiCallLogService apiCallLogService;

    public PlaceTaggingRunner(
            @Value("${app.pipeline.tag-places-script-path}") String scriptPath,
            @Value("${app.pipeline.work-dir}") String workDirBase,
            @Value("${app.pipeline.gemini-api-key}") String geminiApiKey,
            ApiCallLogService apiCallLogService
    ) {
        this.scriptPath = scriptPath;
        this.workDirBase = workDirBase;
        this.geminiApiKey = geminiApiKey;
        this.apiCallLogService = apiCallLogService;
    }

    public List<PlaceTag> run(List<TagCandidate> candidates, Long requestId) {
        Path workDir = Path.of(workDirBase, "tag-" + requestId);
        Path inputFile = workDir.resolve("candidates.json");

        try {
            Files.createDirectories(workDir);
            List<Map<String, Object>> payload = candidates.stream()
                    .map(c -> {
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("index", c.index());
                        m.put("name", c.name());
                        m.put("category", c.category());
                        m.put("rating", c.rating());
                        m.put("userRatingCount", c.userRatingCount());
                        m.put("priceLevel", c.priceLevel());
                        return m;
                    })
                    .toList();
            Files.writeString(inputFile, MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PipelineException("장소 태깅 입력 파일 작성 실패: " + e.getMessage(), e);
        }

        ProcessBuilder builder = new ProcessBuilder("python3", scriptPath, inputFile.toString());
        builder.environment().put("GEMINI_API_KEY", geminiApiKey);

        File stderrFile = null;
        try {
            stderrFile = File.createTempFile("trova-tag-", ".stderr");
            builder.redirectError(stderrFile);

            Process process = builder.start();

            StringBuilder stdoutBuffer = new StringBuilder();
            Thread stdoutReader = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    stdoutBuffer.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    log.warn("장소 태깅 stdout 읽기 실패", e);
                }
            }, "tag-stdout-" + requestId);
            stdoutReader.setDaemon(true);
            stdoutReader.start();

            boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.error("장소 태깅 실행 시간 초과({}분)", TIMEOUT_MINUTES);
                throw new PipelineException("장소 태깅 실행 시간 초과: requestId=" + requestId);
            }

            stdoutReader.join(STDOUT_JOIN_TIMEOUT_MILLIS);

            String stderrContent = readStderr(stderrFile);
            apiCallLogService.recordFromStderr(stderrContent, requestId);

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("장소 태깅 실행 실패(exit={}): {}", exitCode, stderrContent);
                throw new PipelineException("장소 태깅 실행 실패(exit=" + exitCode + "): " + stderrContent);
            }

            return PlaceTagOutputParser.parse(stdoutBuffer.toString());
        } catch (IOException e) {
            throw new PipelineException("장소 태깅 프로세스 시작 실패: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PipelineException("장소 태깅 실행 중 인터럽트: " + e.getMessage(), e);
        } finally {
            if (stderrFile != null && !stderrFile.delete()) {
                log.warn("장소 태깅 stderr 임시 파일 삭제 실패: {}", stderrFile.getAbsolutePath());
            }
            try {
                Files.deleteIfExists(inputFile);
                Files.deleteIfExists(workDir);
            } catch (IOException e) {
                log.warn("장소 태깅 작업 디렉터리 정리 실패: {}", workDir, e);
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
