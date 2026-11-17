package com.trova.backend.service;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.TransportMode;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripServiceCopyTest {

    @Mock
    private TripRepository tripRepository;

    @Mock
    private ItineraryRepository itineraryRepository;

    @Mock
    private TripPlaceRepository tripPlaceRepository;

    @InjectMocks
    private TripService tripService;

    @Test
    void 공개_필드만_새_소유자에게_복사하고_개인_정보는_옮기지_않는다() {
        User owner = new User("google", "owner", "소유자", null);
        User target = new User("google", "target", "요청자", null);
        LocalDate date = LocalDate.of(2026, 11, 17);
        Trip source = new Trip(owner, "  원본 제목  ", date, date);
        Itinerary sourceDay = new Itinerary(source, 3, date);
        TripPlace original = new TripPlace(sourceDay, "장소", "부산", "cafe", 35.1, 129.0,
                "전화", "주소", 4, PlaceSource.VIDEO, 99L);
        original.applyDetails(LocalTime.of(10, 0), LocalTime.of(11, 0), TransportMode.WALK, "개인 메모");
        original.applyGooglePlaceId("google-id");
        original.applySpace("INDOOR");
        when(tripRepository.save(any(Trip.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(itineraryRepository.findByTripOrderByDay(source)).thenReturn(List.of(sourceDay));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(sourceDay)).thenReturn(List.of(original));

        Trip copy = tripService.copyTrip(target, source);

        assertThat(copy).isNotSameAs(source);
        assertThat(copy.getUser()).isSameAs(target);
        assertThat(copy.getTitle()).isEqualTo("  원본 제목  ");
        assertThat(copy.getStartDate()).isEqualTo(date);
        assertThat(copy.getEndDate()).isEqualTo(date);
        ArgumentCaptor<Itinerary> dayCaptor = ArgumentCaptor.forClass(Itinerary.class);
        verify(itineraryRepository).save(dayCaptor.capture());
        Itinerary copiedDay = dayCaptor.getValue();
        assertThat(copiedDay).isNotSameAs(sourceDay);
        assertThat(copiedDay.getTrip()).isSameAs(copy);
        assertThat(copiedDay.getDay()).isEqualTo(3);
        assertThat(copiedDay.getDate()).isEqualTo(date);
        ArgumentCaptor<TripPlace> placeCaptor = ArgumentCaptor.forClass(TripPlace.class);
        verify(tripPlaceRepository).save(placeCaptor.capture());
        TripPlace copied = placeCaptor.getValue();
        assertThat(copied).isNotSameAs(original);
        assertThat(copied.getItinerary()).isSameAs(copiedDay);
        assertThat(copied.getPlaceName()).isEqualTo("장소");
        assertThat(copied.getRegion()).isEqualTo("부산");
        assertThat(copied.getCategory()).isEqualTo("cafe");
        assertThat(copied.getLatitude()).isEqualTo(35.1);
        assertThat(copied.getLongitude()).isEqualTo(129.0);
        assertThat(copied.getPhone()).isEqualTo("전화");
        assertThat(copied.getAddress()).isEqualTo("주소");
        assertThat(copied.getVisitOrder()).isEqualTo(4);
        assertThat(copied.getVisitStartTime()).isEqualTo(original.getVisitStartTime());
        assertThat(copied.getVisitEndTime()).isEqualTo(original.getVisitEndTime());
        assertThat(copied.getArrivalTransportMode()).isEqualTo(TransportMode.WALK);
        assertThat(copied.getSource()).isEqualTo(PlaceSource.MANUAL);
        assertThat(copied.getMemo()).isNull();
        assertThat(copied.getSavedPlaceId()).isNull();
        assertThat(copied.getGooglePlaceId()).isNull();
        assertThat(copied.getSpace()).isNull();
        assertThat(original.getMemo()).isEqualTo("개인 메모");
        assertThat(original.getSource()).isEqualTo(PlaceSource.VIDEO);
    }

    @Test
    void 날짜_없는_자기_여행도_빈_일차를_그대로_복사한다() {
        User owner = new User("google", "owner", "소유자", null);
        Trip source = new Trip(owner, "날짜 없음", null, null);
        List<Itinerary> days = List.of(new Itinerary(source, 1, null), new Itinerary(source, 2, null));
        when(tripRepository.save(any(Trip.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(itineraryRepository.findByTripOrderByDay(source)).thenReturn(days);
        for (Itinerary day : days) {
            when(tripPlaceRepository.findByItineraryOrderByVisitOrder(day)).thenReturn(List.of());
        }

        Trip copy = tripService.copyTrip(owner, source);

        assertThat(copy.getUser()).isSameAs(owner);
        assertThat(copy.getStartDate()).isNull();
        assertThat(copy.getEndDate()).isNull();
        ArgumentCaptor<Itinerary> captor = ArgumentCaptor.forClass(Itinerary.class);
        verify(itineraryRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(Itinerary::getDay).containsExactly(1, 2);
        assertThat(captor.getAllValues()).allSatisfy(day -> {
            assertThat(day.getTrip()).isSameAs(copy);
            assertThat(day.getDate()).isNull();
        });
    }
}
