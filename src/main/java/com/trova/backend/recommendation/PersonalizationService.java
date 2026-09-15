package com.trova.backend.recommendation;

import com.trova.backend.embedding.GeminiEmbeddingClient;
import com.trova.backend.embedding.GeminiTextClient;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.User;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.UserPreferenceSignalRepository;
import com.trova.backend.service.ApiCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * RAG 기반 개인화 — Retrieval(pgvector로 이 사용자의 과거 긍정 신호 중 후보와 비슷한
 * 것 찾기)과 Generation(그 검색 결과를 프롬프트에 넣어 추천 이유 한 줄 생성)을 잇는다.
 * 신호가 하나도 없는 콜드스타트 사용자는 항상 0점/빈 설명 — 기존 동작과 동일하게
 * 동작해야 하므로 별도 처리 없이 자연스럽게 폴백된다.
 */
@Service
public class PersonalizationService {

    private static final Logger log = LoggerFactory.getLogger(PersonalizationService.class);

    private final PlaceRepository placeRepository;
    private final UserPreferenceSignalRepository userPreferenceSignalRepository;
    private final GeminiTextClient geminiTextClient;
    private final GeminiEmbeddingClient geminiEmbeddingClient;
    private final ApiCallLogService apiCallLogService;

    public PersonalizationService(
            PlaceRepository placeRepository,
            UserPreferenceSignalRepository userPreferenceSignalRepository,
            GeminiTextClient geminiTextClient,
            GeminiEmbeddingClient geminiEmbeddingClient,
            ApiCallLogService apiCallLogService
    ) {
        this.placeRepository = placeRepository;
        this.userPreferenceSignalRepository = userPreferenceSignalRepository;
        this.geminiTextClient = geminiTextClient;
        this.geminiEmbeddingClient = geminiEmbeddingClient;
        this.apiCallLogService = apiCallLogService;
    }

    /**
     * Retrieval(findSimilarSignals) 결과와 그로부터 계산한 점수를 함께 반환한다 —
     * 호출자가 같은 후보에 대해 explainFromSignals를 또 부를 때 검색을 한 번 더
     * 하지 않고 이 결과의 similarSignals를 재사용할 수 있게 하기 위함이다.
     */
    public record PersonalizationResult(double score, List<UserPreferenceSignalRepository.SimilarSignal> similarSignals) {
    }

    public PersonalizationResult retrieveAndScore(User user, Place candidate) {
        List<UserPreferenceSignalRepository.SimilarSignal> similar = findSimilarSignals(user, candidate);
        double score = similar.isEmpty() ? 0.0 : similar.stream()
                .mapToDouble(UserPreferenceSignalRepository.SimilarSignal::getSimilarity)
                .average()
                .orElse(0.0);
        return new PersonalizationResult(score, similar);
    }

    public double personalizationScore(User user, Place candidate) {
        return retrieveAndScore(user, candidate).score();
    }

    /**
     * findSimilarSignals를 이미 호출해 similarSignals를 갖고 있는 호출자(예:
     * AlternativeFinderService가 retrieveAndScore로 이미 조회해둔 상위 후보)가
     * 검색을 다시 하지 않고 설명만 생성할 때 쓴다 — "검색을 두 번 하지 않는다" 원칙.
     */
    public Optional<String> explainFromSignals(Place candidate, List<UserPreferenceSignalRepository.SimilarSignal> similar) {
        if (similar.isEmpty()) {
            // 근거 없이 생성하면 그럴듯한 거짓 설명이 나올 위험이 있다 — 신호가 있을
            // 때만 생성한다(RAG의 핵심 원칙).
            return Optional.empty();
        }

        String pastPlaces = similar.stream()
                .map(s -> s.getName() + (s.getMood() != null ? "(" + s.getMood() + ")" : ""))
                .collect(Collectors.joining(", "));
        String prompt = String.format(
                "사용자가 예전에 좋아한 장소들: %s. 이번 추천 후보: %s(%s). " +
                        "왜 이 후보를 추천하는지 20자 내외 한국어 한 문장으로만 답해.",
                pastPlaces, candidate.getName(), candidate.getCategory() != null ? candidate.getCategory() : "");

        long start = System.currentTimeMillis();
        Optional<String> explanation = geminiTextClient.generate(prompt);
        apiCallLogService.record(
                "gemini", "recommendation-explanation", null, System.currentTimeMillis() - start,
                explanation.isPresent(), explanation.isPresent() ? null : "generation failed",
                null, null, null);

        return explanation;
    }

    public Optional<String> explainRecommendation(User user, Place candidate) {
        List<UserPreferenceSignalRepository.SimilarSignal> similar = findSimilarSignals(user, candidate);
        return explainFromSignals(candidate, similar);
    }

    /**
     * 대화형 비서가 "이번 턴 사용자 요청 문장"(예: "조용한 카페 알려줘")과 후보들의
     * 의미적 유사도를 계산한다. 과거 신호 기반 개인화(retrieveAndScore)와는 다른
     * 축 — 이건 이번 요청 자체의 의도를 임베딩으로 반영한다. 요청 문장은 매 턴
     * 새로 임베딩하므로(저장 안 함) 호출마다 Gemini 임베딩 API를 1회 쓴다.
     * 실패하면 빈 맵을 반환한다 — 호출부가 재정렬 없이 원래 순서로 폴백할 수 있게.
     */
    public Map<Long, Double> queryScores(String queryText, List<Long> placeIds) {
        if (placeIds.isEmpty()) {
            return Map.of();
        }
        long start = System.currentTimeMillis();
        Optional<float[]> embedding;
        try {
            embedding = geminiEmbeddingClient.embed(queryText);
        } catch (Exception e) {
            log.warn("요청 문장 임베딩 실패, 유사도 재정렬 없이 진행합니다", e);
            return Map.of();
        }
        apiCallLogService.record(
                "gemini", "conversation-query-embedding", null, System.currentTimeMillis() - start,
                embedding.isPresent(), embedding.isPresent() ? null : "embedding generation failed",
                null, null, null);
        if (embedding.isEmpty()) {
            return Map.of();
        }
        try {
            String vectorLiteral = toVectorLiteral(embedding.get());
            return placeRepository.findSimilarityToQuery(placeIds, vectorLiteral).stream()
                    .collect(Collectors.toMap(PlaceRepository.PlaceSimilarity::getId, PlaceRepository.PlaceSimilarity::getSimilarity));
        } catch (Exception e) {
            log.warn("요청 문장 유사도 조회 실패, 재정렬 없이 진행합니다", e);
            return Map.of();
        }
    }

    private String toVectorLiteral(float[] embedding) {
        return "[" + IntStream.range(0, embedding.length)
                .mapToObj(i -> String.valueOf(embedding[i]))
                .collect(Collectors.joining(",")) + "]";
    }

    private List<UserPreferenceSignalRepository.SimilarSignal> findSimilarSignals(User user, Place candidate) {
        // findEmbeddingText/findTopSimilarSignals 둘 다 네이티브 쿼리라(embedding은 pgvector
        // 타입이라 JPA 필드로 매핑되지 않음 — PlaceRepository 참고), 운영 Postgres가 아닌
        // 환경(H2 테스트 DB 등 vector 타입/embedding 컬럼이 없는 곳)에서 호출되면 SQL 문법
        // 오류가 날 수 있다. 개인화는 부가 기능이므로 이 경우도 0점/빈 결과로 조용히
        // 폴백한다 — try 블록을 findEmbeddingText 호출까지 감싼다.
        try {
            Optional<String> embeddingText = placeRepository.findEmbeddingText(candidate.getId());
            if (embeddingText.isEmpty()) {
                return List.of();
            }
            return userPreferenceSignalRepository.findTopSimilarSignals(user.getId(), embeddingText.get());
        } catch (Exception e) {
            log.warn("개인화 유사 신호 조회 실패, 0점으로 폴백합니다", e);
            return List.of();
        }
    }
}
