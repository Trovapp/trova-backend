package com.trova.backend.embedding;

import java.util.Optional;

public interface GeminiEmbeddingClient {
    /** 실패(429, 타임아웃 등) 시 빈 Optional을 반환한다 — 호출부가 예외 처리를 안 해도 됨. */
    Optional<float[]> embed(String text);
}
