package com.trova.backend.recommendation;

import com.trova.backend.embedding.GeminiEmbeddingClient;
import com.trova.backend.entity.Place;
import com.trova.backend.repository.PlaceEmbeddingJdbcRepository;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.service.ApiCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
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
    static final Runnable NO_WORK = () -> {};

    private final GeminiEmbeddingClient geminiEmbeddingClient;
    private final PlaceRepository placeRepository;
    private final PlaceEmbeddingJdbcRepository placeEmbeddingJdbcRepository;
    private final ApiCallLogService apiCallLogService;

    public PlaceEmbeddingService(
            GeminiEmbeddingClient geminiEmbeddingClient,
            PlaceRepository placeRepository,
            PlaceEmbeddingJdbcRepository placeEmbeddingJdbcRepository,
            ApiCallLogService apiCallLogService
    ) {
        this.geminiEmbeddingClient = geminiEmbeddingClient;
        this.placeRepository = placeRepository;
        this.placeEmbeddingJdbcRepository = placeEmbeddingJdbcRepository;
        this.apiCallLogService = apiCallLogService;
    }

    public void ensureEmbeddings(List<Place> candidates) {
        ensureEmbeddingsWithWork(candidates, NO_WORK);
    }

    public record EmbeddingStats(int batchCalls, int places) {}

    /**
     * HTTP 대기 중 독립 작업을 요청 스레드에서 수행한다. 엔티티 접근·존재 조회·저장·API 기록은
     * 전부 요청 스레드에 남기고, 불변 텍스트 목록만 가상 스레드로 넘긴다. 추가 DB 연결은 없다.
     * 독립 작업은 임베딩이 없거나 존재 조회가 실패해도 정확히 한 번 실행한다.
     */
    public EmbeddingStats ensureEmbeddingsWithWork(List<Place> candidates, Runnable independentWork) {
        if (candidates.isEmpty()) {
            independentWork.run();
            return new EmbeddingStats(0, 0);
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
            independentWork.run();
            return new EmbeddingStats(0, 0);
        }

        List<Place> missing = candidates.stream()
                .filter(place -> !alreadyEmbedded.contains(place.getId()))
                .toList();
        if (missing.isEmpty()) {
            independentWork.run();
            return new EmbeddingStats(0, 0);
        }
        int calls = 0;
        // 한 요청에서 Gemini 호출 자체는 여전히 순차적이다. 대기 중 독립 작업만 겹친다.
        try (ExecutorService executor = independentWork == NO_WORK ? null : Executors.newVirtualThreadPerTaskExecutor()) {
            for (int from = 0; from < missing.size(); from += MAX_BATCH_SIZE) {
                List<Place> batch = missing.subList(from, Math.min(from + MAX_BATCH_SIZE, missing.size()));
                List<String> texts = batch.stream().map(this::buildEmbeddingText).toList();
                long start = System.currentTimeMillis();
                Future<GeneratedBatch> pending = executor == null
                        ? CompletableFuture.completedFuture(generateBatch(texts, start))
                        : executor.submit(() -> generateBatch(texts, start));
                calls++;
                if (from == 0) {
                    independentWork.run();
                }
                GeneratedBatch generated;
                try {
                    generated = pending.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("임베딩 대기 중 인터럽트", e);
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IllegalStateException("임베딩 생성 실패", e.getCause());
                }
                saveBatch(batch, generated.embeddings(), generated.latency());
            }
        }
        return new EmbeddingStats(calls, missing.size());
    }

    private record GeneratedBatch(Optional<List<float[]>> embeddings, long latency) {}

    private GeneratedBatch generateBatch(List<String> texts, long start) {
        Optional<List<float[]>> vectors = geminiEmbeddingClient.embedBatch(texts);
        return new GeneratedBatch(vectors, System.currentTimeMillis() - start);
    }

    private void saveBatch(List<Place> places, Optional<List<float[]>> embeddings, long latency) {
        // 무료 한도는 묶음 안의 문장 수로 센다 — 호출 기록도 문장마다 한 줄씩 남겨 줄 수가 사용량과 같게 한다.
        // latency는 묶음 요청 한 번의 시간이다. DB에는 묶음 INSERT 한 번으로 보낸다(#79).
        apiCallLogService.recordBatch(
                "gemini", "place-embedding-batch", latency,
                embeddings.isPresent(), embeddings.isPresent() ? null : "embedding generation failed",
                places.size());

        if (embeddings.isEmpty()) {
            log.warn("장소 임베딩 묶음 생성 실패, 이번 요청은 건너뜁니다: {}개", places.size());
            return;
        }
        long saveStart = System.currentTimeMillis();
        Map<Long, String> literals = new LinkedHashMap<>();
        for (int i = 0; i < places.size(); i++) {
            literals.put(places.get(i).getId(), toVectorLiteral(embeddings.get().get(i)));
        }
        // 장소마다 UPDATE를 따로 보내면 운영에서 18곳에 1263ms가 걸려서 묶음 전송 한 번으로 저장한다(#79).
        // 묶음은 한 건이 실패하면 전체가 실패할 수 있다 — 저장되지 않은 장소는 다음 검색 때 다시 대상이 된다.
        try {
            placeEmbeddingJdbcRepository.updateEmbeddings(literals);
        } catch (Exception e) {
            log.warn("장소 임베딩 묶음 저장 실패, 이번 요청은 건너뜁니다: {}개", places.size(), e);
        }
        // 구간별 시간 확인용(#77): 묶음 요청과 DB 저장 중 어디가 긴지 본다.
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
