package com.trova.backend.recommendation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.Place;
import com.trova.backend.pipeline.ReviewSummary;
import com.trova.backend.pipeline.ReviewSummaryRunner;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.service.ApiCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 장소 리뷰요약(구조화: highlights/pros/cons/hours/fee/tips/checklist)+원문 스니펫
 * (최대 3개)을 캐시-우선으로 제공한다. Place Details API는 유료 티어라, 캐시가 있으면
 * 절대 다시 부르지 않는다(영구 캐시 — 갱신 정책 없음).
 *
 * Place.reviewSummary(TEXT 컬럼)에는 이제 구조화된 ReviewSummary를 JSON 문자열로
 * 직렬화해서 저장한다 — 기존 스키마를 그대로 재사용하되(마이그레이션 불필요), 예전
 * 포맷(평문 문장)으로 저장된 캐시는 역직렬화가 실패하면 캐시 미스로 취급해서 새
 * 포맷으로 다시 생성한다(개발 단계라 소수의 기존 캐시만 영향받음).
 */
@Service
public class PlaceReviewService {

    private static final Logger log = LoggerFactory.getLogger(PlaceReviewService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final int MAX_SNIPPETS = 3;

    private static final ReviewSummary NO_REVIEWS_SUMMARY = new ReviewSummary(
            "리뷰 정보가 없어요.", List.of(), List.of(), null, null, List.of(), List.of());
    private static final ReviewSummary FETCH_FAILED_SUMMARY = new ReviewSummary(
            "리뷰를 불러오지 못했어요.", List.of(), List.of(), null, null, List.of(), List.of());

    private final PlaceRepository placeRepository;
    private final GooglePlacesApiClient googlePlacesApiClient;
    private final ReviewSummaryRunner reviewSummaryRunner;
    private final ApiCallLogService apiCallLogService;

    public record PlaceReviewInfo(ReviewSummary summary, List<String> snippets) {
    }

    private record PlaceState(Place place, PlaceReviewInfo cachedInfo) {
    }

    public PlaceReviewService(
            PlaceRepository placeRepository,
            GooglePlacesApiClient googlePlacesApiClient,
            ReviewSummaryRunner reviewSummaryRunner,
            ApiCallLogService apiCallLogService
    ) {
        this.placeRepository = placeRepository;
        this.googlePlacesApiClient = googlePlacesApiClient;
        this.reviewSummaryRunner = reviewSummaryRunner;
        this.apiCallLogService = apiCallLogService;
    }

    /**
     * @ElementCollection(reviewSnippets)은 LAZY라, 트랜잭션 밖(open-in-view: false)에서
     * 읽으면 LazyInitializationException이 난다. 실제 방어는 이 메서드가 아니라
     * PlaceRepository.findById()에 붙인 @EntityGraph(attributePaths = "reviewSnippets")
     * 쪽에서 한다 — findById 쿼리 시점에 reviewSnippets까지 JOIN FETCH로 즉시
     * 초기화되므로, 이후 트랜잭션이 끝나고 엔티티가 detach돼도 안전하게 읽힌다.
     */
    private Optional<PlaceState> loadPlaceState(Long placeId) {
        return placeRepository.findById(placeId).map(place -> {
            ReviewSummary cachedSummary = place.getReviewSummary() != null
                    ? deserializeSummary(place.getReviewSummary())
                    : null;
            PlaceReviewInfo cached = cachedSummary != null
                    ? new PlaceReviewInfo(cachedSummary, List.copyOf(place.getReviewSnippets()))
                    : null;
            return new PlaceState(place, cached);
        });
    }

    public Optional<PlaceReviewInfo> getOrGenerateSummary(Long placeId) {
        Optional<PlaceState> state = loadPlaceState(placeId);
        if (state.isEmpty()) {
            return Optional.empty();
        }
        if (state.get().cachedInfo() != null) {
            return Optional.of(state.get().cachedInfo());
        }
        Place place = state.get().place();

        long start = System.currentTimeMillis();
        GooglePlacesDetailsResponse details;
        try {
            details = googlePlacesApiClient.getDetails(place.getGooglePlaceId());
            apiCallLogService.record(
                    "google-places", "place-details", null, System.currentTimeMillis() - start,
                    true, null, null, null, null);
        } catch (Exception e) {
            apiCallLogService.record(
                    "google-places", "place-details", null, System.currentTimeMillis() - start,
                    false, e.getMessage(), null, null, null);
            log.warn("Place Details 조회 실패(placeId={}) — 리뷰요약 없이 반환합니다", placeId, e);
            return Optional.of(new PlaceReviewInfo(FETCH_FAILED_SUMMARY, List.of()));
        }

        List<String> reviewTexts = extractReviewTexts(details);
        if (reviewTexts.isEmpty()) {
            // 리뷰가 없다는 사실 자체도 캐시한다 — 안 그러면 이 장소의 상세보기를
            // 누를 때마다(다른 사용자여도) 매번 유료 Details API를 다시 부르게 된다.
            cache(place, NO_REVIEWS_SUMMARY, List.of());
            return Optional.of(new PlaceReviewInfo(NO_REVIEWS_SUMMARY, List.of()));
        }

        List<String> snippets = reviewTexts.stream().limit(MAX_SNIPPETS).toList();
        ReviewSummary summary;
        try {
            // work-dir/로깅 id로 placeId를 그대로 쓰면, 같은 장소를 동시에 두 요청이
            // 상세보기 할 때 ReviewSummaryRunner의 작업 디렉터리가 충돌한다 —
            // PlaceTaggingRunner/RecommendationService와 동일하게 요청마다 고유한
            // System.nanoTime()을 쓴다.
            summary = reviewSummaryRunner.run(reviewTexts, System.nanoTime());
        } catch (Exception e) {
            // Details 호출은 이미 유료로 나갔지만, 여기서 실패하면 아무것도 캐시하지
            // 않아 다음 요청에서 처음부터 다시 시도할 수 있게 한다.
            log.warn("리뷰 요약 생성 실패(placeId={}) — 캐시하지 않고 반환합니다", placeId, e);
            return Optional.of(new PlaceReviewInfo(FETCH_FAILED_SUMMARY, List.of()));
        }

        cache(place, summary, snippets);
        return Optional.of(new PlaceReviewInfo(summary, snippets));
    }

    private void cache(Place place, ReviewSummary summary, List<String> snippets) {
        place.applyReviewSummary(serializeSummary(summary));
        place.applyReviewSnippets(snippets);
        placeRepository.save(place);
    }

    private String serializeSummary(ReviewSummary summary) {
        try {
            return MAPPER.writeValueAsString(summary);
        } catch (Exception e) {
            throw new IllegalStateException("리뷰 요약 직렬화 실패", e);
        }
    }

    private ReviewSummary deserializeSummary(String json) {
        try {
            return MAPPER.readValue(json, ReviewSummary.class);
        } catch (Exception e) {
            log.warn("캐시된 리뷰요약 파싱 실패(예전 포맷으로 추정) — 새로 생성합니다: {}", e.getMessage());
            return null;
        }
    }

    private List<String> extractReviewTexts(GooglePlacesDetailsResponse details) {
        if (details.reviews() == null) {
            return List.of();
        }
        return details.reviews().stream()
                .map(GooglePlacesDetailsResponse.Review::text)
                .filter(text -> text != null && text.text() != null && !text.text().isBlank())
                .map(GooglePlacesDetailsResponse.ReviewText::text)
                .toList();
    }
}
