package com.trova.backend.service;

import com.trova.backend.entity.*;
import com.trova.backend.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TripServiceIntegrationTest {

    private static final String PROVIDER_USER_ID = "trip-service-integration-1";

    @Autowired
    private TripService tripService;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private SavedPlaceRepository savedPlaceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private ItineraryRepository itineraryRepository;

    @Autowired
    private TripPlaceRepository tripPlaceRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @AfterEach
    void tearDown() {
        userRepository.findByProviderAndProviderUserId("google", PROVIDER_USER_ID)
                .ifPresent(user -> {
                    tripRepository.findByUserOrderByCreatedAtDesc(user).forEach(trip -> {
                        itineraryRepository.findByTripOrderByDay(trip).forEach(itinerary -> {
                            tripPlaceRepository.deleteAll(tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary));
                        });
                        itineraryRepository.deleteAll(itineraryRepository.findByTripOrderByDay(trip));
                    });
                    tripRepository.deleteAll(tripRepository.findByUserOrderByCreatedAtDesc(user));
                    savedPlaceRepository.deleteAll(savedPlaceRepository.findByUserOrderByCreatedAtDescIdDesc(user));
                    processingJobRepository.deleteAll(processingJobRepository.findByUserOrderByCreatedAtDescIdDesc(user));
                    userRepository.delete(user);
                });
    }

    private User newUser() {
        return userRepository.findByProviderAndProviderUserId("google", PROVIDER_USER_ID)
                .orElseGet(() -> userRepository.save(new User("google", PROVIDER_USER_ID, "트립유저", null)));
    }

    @Test
    void 확정된_장소들을_day별로_묶어_Trip과_Itinerary와_TripPlace를_만든다() {
        User user = newUser();
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/trip1", SourcePlatform.YOUTUBE));
        SavedPlace day1First = savedPlaceRepository.save(
                new SavedPlace(job, user, "1일차 첫번째", "부산", "cafe", 35.1, 129.0, 1, 1));
        SavedPlace day1Second = savedPlaceRepository.save(
                new SavedPlace(job, user, "1일차 두번째", "부산", "restaurant", 35.2, 129.1, 1, 2));
        SavedPlace day2First = savedPlaceRepository.save(
                new SavedPlace(job, user, "2일차 첫번째", "부산", "attraction", 35.3, 129.2, 2, 1));
        SavedPlace unassigned = savedPlaceRepository.save(
                new SavedPlace(job, user, "미배정", "부산", "cafe", 35.4, 129.3, null, null));

        Trip trip = tripService.confirmVideoPlacesIntoTrip(
                user, "부산 여행", List.of(day1First, day1Second, day2First, unassigned), null);

        assertThat(trip.getId()).isNotNull();
        assertThat(trip.getTitle()).isEqualTo("부산 여행");
        assertThat(trip.getUser().getId()).isEqualTo(user.getId());

        List<Itinerary> itineraries = itineraryRepository.findByTripOrderByDay(trip);
        assertThat(itineraries).hasSize(2);
        assertThat(itineraries.get(0).getDay()).isEqualTo(1);
        assertThat(itineraries.get(1).getDay()).isEqualTo(2);

        List<TripPlace> day1Places = tripPlaceRepository.findByItineraryOrderByVisitOrder(itineraries.get(0));
        assertThat(day1Places).hasSize(2);
        assertThat(day1Places.get(0).getPlaceName()).isEqualTo("1일차 첫번째");
        assertThat(day1Places.get(0).getVisitOrder()).isEqualTo(1);
        assertThat(day1Places.get(0).getSource()).isEqualTo(PlaceSource.VIDEO);
        assertThat(day1Places.get(0).getSavedPlaceId()).isEqualTo(day1First.getId());
        assertThat(day1Places.get(1).getPlaceName()).isEqualTo("1일차 두번째");

        List<TripPlace> day2Places = tripPlaceRepository.findByItineraryOrderByVisitOrder(itineraries.get(1));
        assertThat(day2Places).hasSize(1);
        assertThat(day2Places.get(0).getPlaceName()).isEqualTo("2일차 첫번째");
    }

    @Test
    void day가_없는_장소만_있으면_Trip은_생기지만_Itinerary는_없다() {
        User user = newUser();
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/trip2", SourcePlatform.YOUTUBE));
        SavedPlace unassigned = savedPlaceRepository.save(
                new SavedPlace(job, user, "미배정", "부산", "cafe", 35.4, 129.3, null, null));

        Trip trip = tripService.confirmVideoPlacesIntoTrip(user, "빈 여행", List.of(unassigned), null);

        assertThat(trip.getId()).isNotNull();
        assertThat(itineraryRepository.findByTripOrderByDay(trip)).isEmpty();
    }

    @Test
    void startDate가_있으면_각_Itinerary에_실제_날짜를_계산해서_넣는다() {
        User user = newUser();
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/trip3", SourcePlatform.YOUTUBE));
        SavedPlace day1 = savedPlaceRepository.save(
                new SavedPlace(job, user, "1일차", "부산", "cafe", 35.1, 129.0, 1, 1));
        SavedPlace day3 = savedPlaceRepository.save(
                new SavedPlace(job, user, "3일차", "부산", "cafe", 35.2, 129.1, 3, 1));

        LocalDate startDate = LocalDate.of(2026, 10, 1);
        Trip trip = tripService.confirmVideoPlacesIntoTrip(user, "날짜있는 여행", List.of(day1, day3), startDate);

        assertThat(trip.getStartDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(trip.getEndDate()).isEqualTo(LocalDate.of(2026, 10, 3));

        List<Itinerary> itineraries = itineraryRepository.findByTripOrderByDay(trip);
        assertThat(itineraries).hasSize(2);
        assertThat(itineraries.get(0).getDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(itineraries.get(1).getDate()).isEqualTo(LocalDate.of(2026, 10, 3));
    }

    @Test
    void createTrip은_기간만큼_Itinerary를_자동_생성한다() {
        User user = newUser();

        Trip trip = tripService.createTrip(
                user, "제주 여행", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3));

        List<Itinerary> itineraries = itineraryRepository.findByTripOrderByDay(trip);
        assertThat(itineraries).hasSize(3);
        assertThat(itineraries.get(0).getDay()).isEqualTo(1);
        assertThat(itineraries.get(0).getDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(itineraries.get(2).getDay()).isEqualTo(3);
        assertThat(itineraries.get(2).getDate()).isEqualTo(LocalDate.of(2026, 11, 3));
    }

    @Test
    void addPlaceToDay는_googlePlaceId로_찾은_장소를_해당_일차_끝에_추가한다() {
        User user = newUser();
        Trip trip = tripService.createTrip(user, "제주 여행", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1));
        placeRepository.save(new Place(
                "trip-place-test-donsadon", "돈사돈", "음식점 > 한식", 4.3, 500, null, 33.4, 126.5, "제주 노형동"));

        TripPlace created = tripService.addPlaceToDay(user, trip.getId(), 1, "trip-place-test-donsadon").orElseThrow();

        assertThat(created.getPlaceName()).isEqualTo("돈사돈");
        assertThat(created.getSource()).isEqualTo(PlaceSource.NORMAL);
        assertThat(created.getVisitOrder()).isEqualTo(1);
        assertThat(created.getSavedPlaceId()).isNull();
        assertThat(created.getGooglePlaceId()).isEqualTo("trip-place-test-donsadon");
    }

    @Test
    void addPlaceToDay는_존재하지_않는_googlePlaceId면_아무것도_만들지_않는다() {
        User user = newUser();
        Trip trip = tripService.createTrip(user, "제주 여행", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1));

        Optional<TripPlace> result = tripService.addPlaceToDay(user, trip.getId(), 1, "존재하지-않는-id");

        assertThat(result).isEmpty();
    }

    @Test
    void removePlace는_소유자_확인_후_삭제한다() {
        User user = newUser();
        Trip trip = tripService.createTrip(user, "제주 여행", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1));
        placeRepository.save(new Place("trip-place-test-remove", "돈사돈", null, null, null, null, 33.4, 126.5, null));
        TripPlace place = tripService.addPlaceToDay(user, trip.getId(), 1, "trip-place-test-remove").orElseThrow();

        boolean removed = tripService.removePlace(user, place.getId());

        assertThat(removed).isTrue();
        assertThat(tripPlaceRepository.findById(place.getId())).isEmpty();
    }

    @Test
    void reorderPlace는_이웃과_순서를_맞바꾼다() {
        User user = newUser();
        Trip trip = tripService.createTrip(user, "제주 여행", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1));
        placeRepository.save(new Place("trip-place-test-first", "첫번째", null, null, null, null, 33.4, 126.5, null));
        placeRepository.save(new Place("trip-place-test-second", "두번째", null, null, null, null, 33.5, 126.6, null));
        TripPlace first = tripService.addPlaceToDay(user, trip.getId(), 1, "trip-place-test-first").orElseThrow();
        TripPlace second = tripService.addPlaceToDay(user, trip.getId(), 1, "trip-place-test-second").orElseThrow();

        tripService.reorderPlace(user, first.getId(), "DOWN");

        TripPlace reloadedFirst = tripPlaceRepository.findById(first.getId()).orElseThrow();
        TripPlace reloadedSecond = tripPlaceRepository.findById(second.getId()).orElseThrow();
        assertThat(reloadedFirst.getVisitOrder()).isEqualTo(2);
        assertThat(reloadedSecond.getVisitOrder()).isEqualTo(1);
    }

    @Test
    void deleteTrip은_딸린_Itinerary와_TripPlace까지_전부_지운다() {
        User user = newUser();
        Trip trip = tripService.createTrip(user, "제주 여행", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1));
        placeRepository.save(new Place("trip-place-test-delete", "돈사돈", null, null, null, null, 33.4, 126.5, null));
        TripPlace place = tripService.addPlaceToDay(user, trip.getId(), 1, "trip-place-test-delete").orElseThrow();

        boolean deleted = tripService.deleteTrip(user, trip.getId());

        assertThat(deleted).isTrue();
        assertThat(tripRepository.findById(trip.getId())).isEmpty();
        assertThat(tripPlaceRepository.findById(place.getId())).isEmpty();
        assertThat(itineraryRepository.findByTripOrderByDay(trip)).isEmpty();
    }
}
