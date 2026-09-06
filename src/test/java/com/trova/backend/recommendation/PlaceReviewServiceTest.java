package com.trova.backend.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.Place;
import com.trova.backend.pipeline.ReviewSummary;
import com.trova.backend.pipeline.ReviewSummaryRunner;
import com.trova.backend.recommendation.PlaceReviewService.PlaceReviewInfo;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    private String toJson(ReviewSummary summary) throws Exception {
        return MAPPER.writeValueAsString(summary);
    }

    @Test
    void 캐시된_요약이_있으면_API를_호출하지_않고_그대로_반환한다() throws Exception {
        Place place = newPlace();
        ReviewSummary cached = new ReviewSummary(
                "이미 있는 요약", List.of("좋은 점"), List.of(), "9시~18시", "무료",
                List.of("일찍 가세요"), List.of("주차 확인"));
        place.applyReviewSummary(toJson(cached));
        place.applyReviewSnippets(List.of("좋아요", "친절해요"));
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));

        Optional<PlaceReviewInfo> result = placeReviewService.getOrGenerateSummary(1L);

        assertThat(result).isPresent();
        assertThat(result.get().summary()).isEqualTo(cached);
        assertThat(result.get().snippets()).containsExactly("좋아요", "친절해요");
        verify(googlePlacesApiClient, never()).getDetails(any());
        verify(reviewSummaryRunner, never()).run(any(), anyLong());
    }

    @Test
    void 캐시가_없고_리뷰가_있으면_Gemini로_요약해서_캐시에_저장한다() throws Exception {
        Place place = newPlace();
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(googlePlacesApiClient.getDetails("g1")).thenReturn(new GooglePlacesDetailsResponse(
                "g1", List.of(
                        new GooglePlacesDetailsResponse.Review(new GooglePlacesDetailsResponse.ReviewText("좋아요")),
                        new GooglePlacesDetailsResponse.Review(new GooglePlacesDetailsResponse.ReviewText("친절해요"))
                )));
        ReviewSummary generated = new ReviewSummary(
                "전반적으로 만족도가 높은 곳이에요.", List.of("친절함"), List.of(), null, null, List.of(), List.of());
        when(reviewSummaryRunner.run(eq(List.of("좋아요", "친절해요")), anyLong())).thenReturn(generated);

        Optional<PlaceReviewInfo> result = placeReviewService.getOrGenerateSummary(1L);

        assertThat(result).isPresent();
        assertThat(result.get().summary()).isEqualTo(generated);
        assertThat(result.get().snippets()).containsExactly("좋아요", "친절해요");
        assertThat(place.getReviewSnippets()).containsExactly("좋아요", "친절해요");
        assertThat(MAPPER.readValue(place.getReviewSummary(), ReviewSummary.class)).isEqualTo(generated);
        verify(placeRepository).save(place);
    }

    @Test
    void 리뷰가_3개_초과면_최대_3개까지만_스니펫으로_저장한다() {
        Place place = newPlace();
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(googlePlacesApiClient.getDetails("g1")).thenReturn(new GooglePlacesDetailsResponse(
                "g1", List.of(
                        new GooglePlacesDetailsResponse.Review(new GooglePlacesDetailsResponse.ReviewText("리뷰1")),
                        new GooglePlacesDetailsResponse.Review(new GooglePlacesDetailsResponse.ReviewText("리뷰2")),
                        new GooglePlacesDetailsResponse.Review(new GooglePlacesDetailsResponse.ReviewText("리뷰3")),
                        new GooglePlacesDetailsResponse.Review(new GooglePlacesDetailsResponse.ReviewText("리뷰4"))
                )));
        when(reviewSummaryRunner.run(eq(List.of("리뷰1", "리뷰2", "리뷰3", "리뷰4")), anyLong()))
                .thenReturn(new ReviewSummary("요약", List.of(), List.of(), null, null, List.of(), List.of()));

        Optional<PlaceReviewInfo> result = placeReviewService.getOrGenerateSummary(1L);

        assertThat(result.get().snippets()).containsExactly("리뷰1", "리뷰2", "리뷰3");
    }

    @Test
    void 리뷰가_없으면_Gemini를_호출하지_않고_고정_요약을_반환한다() throws Exception {
        Place place = newPlace();
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(googlePlacesApiClient.getDetails("g1")).thenReturn(new GooglePlacesDetailsResponse("g1", List.of()));

        Optional<PlaceReviewInfo> result = placeReviewService.getOrGenerateSummary(1L);

        assertThat(result.get().summary().highlights()).isEqualTo("리뷰 정보가 없어요.");
        assertThat(result.get().snippets()).isEmpty();
        verify(reviewSummaryRunner, never()).run(any(), anyLong());
        // 리뷰가 없다는 사실 자체도 캐시해야, 이후 요청에서 유료 Details API를
        // 다시 부르지 않는다 — 최종 리뷰에서 지적된 캐시 미스 버그 수정.
        verify(placeRepository).save(place);
        assertThat(MAPPER.readValue(place.getReviewSummary(), ReviewSummary.class).highlights())
                .isEqualTo("리뷰 정보가 없어요.");
    }

    @Test
    void Details_API_실패하면_캐시하지_않고_실패_요약을_반환한다() {
        Place place = newPlace();
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(googlePlacesApiClient.getDetails("g1")).thenThrow(new RuntimeException("timeout"));

        Optional<PlaceReviewInfo> result = placeReviewService.getOrGenerateSummary(1L);

        assertThat(result.get().summary().highlights()).isEqualTo("리뷰를 불러오지 못했어요.");
        assertThat(place.getReviewSummary()).isNull();
        verify(placeRepository, never()).save(any());
    }

    @Test
    void Gemini_요약_생성이_실패하면_캐시하지_않고_실패_요약을_반환한다() {
        Place place = newPlace();
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(googlePlacesApiClient.getDetails("g1")).thenReturn(new GooglePlacesDetailsResponse(
                "g1", List.of(new GooglePlacesDetailsResponse.Review(
                        new GooglePlacesDetailsResponse.ReviewText("좋아요")))));
        when(reviewSummaryRunner.run(any(), anyLong())).thenThrow(new RuntimeException("gemini 실패"));

        Optional<PlaceReviewInfo> result = placeReviewService.getOrGenerateSummary(1L);

        assertThat(result.get().summary().highlights()).isEqualTo("리뷰를 불러오지 못했어요.");
        assertThat(place.getReviewSummary()).isNull();
        verify(placeRepository, never()).save(any());
    }

    @Test
    void 예전_포맷의_캐시는_역직렬화_실패로_캐시_미스_처리되어_새로_생성한다() {
        Place place = newPlace();
        place.applyReviewSummary("예전 포맷: 그냥 평문 문장입니다");
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(googlePlacesApiClient.getDetails("g1")).thenReturn(new GooglePlacesDetailsResponse(
                "g1", List.of(new GooglePlacesDetailsResponse.Review(
                        new GooglePlacesDetailsResponse.ReviewText("좋아요")))));
        ReviewSummary generated = new ReviewSummary("새로 생성됨", List.of(), List.of(), null, null, List.of(), List.of());
        when(reviewSummaryRunner.run(any(), anyLong())).thenReturn(generated);

        Optional<PlaceReviewInfo> result = placeReviewService.getOrGenerateSummary(1L);

        assertThat(result.get().summary()).isEqualTo(generated);
        verify(googlePlacesApiClient).getDetails("g1");
    }

    @Test
    void 존재하지_않는_장소면_빈_Optional을_반환한다() {
        when(placeRepository.findById(999L)).thenReturn(Optional.empty());

        Optional<PlaceReviewInfo> result = placeReviewService.getOrGenerateSummary(999L);

        assertThat(result).isEmpty();
    }
}
