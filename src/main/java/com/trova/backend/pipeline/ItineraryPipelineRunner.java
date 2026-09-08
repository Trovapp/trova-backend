package com.trova.backend.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.SavedPlace;
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

@Component
public class ItineraryPipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(ItineraryPipelineRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_MINUTES = 2;
    private static final long STDOUT_JOIN_TIMEOUT_MILLIS = 30_000;

    private final String scriptPath;
    private final String workDirBase;
    private final String geminiApiKey;
    private final ApiCallLogService apiCallLogService;

    public ItineraryPipelineRunner(
            @Value("${app.pipeline.itinerary-script-path}") String scriptPath,
            @Value("${app.pipeline.work-dir}") String workDirBase,
            @Value("${app.pipeline.gemini-api-key}") String geminiApiKey,
            ApiCallLogService apiCallLogService
    ) {
        this.scriptPath = scriptPath;
        this.workDirBase = workDirBase;
        this.geminiApiKey = geminiApiKey;
        this.apiCallLogService = apiCallLogService;
    }

    public List<ItineraryAssignment> run(List<SavedPlace> places, Long jobId) {
        Path workDir = Path.of(workDirBase, "itinerary-" + jobId);
        Path inputFile = workDir.resolve("places.json");

        try {
            Files.createDirectories(workDir);
            List<Map<String, Object>> payload = places.stream()
                    .map(place -> Map.<String, Object>of(
                            "id", place.getId(),
                            "name", place.getPlaceName(),
                            "region", place.getRegion() == null ? "" : place.getRegion(),
                            "category", place.getCategory() == null ? "" : place.getCategory()
                    ))
                    .toList();
            Files.writeString(inputFile, MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PipelineException("일정 생성 입력 파일 작성 실패: " + e.getMessage(), e);
        }

        ProcessBuilder builder = new ProcessBuilder("python3", scriptPath, inputFile.toString());
        builder.environment().put("GEMINI_API_KEY", geminiApiKey);

        File stderrFile = null;
        try {
            stderrFile = File.createTempFile("trova-itinerary-", ".stderr");
            builder.redirectError(stderrFile);

            Process process = builder.start();

            StringBuilder stdoutBuffer = new StringBuilder();
            Thread stdoutReader = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    stdoutBuffer.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    log.warn("ProcessingJob {} 일정 생성 stdout 읽기 실패", jobId, e);
                }
            }, "itinerary-stdout-" + jobId);
            stdoutReader.setDaemon(true);
            stdoutReader.start();

            boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.error("ProcessingJob {} 일정 생성 실행 시간 초과({}분)", jobId, TIMEOUT_MINUTES);
                throw new PipelineException("일정 생성 실행 시간 초과: jobId=" + jobId);
            }

            stdoutReader.join(STDOUT_JOIN_TIMEOUT_MILLIS);

            String stderrContent = readStderr(stderrFile);
            apiCallLogService.recordFromStderr(stderrContent, jobId);

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("ProcessingJob {} 일정 생성 실행 실패(exit={}): {}", jobId, exitCode, stderrContent);
                throw new PipelineException("일정 생성 실행 실패(exit=" + exitCode + "): " + stderrContent);
            }

            return ItineraryPipelineOutputParser.parse(stdoutBuffer.toString());
        } catch (IOException e) {
            throw new PipelineException("일정 생성 프로세스 시작 실패: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PipelineException("일정 생성 실행 중 인터럽트: " + e.getMessage(), e);
        } finally {
            if (stderrFile != null && !stderrFile.delete()) {
                log.warn("일정 생성 stderr 임시 파일 삭제 실패: {}", stderrFile.getAbsolutePath());
            }
            try {
                Files.deleteIfExists(workDir.resolve("places.json"));
                Files.deleteIfExists(workDir);
            } catch (IOException e) {
                log.warn("일정 생성 작업 디렉터리 정리 실패: {}", workDir, e);
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
