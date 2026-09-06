package com.trova.backend.recommendation;

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
 * 장소 리뷰요약+원문 스니펫(최대 3개)을 캐시-우선으로 제공한다. Place Details API는
 * 유료 티어라, 캐시가 있으면 절대 다시 부르지 않는다(영구 캐시 — 갱신 정책 없음).
 */
@Service
public class PlaceReviewService {

    private static final Logger log = LoggerFactory.getLogger(PlaceReviewService.class);
    private static final String NO_REVIEWS_MESSAGE = "리뷰 정보 없음";
    private static final String FETCH_FAILED_MESSAGE = "리뷰를 불러오지 못했어요";
    private static final int MAX_SNIPPETS = 3;

    private final PlaceRepository placeRepository;
    private final GooglePlacesApiClient googlePlacesApiClient;
    private final ReviewSummaryRunner reviewSummaryRunner;
    private final ApiCallLogService apiCallLogService;

    public record PlaceReviewInfo(String summary, List<String> snippets) {
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
     * 읽으면 LazyInitializationException이 난다. 처음엔 이 메서드에 자체
     * @Transactional(readOnly=true)을 붙여 짧은 읽기 전용 트랜잭션을 열려 했지만,
     * getOrGenerateSummary가 같은 빈 안에서 this.loadPlaceState(...)로 호출하는
     * self-invocation이라 Spring 프록시(AOP)를 거치지 않아 트랜잭션이 전혀 시작되지
     * 않는다는 게 PlaceReviewServiceIntegrationTest로 실제 검증됐다(수정 전 코드로
     * 돌려서 LazyInitializationException 재현 확인). 그래서 실제 방어는 여기가 아니라
     * PlaceRepository.findById()에 붙인 @EntityGraph(attributePaths = "reviewSnippets")
     * 쪽에서 한다 — findById 쿼리 시점에 reviewSnippets까지 JOIN FETCH로 즉시
     * 초기화되므로, 이후 트랜잭션이 끝나고 엔티티가 detach돼도 이미 메모리에 로드된
     * 컬렉션이라 안전하게 읽힌다(self-invocation 문제 자체가 발생하지 않는 방식).
     * 이 메서드는 그 결과를 List.copyOf(...)로 한 번 더 방어적으로 복사해서 반환할
     * 뿐이다. Google Places/Gemini 같은 외부 호출은 이 메서드에도, getOrGenerateSummary
     * 어디에도 트랜잭션으로 감싸지 않는다(paid API 호출 + 2분 타임아웃 서브프로세스를
     * 트랜잭션으로 감싸면 더 위험하다).
     */
    private Optional<PlaceState> loadPlaceState(Long placeId) {
        return placeRepository.findById(placeId).map(place -> {
            PlaceReviewInfo cached = place.getReviewSummary() != null
                    ? new PlaceReviewInfo(place.getReviewSummary(), List.copyOf(place.getReviewSnippets()))
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
            return Optional.of(new PlaceReviewInfo(FETCH_FAILED_MESSAGE, List.of()));
        }

        List<String> reviewTexts = extractReviewTexts(details);
        if (reviewTexts.isEmpty()) {
            // 리뷰가 없다는 사실 자체도 캐시한다 — 안 그러면 이 장소의 상세보기를
            // 누를 때마다(다른 사용자여도) 매번 유료 Details API를 다시 부르게 된다.
            place.applyReviewSummary(NO_REVIEWS_MESSAGE);
            place.applyReviewSnippets(List.of());
            placeRepository.save(place);
            return Optional.of(new PlaceReviewInfo(NO_REVIEWS_MESSAGE, List.of()));
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
            return Optional.of(new PlaceReviewInfo(FETCH_FAILED_MESSAGE, List.of()));
        }

        place.applyReviewSummary(summary.summary());
        place.applyReviewSnippets(snippets);
        placeRepository.save(place);
        return Optional.of(new PlaceReviewInfo(summary.summary(), snippets));
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
