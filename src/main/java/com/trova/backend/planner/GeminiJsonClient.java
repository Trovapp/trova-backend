package com.trova.backend.planner;

import java.util.Optional;

/**
 * 일정 에이전트용 Gemini 호출 — JSON으로만 답하게 하고(responseMimeType), 429·5xx는 짧게 다시 시도한다.
 * 호출마다 api_call_logs에 operation 이름으로 시간·토큰을 남긴다(eval에서 요청당 호출 수·토큰을 센다).
 */
public interface GeminiJsonClient {

    /** 실패하거나(재시도 후에도) 응답이 비면 빈 값 — 호출하는 쪽이 코드 규칙으로 대신한다. */
    Optional<String> generateJson(String prompt, String operation);
}
