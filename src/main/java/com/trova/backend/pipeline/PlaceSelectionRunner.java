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
    private final ApiCallLogService apiCallLogService;
    private final JsonPythonProcessRunner processRunner;

    @Autowired
    public PlaceSelectionRunner(
            @Value("${app.pipeline.select-script-path}") String scriptPath,
            @Value("${app.pipeline.work-dir}") String workDirBase,
            @Value("${app.pipeline.gemini-api-key}") String geminiApiKey,
            ApiCallLogService apiCallLogService
    ) {
        this(scriptPath, workDirBase, geminiApiKey, apiCallLogService, Duration.ofMinutes(TIMEOUT_MINUTES));
    }

    PlaceSelectionRunner(String scriptPath, String workDirBase, String geminiApiKey,
                      ApiCallLogService apiCallLogService, Duration timeout) {
        this.scriptPath = scriptPath;
        this.workDirBase = workDirBase;
        this.geminiApiKey = geminiApiKey;
        this.apiCallLogService = apiCallLogService;
        this.processRunner = new JsonPythonProcessRunner(timeout);
    }

    public List<PlaceSelection> run(List<SelectionCandidate> candidates, Long jobId) {
        Path workDir = Path.of(workDirBase, "select-" + jobId);
        Path inputFile = workDir.resolve("candidates.json");

        try {
            String inputJson = MAPPER.writeValueAsString(candidates);
            JsonPythonProcessRunner.Output output = processRunner.run(
                    scriptPath, geminiApiKey, inputFile, inputJson, "장소 선택", jobId);
            apiCallLogService.recordFromStderr(output.stderr(), jobId);
            if (output.exitCode() != 0) {
                log.error("장소 선택 {} 실행 실패(exit={}): {}", jobId, output.exitCode(), output.stderr());
                throw new PipelineException("장소 선택 실행 실패(exit=" + output.exitCode() + "): " + output.stderr());
            }
            return PlaceSelectionOutputParser.parse(output.stdout());
        } catch (IOException error) {
            throw new PipelineException("장소 선택 입력 파일 작성 실패: " + error.getMessage(), error);
        }
    }
}
