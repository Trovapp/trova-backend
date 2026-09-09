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
        Set<Long> alreadyEmbedded = new HashSet<>(placeRepository.findIdsWithEmbedding(ids));

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
        placeRepository.updateEmbedding(place.getId(), toVectorLiteral(embedding.get()));
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
