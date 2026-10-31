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
 * 추천엔진 후보 장소들에 Gemini로 mood/space 태그를 붙인다(요청당 최대 1회 호출).
 */
@Component
public class PlaceTaggingRunner {

    private static final Logger log = LoggerFactory.getLogger(PlaceTaggingRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_MINUTES = 2;

    public record TagCandidate(
            Integer index, String name, String category, Double rating,
            Integer userRatingCount, String priceLevel
    ) {
    }

    private final String scriptPath;
    private final String workDirBase;
    private final String geminiApiKey;
    private final ApiCallLogService apiCallLogService;
    private final JsonPythonProcessRunner processRunner;

    @Autowired
    public PlaceTaggingRunner(
            @Value("${app.pipeline.tag-places-script-path}") String scriptPath,
            @Value("${app.pipeline.work-dir}") String workDirBase,
            @Value("${app.pipeline.gemini-api-key}") String geminiApiKey,
            ApiCallLogService apiCallLogService
    ) {
        this(scriptPath, workDirBase, geminiApiKey, apiCallLogService, Duration.ofMinutes(TIMEOUT_MINUTES));
    }

    PlaceTaggingRunner(String scriptPath, String workDirBase, String geminiApiKey,
                      ApiCallLogService apiCallLogService, Duration timeout) {
        this.scriptPath = scriptPath;
        this.workDirBase = workDirBase;
        this.geminiApiKey = geminiApiKey;
        this.apiCallLogService = apiCallLogService;
        this.processRunner = new JsonPythonProcessRunner(timeout);
    }

    public List<PlaceTag> run(List<TagCandidate> candidates, Long requestId) {
        Path workDir = Path.of(workDirBase, "tag-" + requestId);
        Path inputFile = workDir.resolve("candidates.json");

        try {
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
            String inputJson = MAPPER.writeValueAsString(payload);
            JsonPythonProcessRunner.Output output = processRunner.run(
                    scriptPath, geminiApiKey, inputFile, inputJson, "장소 태깅", requestId);
            apiCallLogService.recordFromStderr(output.stderr(), requestId);
            if (output.exitCode() != 0) {
                log.error("장소 태깅 {} 실행 실패(exit={}): {}", requestId, output.exitCode(), output.stderr());
                throw new PipelineException("장소 태깅 실행 실패(exit=" + output.exitCode() + "): " + output.stderr());
            }
            return PlaceTagOutputParser.parse(output.stdout());
        } catch (IOException error) {
            throw new PipelineException("장소 태깅 입력 파일 작성 실패: " + error.getMessage(), error);
        }
    }
}
