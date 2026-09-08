package com.trova.backend.recommendation;

import com.trova.backend.entity.*;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GapRecommendationServiceTest {

    @Mock private TripRepository tripRepository;
    @Mock private ItineraryRepository itineraryRepository;
    @Mock private TripPlaceRepository tripPlaceRepository;
    @Mock private GooglePlacesApiClient googlePlacesApiClient;
    @Mock private PlaceCatalogService placeCatalogService;
    @InjectMocks private GapRecommendationService gapRecommendationService;

    @Test
    void 시간이_없는_장소_쌍은_gap으로_잡지_않는다() {
        User user = new User("google", "gap1", "갭유저1", null);
        Trip trip = new Trip(user, "여행", null, null);
        Itinerary itinerary = new Itinerary(trip, 1, null);
        TripPlace a = new TripPlace(itinerary, "A", null, "cafe", 37.5, 127.0, null, null, 1, PlaceSource.NORMAL, null);
        TripPlace b = new TripPlace(itinerary, "B", null, "cafe", 37.6, 127.1, null, null, 2, PlaceSource.NORMAL, null);
        // 시간 미입력 — visitStartTime/visitEndTime 둘 다 null

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(itineraryRepository.findByTripAndDay(trip, 1)).thenReturn(Optional.of(itinerary));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary)).thenReturn(List.of(a, b));

        Optional<List<GapRecommendationService.Gap>> result = gapRecommendationService.findGaps(user, 1L, 1);

        assertThat(result).isPresent();
        assertThat(result.get()).isEmpty();
    }

    @Test
    void _30분_넘게_비면_gap으로_잡고_중간지점을_검색한다() {
        User user = new User("google", "gap2", "갭유저2", null);
        Trip trip = new Trip(user, "여행", null, null);
        Itinerary itinerary = new Itinerary(trip, 1, null);
        TripPlace a = new TripPlace(itinerary, "A", null, "cafe", 37.500, 127.000, null, null, 1, PlaceSource.NORMAL, null);
        TripPlace b = new TripPlace(itinerary, "B", null, "cafe", 37.510, 127.000, null, null, 2, PlaceSource.NORMAL, null);
        a.applyDetails(null, LocalTime.of(10, 0), null, null);
        b.applyDetails(LocalTime.of(11, 0), null, null, null);

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(itineraryRepository.findByTripAndDay(trip, 1)).thenReturn(Optional.of(itinerary));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary)).thenReturn(List.of(a, b));
        when(googlePlacesApiClient.searchNearby(37.505, 127.000, 1500, null))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of()));

        Optional<List<GapRecommendationService.Gap>> result = gapRecommendationService.findGaps(user, 1L, 1);

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0).gapMinutes()).isEqualTo(60);
    }

    @Test
    void _30분_이하로_비면_gap으로_안_잡는다() {
        User user = new User("google", "gap3", "갭유저3", null);
        Trip trip = new Trip(user, "여행", null, null);
        Itinerary itinerary = new Itinerary(trip, 1, null);
        TripPlace a = new TripPlace(itinerary, "A", null, "cafe", 37.500, 127.000, null, null, 1, PlaceSource.NORMAL, null);
        TripPlace b = new TripPlace(itinerary, "B", null, "cafe", 37.510, 127.000, null, null, 2, PlaceSource.NORMAL, null);
        a.applyDetails(null, LocalTime.of(10, 0), null, null);
        b.applyDetails(LocalTime.of(10, 20), null, null, null);

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(itineraryRepository.findByTripAndDay(trip, 1)).thenReturn(Optional.of(itinerary));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary)).thenReturn(List.of(a, b));

        Optional<List<GapRecommendationService.Gap>> result = gapRecommendationService.findGaps(user, 1L, 1);

        assertThat(result).isPresent();
        assertThat(result.get()).isEmpty();
    }
}
