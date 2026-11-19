package com.trova.backend.controller;

import com.trova.backend.controller.TripController.ItineraryResponse;
import com.trova.backend.controller.TripController.TripDetailResponse;
import com.trova.backend.controller.TripController.TripPlaceResponse;
import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.TransportMode;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 여행 상세(#476) 응답이 P5 이전(엔티티 기반 toDetail)과 바이트 단위로 같은지 고정한다.
 * 기대값은 이전 구현을 그대로 옮긴 참조 조립 + MVC에 등록된 실제 JSON 변환기로 만든다.
 * TripDetailQueryCountTest와 같은 설정이라 스프링 컨텍스트를 공유한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.datasource.url=jdbc:h2:mem:trip-detail-query-count;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
class TripDetailResponseEquivalenceTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private TripRepository tripRepository;
    @Autowired private ItineraryRepository itineraryRepository;
    @Autowired private TripPlaceRepository tripPlaceRepository;
    @Autowired private RequestMappingHandlerAdapter handlerAdapter;

    @Test
    void 빈_일차와_선택_필드와_역순_저장이_섞여도_이전_응답과_바이트가_같다() throws Exception {
        User owner = newUser();
        LocalDate start = LocalDate.of(2026, 12, 1);
        Trip trip = tripRepository.save(new Trip(owner, "제주 \"맛집\" 여행 🍊", start, start.plusDays(3)));
        // 일차·장소를 역순으로 저장해 저장 순서가 아니라 일차·방문 순서로 정렬되는지 본다.
        Itinerary day4 = itineraryRepository.save(new Itinerary(trip, 4, start.plusDays(3)));
        itineraryRepository.save(new Itinerary(trip, 3, start.plusDays(2)));
        Itinerary day2 = itineraryRepository.save(new Itinerary(trip, 2, start.plusDays(1)));
        Itinerary day1 = itineraryRepository.save(new Itinerary(trip, 1, start));
        place(day1, "1일차-3", 3, PlaceSource.NORMAL, null, null);
        TripPlace detailed = place(day1, "1일차-1", 1, PlaceSource.VIDEO, 37.5, 127.0);
        detailed.applyGooglePlaceId("ChIJ-abc");
        detailed.applyDetails(LocalTime.of(9, 30), LocalTime.of(11, 0), null,
                "줄바꿈\n따옴표\" 역슬래시\\ 이모지 🍜 <b>태그</b>");
        tripPlaceRepository.save(detailed);
        TripPlace second = place(day1, "1일차-2", 2, PlaceSource.NORMAL, 33.4, 126.5);
        second.applyDetails(null, LocalTime.of(13, 5), TransportMode.TRANSIT, "");
        tripPlaceRepository.save(second);
        place(day2, "2일차-1", 1, PlaceSource.VIDEO, 33.1, 126.1);
        TripPlace last = place(day4, "4일차-1", 1, PlaceSource.NORMAL, 35.0, 129.0);
        last.applyDetails(null, null, TransportMode.CAR, null);
        tripPlaceRepository.save(last);
        // 다른 여행의 장소가 섞이지 않는지.
        otherTripWithPlaces();

        assertSameAsBefore(trip);
    }

    @Test
    void 날짜_미정_여행과_일차_날짜가_비어도_이전_응답과_같다() throws Exception {
        User owner = newUser();
        Trip trip = tripRepository.save(new Trip(owner, "날짜 미정", null, null));
        Itinerary day1 = itineraryRepository.save(new Itinerary(trip, 1, null));
        itineraryRepository.save(new Itinerary(trip, 2, null));
        place(day1, "미정-1", 1, PlaceSource.NORMAL, null, null);
        assertSameAsBefore(trip);
    }

    @Test
    void 장소가_하나도_없는_여행과_일차가_없는_여행도_이전_응답과_같다() throws Exception {
        User owner = newUser();
        LocalDate start = LocalDate.of(2027, 1, 1);
        Trip empty = tripRepository.save(new Trip(owner, "빈 여행", start, start.plusDays(2)));
        for (int day = 1; day <= 3; day++) {
            itineraryRepository.save(new Itinerary(empty, day, start.plusDays(day - 1)));
        }
        assertSameAsBefore(empty);

        Trip noDays = tripRepository.save(new Trip(owner, "일차 없음", null, null));
        assertSameAsBefore(noDays);
    }

    @Test
    void 일차_삼십개와_장소_삼백개도_이전_응답과_같다() throws Exception {
        User owner = newUser();
        LocalDate start = LocalDate.of(2027, 3, 1);
        Trip trip = tripRepository.save(new Trip(owner, "긴 여행", start, start.plusDays(29)));
        for (int day = 30; day >= 1; day--) {
            Itinerary itinerary = itineraryRepository.save(new Itinerary(trip, day, start.plusDays(day - 1)));
            int count = day % 7 == 0 ? 0 : 10 + day % 3;
            for (int visit = count; visit >= 1; visit--) {
                place(itinerary, day + "일차-" + visit, visit, PlaceSource.NORMAL, 37.0 + visit / 100.0, 127.0);
            }
        }
        assertSameAsBefore(trip);
    }

    @Test
    void 남의_여행과_없는_여행은_404이고_인증_없음과_탈퇴한_사용자는_401이다() throws Exception {
        User owner = newUser();
        Trip trip = tripRepository.save(new Trip(owner, "내 여행", null, null));
        itineraryRepository.save(new Itinerary(trip, 1, null));
        User stranger = newUser();
        String strangerBearer = "Bearer " + jwtService.issue(stranger);

        mockMvc.perform(get("/api/trips/" + trip.getId()).header("Authorization", strangerBearer))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/trips/" + Long.MAX_VALUE).header("Authorization", strangerBearer))
                .andExpect(status().isNotFound());
        // 예열(메모리 확인)된 뒤에도 같은 결과.
        mockMvc.perform(get("/api/trips/" + trip.getId()).header("Authorization", strangerBearer))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/trips/" + trip.getId()))
                .andExpect(status().isUnauthorized());

        User deleted = newUser();
        String deletedBearer = "Bearer " + jwtService.issue(deleted);
        userRepository.delete(deleted);
        mockMvc.perform(get("/api/trips/" + trip.getId()).header("Authorization", deletedBearer))
                .andExpect(status().isUnauthorized());
    }

    private void assertSameAsBefore(Trip trip) throws Exception {
        String bearer = "Bearer " + jwtService.issue(trip.getUser());
        byte[] expected = serialize(referenceDetail(trip));
        // 처음(사용자 확인)과 예열 뒤 응답 모두 같아야 한다.
        for (int i = 0; i < 2; i++) {
            byte[] actual = mockMvc.perform(get("/api/trips/" + trip.getId()).header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(new String(actual, StandardCharsets.UTF_8))
                    .isEqualTo(new String(expected, StandardCharsets.UTF_8));
            assertThat(actual).isEqualTo(expected);
        }
    }

    /** P5 이전 TripController.toDetail을 그대로 옮긴 참조 구현. */
    private TripDetailResponse referenceDetail(Trip trip) {
        Map<Long, List<TripPlace>> placesByDay = tripPlaceRepository.findByTripOrderByDayAndVisitOrder(trip).stream()
                .collect(Collectors.groupingBy(place -> place.getItinerary().getId()));
        List<ItineraryResponse> days = itineraryRepository.findByTripOrderByDay(trip).stream()
                .map(itinerary -> new ItineraryResponse(
                        itinerary.getId(), itinerary.getDay(), itinerary.getDate(),
                        placesByDay.getOrDefault(itinerary.getId(), List.of()).stream()
                                .map(TripPlaceResponse::from)
                                .toList()))
                .toList();
        return new TripDetailResponse(trip.getId(), trip.getTitle(), trip.getStartDate(), trip.getEndDate(), days);
    }

    @SuppressWarnings("unchecked")
    private byte[] serialize(TripDetailResponse response) throws Exception {
        for (HttpMessageConverter<?> converter : handlerAdapter.getMessageConverters()) {
            if (converter.canWrite(TripDetailResponse.class, MediaType.APPLICATION_JSON)) {
                MockHttpOutputMessage out = new MockHttpOutputMessage();
                ((HttpMessageConverter<Object>) converter).write(response, MediaType.APPLICATION_JSON, out);
                return out.getBodyAsBytes();
            }
        }
        throw new IllegalStateException("JSON 변환기가 없다");
    }

    private User newUser() {
        return userRepository.save(new User("google", "detail-eq-" + UUID.randomUUID(), "사용자", null));
    }

    private TripPlace place(Itinerary itinerary, String name, int visitOrder, PlaceSource source,
                            Double latitude, Double longitude) {
        return tripPlaceRepository.save(new TripPlace(itinerary, name, "제주", "cafe",
                latitude, longitude, "064-000-0000", "제주시 어딘가", visitOrder, source, null));
    }

    private void otherTripWithPlaces() {
        User other = newUser();
        Trip trip = tripRepository.save(new Trip(other, "남의 여행", null, null));
        Itinerary day1 = itineraryRepository.save(new Itinerary(trip, 1, null));
        place(day1, "남의-1", 1, PlaceSource.NORMAL, null, null);
    }
}
