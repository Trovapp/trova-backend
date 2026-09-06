package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import com.trova.backend.pipeline.ReviewSummary;
import com.trova.backend.pipeline.ReviewSummaryRunner;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaceReviewServiceTest {

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private GooglePlacesApiClient googlePlacesApiClient;

    @Mock
    private ReviewSummaryRunner reviewSummaryRunner;

    @Mock
    private ApiCallLogService apiCallLogService;

    @InjectMocks
    private PlaceReviewService placeReviewService;

    private Place newPlace() {
        return new Place("g1", "카페A", "cafe", 4.5, 100, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "서울 어딘가");
    }

    @Test
    void 캐시된_요약이_있으면_API를_호출하지_않고_그대로_반환한다() {
        Place place = newPlace();
        place.applyReviewSummary("이미 있는 요약");
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));

        Optional<String> result = placeReviewService.getOrGenerateSummary(1L);

        assertThat(result).contains("이미 있는 요약");
        verify(googlePlacesApiClient, never()).getDetails(any());
        verify(reviewSummaryRunner, never()).run(any(), anyLong());
    }

    @Test
    void 캐시가_없고_리뷰가_있으면_Gemini로_요약해서_캐시에_저장한다() {
        Place place = newPlace();
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(googlePlacesApiClient.getDetails("g1")).thenReturn(new GooglePlacesDetailsResponse(
                "g1", List.of(
                        new GooglePlacesDetailsResponse.Review(new GooglePlacesDetailsResponse.ReviewText("좋아요")),
                        new GooglePlacesDetailsResponse.Review(new GooglePlacesDetailsResponse.ReviewText("친절해요"))
                )));
        when(reviewSummaryRunner.run(List.of("좋아요", "친절해요"), 1L))
                .thenReturn(new ReviewSummary("전반적으로 만족도가 높은 곳이에요."));

        Optional<String> result = placeReviewService.getOrGenerateSummary(1L);

        assertThat(result).contains("전반적으로 만족도가 높은 곳이에요.");
        assertThat(place.getReviewSummary()).isEqualTo("전반적으로 만족도가 높은 곳이에요.");
        verify(placeRepository).save(place);
    }

    @Test
    void 리뷰가_없으면_Gemini를_호출하지_않고_고정_문구를_반환한다() {
        Place place = newPlace();
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(googlePlacesApiClient.getDetails("g1")).thenReturn(new GooglePlacesDetailsResponse("g1", List.of()));

        Optional<String> result = placeReviewService.getOrGenerateSummary(1L);

        assertThat(result).contains("리뷰 정보 없음");
        verify(reviewSummaryRunner, never()).run(any(), anyLong());
        verify(placeRepository, never()).save(any());
    }

    @Test
    void Details_API_실패하면_캐시하지_않고_실패_문구를_반환한다() {
        Place place = newPlace();
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(googlePlacesApiClient.getDetails("g1")).thenThrow(new RuntimeException("timeout"));

        Optional<String> result = placeReviewService.getOrGenerateSummary(1L);

        assertThat(result).contains("리뷰를 불러오지 못했어요");
        assertThat(place.getReviewSummary()).isNull();
        verify(placeRepository, never()).save(any());
    }

    @Test
    void 존재하지_않는_장소면_빈_Optional을_반환한다() {
        when(placeRepository.findById(999L)).thenReturn(Optional.empty());

        Optional<String> result = placeReviewService.getOrGenerateSummary(999L);

        assertThat(result).isEmpty();
    }
}
