package com.trova.backend.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.service.ApiCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 좌표가 불확실한 장소들을 Gemini로 한 번에 검증한다(영상당 최대 1회 호출).
 * 입력: {@code List<VerificationCandidate>} — 이 영상 안에서 검증이 필요한 장소만.
 */
@Component
public class PlaceVerificationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlaceVerificationRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_MINUTES = 2;

    public record VerificationCandidate(Integer index, String name, String region) {
    }

    private final String scriptPath;
    private final String workDirBase;
    private final String geminiApiKey;
    private final ApiCallLogService apiCallLogService;
    private final JsonPythonProcessRunner processRunner;

    @Autowired
    public PlaceVerificationRunner(
            @Value("${app.pipeline.verify-script-path}") String scriptPath,
            @Value("${app.pipeline.work-dir}") String workDirBase,
            @Value("${app.pipeline.gemini-api-key}") String geminiApiKey,
            ApiCallLogService apiCallLogService
    ) {
        this(scriptPath, workDirBase, geminiApiKey, apiCallLogService, Duration.ofMinutes(TIMEOUT_MINUTES));
    }

    PlaceVerificationRunner(String scriptPath, String workDirBase, String geminiApiKey,
                      ApiCallLogService apiCallLogService, Duration timeout) {
        this.scriptPath = scriptPath;
        this.workDirBase = workDirBase;
        this.geminiApiKey = geminiApiKey;
        this.apiCallLogService = apiCallLogService;
        this.processRunner = new JsonPythonProcessRunner(timeout);
    }

    public List<PlaceVerification> run(List<VerificationCandidate> candidates, Long jobId) {
        Path workDir = Path.of(workDirBase, "verify-" + jobId);
        Path inputFile = workDir.resolve("candidates.json");

        try {
            List<Map<String, Object>> payload = candidates.stream()
                    .map(c -> Map.<String, Object>of(
                            "index", c.index(),
                            "name", c.name(),
                            "region", c.region() == null ? "" : c.region()
                    ))
                    .toList();
            String inputJson = MAPPER.writeValueAsString(payload);
            JsonPythonProcessRunner.Output output = processRunner.run(
                    scriptPath, geminiApiKey, inputFile, inputJson, "장소 검증", jobId);
            apiCallLogService.recordFromStderr(output.stderr(), jobId);
            if (output.exitCode() != 0) {
                log.error("장소 검증 {} 실행 실패(exit={}): {}", jobId, output.exitCode(), output.stderr());
                throw new PipelineException("장소 검증 실행 실패(exit=" + output.exitCode() + "): " + output.stderr());
            }
            return PlaceVerificationOutputParser.parse(output.stdout());
        } catch (IOException error) {
            throw new PipelineException("장소 검증 입력 파일 작성 실패: " + error.getMessage(), error);
        }
    }
}
