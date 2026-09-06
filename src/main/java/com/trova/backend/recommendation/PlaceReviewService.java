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
 * 장소 리뷰요약을 캐시-우선으로 제공한다. Place Details API는 유료 티어라, 캐시가
 * 있으면 절대 다시 부르지 않는다(영구 캐시 — 갱신 정책 없음, 스펙 참고).
 */
@Service
public class PlaceReviewService {

    private static final Logger log = LoggerFactory.getLogger(PlaceReviewService.class);
    private static final String NO_REVIEWS_MESSAGE = "리뷰 정보 없음";
    private static final String FETCH_FAILED_MESSAGE = "리뷰를 불러오지 못했어요";

    private final PlaceRepository placeRepository;
    private final GooglePlacesApiClient googlePlacesApiClient;
    private final ReviewSummaryRunner reviewSummaryRunner;
    private final ApiCallLogService apiCallLogService;

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

    public Optional<String> getOrGenerateSummary(Long placeId) {
        Optional<Place> maybePlace = placeRepository.findById(placeId);
        if (maybePlace.isEmpty()) {
            return Optional.empty();
        }
        Place place = maybePlace.get();
        if (place.getReviewSummary() != null) {
            return Optional.of(place.getReviewSummary());
        }

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
            return Optional.of(FETCH_FAILED_MESSAGE);
        }

        List<String> reviewTexts = extractReviewTexts(details);
        if (reviewTexts.isEmpty()) {
            return Optional.of(NO_REVIEWS_MESSAGE);
        }

        ReviewSummary summary = reviewSummaryRunner.run(reviewTexts, placeId);
        place.applyReviewSummary(summary.summary());
        placeRepository.save(place);
        return Optional.of(summary.summary());
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
