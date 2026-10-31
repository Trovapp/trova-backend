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

/** 장소 리뷰 텍스트 목록을 Gemini로 2~3문장 요약한다(요청당 1회 호출, 리뷰당 별도 호출 아님). */
@Component
public class ReviewSummaryRunner {

    private static final Logger log = LoggerFactory.getLogger(ReviewSummaryRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_MINUTES = 2;

    private final String scriptPath;
    private final String workDirBase;
    private final String geminiApiKey;
    private final ApiCallLogService apiCallLogService;
    private final JsonPythonProcessRunner processRunner;

    @Autowired
    public ReviewSummaryRunner(
            @Value("${app.pipeline.summarize-reviews-script-path}") String scriptPath,
            @Value("${app.pipeline.work-dir}") String workDirBase,
            @Value("${app.pipeline.gemini-api-key}") String geminiApiKey,
            ApiCallLogService apiCallLogService
    ) {
        this(scriptPath, workDirBase, geminiApiKey, apiCallLogService, Duration.ofMinutes(TIMEOUT_MINUTES));
    }

    ReviewSummaryRunner(String scriptPath, String workDirBase, String geminiApiKey,
                      ApiCallLogService apiCallLogService, Duration timeout) {
        this.scriptPath = scriptPath;
        this.workDirBase = workDirBase;
        this.geminiApiKey = geminiApiKey;
        this.apiCallLogService = apiCallLogService;
        this.processRunner = new JsonPythonProcessRunner(timeout);
    }

    public ReviewSummary run(List<String> reviewTexts, Long requestId) {
        Path workDir = Path.of(workDirBase, "review-summary-" + requestId);
        Path inputFile = workDir.resolve("reviews.json");

        try {
            String inputJson = MAPPER.writeValueAsString(reviewTexts);
            JsonPythonProcessRunner.Output output = processRunner.run(
                    scriptPath, geminiApiKey, inputFile, inputJson, "리뷰 요약", requestId);
            apiCallLogService.recordFromStderr(output.stderr(), requestId);
            if (output.exitCode() != 0) {
                log.error("리뷰 요약 {} 실행 실패(exit={}): {}", requestId, output.exitCode(), output.stderr());
                throw new PipelineException("리뷰 요약 실행 실패(exit=" + output.exitCode() + "): " + output.stderr());
            }
            return ReviewSummaryOutputParser.parse(output.stdout());
        } catch (IOException error) {
            throw new PipelineException("리뷰 요약 입력 파일 작성 실패: " + error.getMessage(), error);
        }
    }
}
