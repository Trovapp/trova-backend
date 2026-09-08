package com.trova.backend.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.Place;
import com.trova.backend.pipeline.ReviewSummary;
import com.trova.backend.repository.PlaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
class PlaceReviewServiceIntegrationTest {

    @Autowired
    private PlaceReviewService placeReviewService;

    @Autowired
    private PlaceRepository placeRepository;

    @MockitoBean
    private GooglePlacesApiClient googlePlacesApiClient;

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
}
