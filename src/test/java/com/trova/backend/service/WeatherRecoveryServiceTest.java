package com.trova.backend.service;

import com.trova.backend.entity.*;
import com.trova.backend.geocoding.KakaoKeywordSearchResponse;
import com.trova.backend.geocoding.KakaoLocalApiClient;
import com.trova.backend.pipeline.PlaceTag;
import com.trova.backend.pipeline.PlaceTaggingRunner;
import com.trova.backend.repository.NotificationRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.weather.OpenWeatherApiClient;
import com.trova.backend.weather.OpenWeatherForecastResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WeatherRecoveryServiceTest {

    @Mock private TripPlaceRepository tripPlaceRepository;
    @Mock private PlaceTaggingRunner placeTaggingRunner;
    @Mock private OpenWeatherApiClient openWeatherApiClient;
    @Mock private KakaoLocalApiClient kakaoLocalApiClient;
    @Mock private NotificationRepository notificationRepository;

    private WeatherRecoveryService service;

    private final User user = new User("google", "weather-test", "테스트유저", null);
    private final Trip trip = new Trip(user, "여행", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1));
    private final Itinerary itinerary = new Itinerary(trip, 1, LocalDate.of(2026, 10, 1));

    private TripPlace outdoorPlace(String space) {
        TripPlace p = new TripPlace(itinerary, "해운대", "부산", "beach", 35.15, 129.16, null, "부산 해운대", 1, PlaceSource.VIDEO, null);
        if (space != null) {
            p.applySpace(space);
        }
        return p;
    }

    private void setUp() {
        service = new WeatherRecoveryService(
                tripPlaceRepository, placeTaggingRunner, openWeatherApiClient, kakaoLocalApiClient, notificationRepository);
    }

    @Test
    void 실외_장소가_있고_강수확률이_높으면_알림을_생성한다() {
        setUp();
        TripPlace place = outdoorPlace("OUTDOOR");
        when(notificationRepository.findByItinerary(itinerary)).thenReturn(Optional.empty());
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary)).thenReturn(List.of(place));
        when(openWeatherApiClient.forecast(35.15, 129.16)).thenReturn(new OpenWeatherForecastResponse(List.of(
                new OpenWeatherForecastResponse.Entry(0L, "2026-10-01 12:00:00", 0.8)
        )));
        when(kakaoLocalApiClient.searchKeyword("부산 실내 명소")).thenReturn(new KakaoKeywordSearchResponse(List.of(
                new KakaoKeywordSearchResponse.Document("부산 아쿠아리움", "129.15", "35.15", null, "부산 해운대", null, null, null)
        )));
        when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<Notification> result = service.checkAndNotify(itinerary);

        assertThat(result).isPresent();
        assertThat(result.get().getPrecipitationProb()).isEqualTo(0.8);
        assertThat(result.get().getAlternatives()).hasSize(1);
        assertThat(result.get().getAlternatives().get(0).getName()).isEqualTo("부산 아쿠아리움");
        verify(placeTaggingRunner, never()).run(any(), anyLong());
    }

    @Test
    void 태그가_없으면_먼저_태깅하고_판단한다() {
        setUp();
        TripPlace untagged = outdoorPlace(null);
        when(notificationRepository.findByItinerary(itinerary)).thenReturn(Optional.empty());
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary)).thenReturn(List.of(untagged));
        when(placeTaggingRunner.run(any(), anyLong())).thenReturn(List.of(new PlaceTag(0, "CALM", "OUTDOOR")));
        when(openWeatherApiClient.forecast(35.15, 129.16)).thenReturn(new OpenWeatherForecastResponse(List.of(
                new OpenWeatherForecastResponse.Entry(0L, "2026-10-01 12:00:00", 0.9)
        )));
        when(kakaoLocalApiClient.searchKeyword(any())).thenReturn(new KakaoKeywordSearchResponse(List.of()));
        when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<Notification> result = service.checkAndNotify(itinerary);

        assertThat(result).isPresent();
        assertThat(untagged.getSpace()).isEqualTo("OUTDOOR");
        verify(tripPlaceRepository).saveAll(List.of(untagged));
    }

    @Test
    void 실외_장소가_없으면_알림을_만들지_않는다() {
        setUp();
        TripPlace indoor = outdoorPlace("INDOOR");
        when(notificationRepository.findByItinerary(itinerary)).thenReturn(Optional.empty());
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary)).thenReturn(List.of(indoor));

        Optional<Notification> result = service.checkAndNotify(itinerary);

        assertThat(result).isEmpty();
        verify(openWeatherApiClient, never()).forecast(anyDouble(), anyDouble());
    }

    @Test
    void 강수확률이_낮으면_알림을_만들지_않는다() {
        setUp();
        TripPlace place = outdoorPlace("OUTDOOR");
        when(notificationRepository.findByItinerary(itinerary)).thenReturn(Optional.empty());
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary)).thenReturn(List.of(place));
        when(openWeatherApiClient.forecast(35.15, 129.16)).thenReturn(new OpenWeatherForecastResponse(List.of(
                new OpenWeatherForecastResponse.Entry(0L, "2026-10-01 12:00:00", 0.1)
        )));

        Optional<Notification> result = service.checkAndNotify(itinerary);

        assertThat(result).isEmpty();
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void 이미_알림이_있으면_다시_만들지_않는다() {
        setUp();
        Notification existing = new Notification(user, itinerary, "t", "b", 0.9, List.of());
        when(notificationRepository.findByItinerary(itinerary)).thenReturn(Optional.of(existing));

        Optional<Notification> result = service.checkAndNotify(itinerary);

        assertThat(result).isEmpty();
        verify(tripPlaceRepository, never()).findByItineraryOrderByVisitOrder(any());
    }

    @Test
    void 날짜가_없는_Itinerary는_바로_스킵한다() {
        setUp();
        Itinerary noDate = new Itinerary(trip, 1, null);

        Optional<Notification> result = service.checkAndNotify(noDate);

        assertThat(result).isEmpty();
        verifyNoInteractions(notificationRepository, tripPlaceRepository, openWeatherApiClient);
    }
}
