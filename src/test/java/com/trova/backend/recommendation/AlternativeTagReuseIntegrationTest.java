package com.trova.backend.recommendation;

import com.trova.backend.conversation.ConversationState;
import com.trova.backend.conversation.ConversationToolExecutor;
import com.trova.backend.conversation.GeminiChatClient;
import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.pipeline.PlaceTag;
import com.trova.backend.pipeline.PlaceTaggingRunner;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.TripPlaceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 테스트 자체에 트랜잭션을 열지 않아 요청 사이 엔티티 재사용/dirty checking으로 저장 누락이 가려지지 않게 한다.
@SpringBootTest
class AlternativeTagReuseIntegrationTest {
    @Autowired private AlternativeFinderService finder;
    @Autowired private ConversationToolExecutor executor;
    @Autowired private PlaceRepository places;
    @Autowired private MeterRegistry metrics;
    @MockitoBean private GooglePlacesApiClient google;
    @MockitoBean private TripPlaceRepository tripPlaces;
    @MockitoBean private PlaceTaggingRunner runner;
    @MockitoBean private PlaceEmbeddingService embeddings;
    @MockitoBean private PersonalizationService personalization;

    private User owner;
    private Long placeId;

    @BeforeEach
    void 준비() {
        owner = new User("google", "tag-reuse", "사용자", null);
        ReflectionTestUtils.setField(owner, "id", 901L);
        Trip trip = new Trip(owner, "여행", null, null);
        TripPlace target = new TripPlace(new Itinerary(trip, 1, null), "원본", null, "cafe",
                37.5, 127.0, null, null, 1, PlaceSource.NORMAL, null);
        ReflectionTestUtils.setField(target, "id", 902L);
        when(tripPlaces.findById(902L)).thenReturn(Optional.of(target));
        when(tripPlaces.findByItineraryOrderByVisitOrder(target.getItinerary())).thenReturn(List.of(target));
        when(tripPlaces.findByItinerary_Trip(trip)).thenReturn(List.of(target));
        when(tripPlaces.findByTripOrderByDayAndVisitOrder(trip)).thenReturn(List.of(target));
        when(embeddings.ensureEmbeddingsWithWork(anyList(), any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return new PlaceEmbeddingService.EmbeddingStats(0, 0);
        });
        Place saved = places.save(new Place("tag-reuse-place", "후보 카페", "cafe", 4.5, 100,
                null, 37.501, 127.001, "주소"));
        placeId = saved.getId();
        GooglePlacesNearbySearchResponse.Place raw = new GooglePlacesNearbySearchResponse.Place(
                saved.getGooglePlaceId(), new GooglePlacesNearbySearchResponse.Place.DisplayName(saved.getName()),
                List.of("cafe"), 4.5, 100, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.501, 127.001), "주소");
        when(google.searchNearby(37.5, 127.0, 2000, "cafe"))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(raw)));
        when(runner.run(anyList(), anyLong())).thenReturn(List.of(new PlaceTag(0, "조용한", "INDOOR")));
        when(personalization.personalizationScores(anyLong(), anyList())).thenReturn(Map.of());
        when(personalization.queryScores(any(), anyList())).thenReturn(Map.of());
    }

    @AfterEach
    void 정리() { places.deleteById(placeId); }

    private void 찾기() {
        assertThat(finder.findAlternatives(owner, 902L,
                new AlternativeFilter("카페", true, null, null, null), false).orElseThrow()).hasSize(1);
    }

    @Test
    void 대안_두번_요청하면_DB에서_태그를_읽어_러너는_한번만_부른다() {
        double before = metrics.get("trova.alternatives.space_tagging.calls").counter().count();
        찾기();
        찾기();
        verify(runner, times(1)).run(anyList(), anyLong());
        assertThat(places.findById(placeId).orElseThrow().getSpace()).isEqualTo("INDOOR");
        assertThat(metrics.get("trova.alternatives.space_tagging.calls").counter().count() - before).isEqualTo(1);
    }

    @Test
    void 대화_도구도_다음_요청에_저장된_태그를_재사용한다() {
        ConversationState state = new ConversationState(owner.getId(), 1L, 902L, null, null);
        GeminiChatClient.FunctionCall call = new GeminiChatClient.FunctionCall(
                "find_alternatives", Map.of("category", "카페", "indoor", true), null);
        assertThat(executor.execute(owner, state, call, "실내로 바꿔줘").candidates()).hasSize(1);
        assertThat(executor.execute(owner, state, call, "다른 실내 장소").candidates()).hasSize(1);
        verify(runner, times(1)).run(anyList(), anyLong());
    }

    @Test
    void 삼십일_이내의_실외_태그도_재사용한다() {
        태그저장("OUTDOOR", LocalDateTime.now().minusDays(29));
        assertThat(finder.findAlternatives(owner, 902L,
                new AlternativeFilter("카페", true, null, null, null), false).orElseThrow()).isEmpty();
        verify(runner, times(0)).run(anyList(), anyLong());
    }

    @Test
    void 삼십일이_지난_태그는_갱신하고_다음_요청에_재사용한다() {
        태그저장("OUTDOOR", LocalDateTime.now().minusDays(30));
        찾기();
        찾기();
        verify(runner, times(1)).run(anyList(), anyLong());
    }

    @Test
    void 시각이_없는_기존_태그는_다시_태깅한다() {
        Place saved = places.findById(placeId).orElseThrow();
        saved.applySpaceTag("OUTDOOR");
        ReflectionTestUtils.setField(saved, "spaceTaggedAt", null);
        places.save(saved);
        찾기();
        verify(runner, times(1)).run(anyList(), anyLong());
    }

    @Test
    void 태깅_실패는_저장하지_않고_다음_요청에서_재시도한다() {
        double before = metrics.get("trova.alternatives.space_tagging.calls").counter().count();
        when(runner.run(anyList(), anyLong())).thenThrow(new RuntimeException("429"))
                .thenReturn(List.of(new PlaceTag(0, "조용한", "INDOOR")));
        assertThat(finder.findAlternatives(owner, 902L,
                new AlternativeFilter("카페", true, null, null, null), false).orElseThrow()).isEmpty();
        assertThat(places.findById(placeId).orElseThrow().getSpace()).isNull();
        assertThat(metrics.get("trova.alternatives.space_tagging.calls").counter().count() - before).isEqualTo(1);
        찾기();
        verify(runner, times(2)).run(anyList(), anyLong());
        assertThat(metrics.get("trova.alternatives.space_tagging.calls").counter().count() - before).isEqualTo(2);
    }

    @Test
    void 새_후보도_카탈로그_삽입_뒤_태그가_저장된다() {
        places.deleteById(placeId);
        try {
            찾기();
            Place saved = places.findByGooglePlaceId("tag-reuse-place").orElseThrow();
            placeId = saved.getId();
            assertThat(saved.getSpace()).isEqualTo("INDOOR");
            assertThat(saved.getSpaceTaggedAt()).isNotNull();
            찾기();
            verify(runner, times(1)).run(anyList(), anyLong());
        } finally {
            places.findByGooglePlaceId("tag-reuse-place").ifPresent(saved -> placeId = saved.getId());
        }
    }

    @Test
    void 누락되거나_빈_태그는_저장하지_않는다() {
        when(runner.run(anyList(), anyLong())).thenReturn(List.of())
                .thenReturn(List.of(new PlaceTag(0, "조용한", "")))
                .thenReturn(List.of(new PlaceTag(0, "조용한", "INDOOR")));
        AlternativeFilter filter = new AlternativeFilter("카페", true, null, null, null);
        assertThat(finder.findAlternatives(owner, 902L, filter, false).orElseThrow()).isEmpty();
        assertThat(finder.findAlternatives(owner, 902L, filter, false).orElseThrow()).isEmpty();
        Place saved = places.findById(placeId).orElseThrow();
        assertThat(saved.getSpace()).isNull();
        assertThat(saved.getSpaceTaggedAt()).isNull();
        찾기();
        verify(runner, times(3)).run(anyList(), anyLong());
    }

    @Test
    void 만료된_태그의_재태깅_실패는_기존_시각을_연장하지_않는다() {
        LocalDateTime old = LocalDateTime.now().minusDays(31);
        태그저장("OUTDOOR", old);
        when(runner.run(anyList(), anyLong())).thenThrow(new RuntimeException("429"))
                .thenReturn(List.of(new PlaceTag(0, "조용한", "INDOOR")));
        assertThat(finder.findAlternatives(owner, 902L,
                new AlternativeFilter("카페", true, null, null, null), false).orElseThrow()).isEmpty();
        assertThat(places.findById(placeId).orElseThrow().getSpaceTaggedAt()).isEqualToIgnoringNanos(old);
        찾기();
        verify(runner, times(2)).run(anyList(), anyLong());
    }

    private void 태그저장(String space, LocalDateTime at) {
        Place saved = places.findById(placeId).orElseThrow();
        saved.applySpaceTag(space);
        ReflectionTestUtils.setField(saved, "spaceTaggedAt", at);
        places.save(saved);
    }
}
