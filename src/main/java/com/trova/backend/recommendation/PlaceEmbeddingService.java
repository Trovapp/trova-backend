package com.trova.backend.recommendation;

import com.trova.backend.embedding.GeminiEmbeddingClient;
import com.trova.backend.entity.Place;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.service.ApiCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 장소 임베딩을 검색 시점이 아니라 랭킹 직전(후보 목록이 정해진 시점)에 1회만
 * 계산해서 저장한다. Place는 googlePlaceId로 캐싱되므로 한 번 임베딩되면 이후
 * 요청에서는 재계산되지 않는다 — 매 검색마다 Gemini 호출이 늘지 않는 게 핵심.
 */
@Service
public class PlaceEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(PlaceEmbeddingService.class);

    private final GeminiEmbeddingClient geminiEmbeddingClient;
    private final PlaceRepository placeRepository;
    private final ApiCallLogService apiCallLogService;

    public PlaceEmbeddingService(
            GeminiEmbeddingClient geminiEmbeddingClient,
            PlaceRepository placeRepository,
            ApiCallLogService apiCallLogService
    ) {
        this.geminiEmbeddingClient = geminiEmbeddingClient;
        this.placeRepository = placeRepository;
        this.apiCallLogService = apiCallLogService;
    }

    public void ensureEmbeddings(List<Place> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        List<Long> ids = candidates.stream().map(Place::getId).toList();
        Set<Long> alreadyEmbedded;
        // findIdsWithEmbedding도 네이티브 쿼리(embedding은 pgvector 타입이라 JPA 필드로
        // 매핑되지 않음)라 pgvector 마이그레이션이 아직 안 된 환경에서는 SQL 오류가 날 수
        // 있다. 이 조회가 실패하면 이후 updateEmbedding도 똑같이 실패할 게 뻔하므로,
        // PersonalizationService.findSimilarSignals와 같은 원칙으로 여기서 즉시 포기하고
        // Gemini 호출도 하지 않는다 — findAlternatives/findGaps/recommend가 500이 되면 안 된다.
        try {
            alreadyEmbedded = new HashSet<>(placeRepository.findIdsWithEmbedding(ids));
        } catch (Exception e) {
            log.warn("임베딩 존재 여부 조회 실패, 이번 요청은 임베딩 생성을 건너뜁니다", e);
            return;
        }

        for (Place place : candidates) {
            if (alreadyEmbedded.contains(place.getId())) {
                continue;
            }
            ensureEmbedding(place);
        }
    }

    private void ensureEmbedding(Place place) {
        String text = buildEmbeddingText(place);
        long start = System.currentTimeMillis();
        Optional<float[]> embedding = geminiEmbeddingClient.embed(text);
        apiCallLogService.record(
                "gemini", "place-embedding", null, System.currentTimeMillis() - start,
                embedding.isPresent(), embedding.isPresent() ? null : "embedding generation failed",
                null, null, null);

        if (embedding.isEmpty()) {
            log.warn("장소 임베딩 생성 실패, 건너뜁니다: placeId={}", place.getId());
            return;
        }
        // updateEmbedding도 네이티브 쿼리라 DB 오류 가능성이 있다 — 한 장소의 저장
        // 실패로 배치 전체(나머지 후보들)가 중단되면 안 되므로 여기서 잡고 다음으로 넘어간다.
        try {
            placeRepository.updateEmbedding(place.getId(), toVectorLiteral(embedding.get()));
        } catch (Exception e) {
            log.warn("장소 임베딩 저장 실패, 건너뜁니다: placeId={}", place.getId(), e);
        }
    }

    /** mood/reviewSummary는 있을 때만 붙인다 — 태깅 전이거나 리뷰 요약이 없는 장소도 임베딩 대상이다. */
    private String buildEmbeddingText(Place place) {
        StringBuilder sb = new StringBuilder();
        sb.append(place.getName());
        if (place.getCategory() != null) {
            sb.append(" ").append(place.getCategory());
        }
        if (place.getMood() != null) {
            sb.append(" ").append(place.getMood());
        }
        if (place.getReviewSummary() != null) {
            sb.append(" ").append(place.getReviewSummary());
        }
        return sb.toString();
    }

    private String toVectorLiteral(float[] embedding) {
        return "[" + IntStream.range(0, embedding.length)
                .mapToObj(i -> String.valueOf(embedding[i]))
                .collect(Collectors.joining(",")) + "]";
    }
}
