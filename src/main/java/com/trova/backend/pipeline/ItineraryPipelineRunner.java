package com.trova.backend.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.SavedPlace;
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

@Component
public class ItineraryPipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(ItineraryPipelineRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_MINUTES = 2;

    private final String scriptPath;
    private final String workDirBase;
    private final String geminiApiKey;
    private final ApiCallLogService apiCallLogService;
    private final JsonPythonProcessRunner processRunner;

    @Autowired
    public ItineraryPipelineRunner(
            @Value("${app.pipeline.itinerary-script-path}") String scriptPath,
            @Value("${app.pipeline.work-dir}") String workDirBase,
            @Value("${app.pipeline.gemini-api-key}") String geminiApiKey,
            ApiCallLogService apiCallLogService
    ) {
        this(scriptPath, workDirBase, geminiApiKey, apiCallLogService, Duration.ofMinutes(TIMEOUT_MINUTES));
    }

    ItineraryPipelineRunner(String scriptPath, String workDirBase, String geminiApiKey,
                      ApiCallLogService apiCallLogService, Duration timeout) {
        this.scriptPath = scriptPath;
        this.workDirBase = workDirBase;
        this.geminiApiKey = geminiApiKey;
        this.apiCallLogService = apiCallLogService;
        this.processRunner = new JsonPythonProcessRunner(timeout);
    }

    public List<ItineraryAssignment> run(List<SavedPlace> places, Long jobId) {
        Path workDir = Path.of(workDirBase, "itinerary-" + jobId);
        Path inputFile = workDir.resolve("places.json");

        try {
            List<Map<String, Object>> payload = places.stream()
                    .map(place -> Map.<String, Object>of(
                            "id", place.getId(),
                            "name", place.getPlaceName(),
                            "region", place.getRegion() == null ? "" : place.getRegion(),
                            "category", place.getCategory() == null ? "" : place.getCategory()
                    ))
                    .toList();
            String inputJson = MAPPER.writeValueAsString(payload);
            JsonPythonProcessRunner.Output output = processRunner.run(
                    scriptPath, geminiApiKey, inputFile, inputJson, "일정 생성", jobId);
            apiCallLogService.recordFromStderr(output.stderr(), jobId);
            if (output.exitCode() != 0) {
                log.error("일정 생성 {} 실행 실패(exit={}): {}", jobId, output.exitCode(), output.stderr());
                throw new PipelineException("일정 생성 실행 실패(exit=" + output.exitCode() + "): " + output.stderr());
            }
            return ItineraryPipelineOutputParser.parse(output.stdout());
        } catch (IOException error) {
            throw new PipelineException("일정 생성 입력 파일 작성 실패: " + error.getMessage(), error);
        }
    }
}
