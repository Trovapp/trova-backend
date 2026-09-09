package com.trova.backend.embedding;

import java.util.Optional;

public interface GeminiTextClient {
    /** 실패 시 빈 Optional. 짧은 문장 생성 전용(추천 이유 등) — 긴 구조화 응답은 기존 python 파이프라인을 계속 쓴다. */
    Optional<String> generate(String prompt);
}
