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
    // batchEmbedContents 한 번에 보낼 수 있는 최대 문장 수
    static final int MAX_BATCH_SIZE = 100;

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

        List<Place> missing = candidates.stream()
                .filter(place -> !alreadyEmbedded.contains(place.getId()))
                .toList();
        for (int from = 0; from < missing.size(); from += MAX_BATCH_SIZE) {
            embedBatch(missing.subList(from, Math.min(from + MAX_BATCH_SIZE, missing.size())));
        }
    }

    // 처음 보는 지역에서는 새 장소가 한꺼번에 들어온다(운영 실측: 김해 19개, 전주 16개). 하나씩 부르면
    // 개당 약 0.4~0.5초가 쌓여 대화 첫 응답이 8~9초 늦어져서, 요청 한 번으로 묶는다(#73).
    // 묶음이 실패하면 이번엔 전부 건너뛴다 — 저장되지 않은 장소는 다음 검색 때 다시 대상이 된다.
    private void embedBatch(List<Place> places) {
        List<String> texts = places.stream().map(this::buildEmbeddingText).toList();
        long start = System.currentTimeMillis();
        Optional<List<float[]>> embeddings = geminiEmbeddingClient.embedBatch(texts);
        long latency = System.currentTimeMillis() - start;
        // 무료 한도는 묶음 안의 문장 수로 센다 — 호출 기록도 문장마다 한 줄씩 남겨 줄 수가 사용량과 같게 한다.
        // latency는 묶음 요청 한 번의 시간이다.
        for (int i = 0; i < places.size(); i++) {
            apiCallLogService.record(
                    "gemini", "place-embedding-batch", null, latency,
                    embeddings.isPresent(), embeddings.isPresent() ? null : "embedding generation failed",
                    null, null, null);
        }

        if (embeddings.isEmpty()) {
            log.warn("장소 임베딩 묶음 생성 실패, 이번 요청은 건너뜁니다: {}개", places.size());
            return;
        }
        long saveStart = System.currentTimeMillis();
        for (int i = 0; i < places.size(); i++) {
            Place place = places.get(i);
            // updateEmbedding도 네이티브 쿼리라 DB 오류 가능성이 있다 — 한 장소의 저장
            // 실패로 나머지 후보들이 중단되면 안 되므로 여기서 잡고 다음으로 넘어간다.
            try {
                placeRepository.updateEmbedding(place.getId(), toVectorLiteral(embeddings.get().get(i)));
            } catch (Exception e) {
                log.warn("장소 임베딩 저장 실패, 건너뜁니다: placeId={}", place.getId(), e);
            }
        }
        // 구간별 시간 확인용(#77): 묶음 요청과 DB 저장(장소마다 한 번씩) 중 어디가 긴지 본다.
        log.info("장소 임베딩 묶음: {}개, 요청={}ms, 저장={}ms", places.size(), latency, System.currentTimeMillis() - saveStart);
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
