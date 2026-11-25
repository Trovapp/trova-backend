package com.trova.backend.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.Place;
import com.trova.backend.pipeline.ReviewSummary;
import com.trova.backend.pipeline.ReviewSummaryRunner;
import com.trova.backend.repository.PlaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class PlaceReviewServiceIntegrationTest {

    @Autowired
    private PlaceReviewService placeReviewService;

    @Autowired
    private PlaceRepository placeRepository;

    @MockitoBean
    private GooglePlacesApiClient googlePlacesApiClient;

    @MockitoBean
    private ReviewSummaryRunner reviewSummaryRunner;

    private Long placeId;

    @AfterEach
    void tearDown() {
        if (placeId != null) {
            placeRepository.deleteById(placeId);
        }
    }

    @Test
    void 캐시된_리뷰요약과_스니펫은_트랜잭션_밖에서도_지연로딩_예외없이_읽힌다() throws Exception {
        Place place = placeRepository.save(new Place(
                "place-review-integration-1", "테스트 장소", "cafe", 4.5, 100,
                null, 37.5, 127.0, "주소"));
        ReviewSummary cached = new ReviewSummary(
                "캐시된 요약", List.of("좋은 점"), List.of(), null, null, List.of(), List.of());
        place.applyReviewSummary(new ObjectMapper().writeValueAsString(cached));
        place.applyReviewSnippets(List.of("좋아요", "친절해요"));
        placeRepository.save(place);
        placeId = place.getId();

        Optional<PlaceReviewService.PlaceReviewInfo> result = placeReviewService.getOrGenerateSummary(placeId);

        assertThat(result).isPresent();
        assertThat(result.get().summary()).isEqualTo(cached);
        assertThat(result.get().snippets()).containsExactly("좋아요", "친절해요");
        verify(googlePlacesApiClient, never()).getDetails(any());
    }
    @Test
    void 상세_로드_뒤_저장된_태그를_리뷰_캐시가_덮어쓰지_않는다() {
        Place place = placeRepository.save(new Place(
                "review-tag-race", "장소", "cafe", 4.5, 10, null, 37.5, 127.0, "주소"));
        placeId = place.getId();
        LocalDateTime taggedAt = LocalDateTime.now().minusDays(1).withNano(0);
        when(googlePlacesApiClient.getDetails(place.getGooglePlaceId())).thenAnswer(call -> {
            placeRepository.updateSpaceTag(placeId, "INDOOR", taggedAt);
            return new GooglePlacesDetailsResponse(place.getGooglePlaceId(), List.of(), "02-123");
        });

        assertThat(placeReviewService.getOrGenerateSummary(placeId).orElseThrow().phone()).isEqualTo("02-123");

        Place saved = placeRepository.findById(placeId).orElseThrow();
        assertThat(saved.getSpace()).isEqualTo("INDOOR");
        assertThat(saved.getSpaceTaggedAt()).isEqualTo(taggedAt);
        assertThat(saved.getReviewSummary()).contains("리뷰 정보가 없어요.");
        assertThat(saved.getPhone()).isEqualTo("02-123");
        assertThat(saved.getPhoneCheckedAt()).isNotNull();
    }

    @Test
    void 상세_로드_뒤_저장된_태그를_전화_보충이_덮어쓰지_않는다() throws Exception {
        Place place = new Place("phone-tag-race", "장소", "cafe", 4.5, 10, null, 37.5, 127.0, "주소");
        place.applyReviewSummary(new ObjectMapper().writeValueAsString(new ReviewSummary(
                "기존 요약", List.of(), List.of(), null, null, List.of(), List.of())));
        place.applyReviewSnippets(List.of("기존 스니펫"));
        placeId = placeRepository.save(place).getId();
        LocalDateTime taggedAt = LocalDateTime.now().minusDays(1).withNano(0);
        when(googlePlacesApiClient.getPhone(place.getGooglePlaceId())).thenAnswer(call -> {
            placeRepository.updateSpaceTag(placeId, "OUTDOOR", taggedAt);
            return new GooglePlacesDetailsResponse(place.getGooglePlaceId(), List.of(), "02-456");
        });

        assertThat(placeReviewService.getOrGenerateSummary(placeId).orElseThrow().phone()).isEqualTo("02-456");

        Place saved = placeRepository.findById(placeId).orElseThrow();
        assertThat(saved.getSpace()).isEqualTo("OUTDOOR");
        assertThat(saved.getSpaceTaggedAt()).isEqualTo(taggedAt);
        assertThat(saved.getReviewSummary()).contains("기존 요약");
        assertThat(saved.getReviewSnippets()).containsExactly("기존 스니펫");
        assertThat(saved.getPhone()).isEqualTo("02-456");
    }

    @Test
    void 리뷰_부분_갱신은_기존_스니펫을_순서대로_교체하고_다음_요청에_재사용한다() {
        Place place = new Place("review-snippet-update", "장소", "cafe", 4.5, 10, null, 37.5, 127.0, "주소");
        place.applyReviewSummary("예전 평문 요약");
        place.applyReviewSnippets(List.of("예전 스니펫1", "예전 스니펫2", "예전 스니펫3"));
        placeId = placeRepository.save(place).getId();
        LocalDateTime taggedAt = LocalDateTime.now().minusDays(1).withNano(0);
        when(googlePlacesApiClient.getDetails(place.getGooglePlaceId())).thenAnswer(call -> {
            placeRepository.updateSpaceTag(placeId, "MIXED", taggedAt);
            return new GooglePlacesDetailsResponse(place.getGooglePlaceId(), List.of(
                    new GooglePlacesDetailsResponse.Review(new GooglePlacesDetailsResponse.ReviewText("새 스니펫1")),
                    new GooglePlacesDetailsResponse.Review(new GooglePlacesDetailsResponse.ReviewText("새 스니펫2"))), "02-789");
        });
        ReviewSummary summary = new ReviewSummary("새 요약", List.of(), List.of(), null, null, List.of(), List.of());
        when(reviewSummaryRunner.run(any(), anyLong())).thenReturn(summary);

        placeReviewService.getOrGenerateSummary(placeId).orElseThrow();
        Place saved = placeRepository.findById(placeId).orElseThrow();
        assertThat(saved.getReviewSnippets()).containsExactly("새 스니펫1", "새 스니펫2");
        assertThat(saved.getReviewSummaryGeneratedAt()).isNotNull();
        assertThat(saved.getSpace()).isEqualTo("MIXED");
        assertThat(saved.getSpaceTaggedAt()).isEqualTo(taggedAt);
        assertThat(placeReviewService.getOrGenerateSummary(placeId).orElseThrow().summary()).isEqualTo(summary);
        verify(googlePlacesApiClient).getDetails(place.getGooglePlaceId());
    }

}
