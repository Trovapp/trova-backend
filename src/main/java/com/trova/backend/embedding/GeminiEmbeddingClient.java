package com.trova.backend.embedding;

import java.util.List;
import java.util.Optional;

public interface GeminiEmbeddingClient {
    /** 실패(429, 타임아웃 등) 시 빈 Optional을 반환한다 — 호출부가 예외 처리를 안 해도 됨. */
    Optional<float[]> embed(String text);

    /**
     * 여러 문장을 요청 한 번으로 임베딩한다(batchEmbedContents, 최대 100개). 결과는 입력 순서와 같다.
     * 실패하거나 응답 개수가 입력과 다르면 전부 빈 Optional이다(일부만 돌려주지 않는다).
     */
    Optional<List<float[]>> embedBatch(List<String> texts);
}
