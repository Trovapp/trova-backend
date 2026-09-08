package com.trova.backend.controller;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TripControllerTest {

    @Autowired
    private MockMvc mockMvc;

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

    private ClientRegistration googleRegistration() {
        return ClientRegistration.withRegistrationId("google")
                .clientId("test-client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .build();
    }

    private RequestPostProcessor loginAs(String sub, String name) {
        return oauth2Login()
                .clientRegistration(googleRegistration())
                .attributes(attrs -> {
                    attrs.put("sub", sub);
                    attrs.put("name", name);
                    attrs.put("picture", "https://example.com/p.jpg");
                });
    }

    /** startDate 2026-01-01부터 days일짜리 여행 + 그만큼의 Itinerary를 만든다. */
    private Trip trip(User user, int days) {
        LocalDate startDate = LocalDate.of(2026, 1, 1);
        Trip trip = tripRepository.save(new Trip(user, "테스트 여행", startDate, startDate.plusDays(days - 1)));
        for (int day = 1; day <= days; day++) {
            itineraryRepository.save(new Itinerary(trip, day, startDate.plusDays(day - 1)));
        }
        return trip;
    }

    private TripPlace tripPlace(Itinerary itinerary, String name, Double lat, Double lng, int visitOrder) {
        return tripPlaceRepository.save(
                new TripPlace(itinerary, name, "서울", "cafe", lat, lng, null, null, visitOrder, PlaceSource.NORMAL, null));
    }

    @Test
    void 여행_생성_성공() throws Exception {
        User me = userRepository.save(new User("google", "trip1", "여행유저1", null));

        mockMvc.perform(post("/api/trips")
                        .with(loginAs("trip1", "여행유저1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"제주 여행\",\"startDate\":\"2026-03-01\",\"endDate\":\"2026-03-03\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("제주 여행"));
    }

    @Test
    void 제목이_비면_400() throws Exception {
        userRepository.save(new User("google", "trip2", "여행유저2", null));

        mockMvc.perform(post("/api/trips")
                        .with(loginAs("trip2", "여행유저2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"startDate\":\"2026-03-01\",\"endDate\":\"2026-03-03\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 종료일이_시작일보다_빠르면_400() throws Exception {
        userRepository.save(new User("google", "trip3", "여행유저3", null));

        mockMvc.perform(post("/api/trips")
                        .with(loginAs("trip3", "여행유저3"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"제주\",\"startDate\":\"2026-03-03\",\"endDate\":\"2026-03-01\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 여행_상세_조회시_일차별로_장소가_묶여서_온다() throws Exception {
        User me = userRepository.save(new User("google", "trip4", "여행유저4", null));
        Trip t = trip(me, 2);
        Itinerary day1 = itineraryRepository.findByTripAndDay(t, 1).orElseThrow();
        tripPlace(day1, "장소A", 37.5, 127.0, 1);

        mockMvc.perform(get("/api/trips/" + t.getId()).with(loginAs("trip4", "여행유저4")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(2))
                .andExpect(jsonPath("$.days[0].places[0].placeName").value("장소A"));
    }

    @Test
    void 타인_여행_조회는_404() throws Exception {
        User me = userRepository.save(new User("google", "trip5", "여행유저5", null));
        User other = userRepository.save(new User("google", "trip6", "여행유저6", null));
        Trip otherTrip = trip(other, 1);

        mockMvc.perform(get("/api/trips/" + otherTrip.getId()).with(loginAs("trip5", "여행유저5")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 여행_삭제시_일정과_장소도_함께_지워진다() throws Exception {
        User me = userRepository.save(new User("google", "trip7", "여행유저7", null));
        Trip t = trip(me, 1);
        Itinerary day1 = itineraryRepository.findByTripAndDay(t, 1).orElseThrow();
        TripPlace place = tripPlace(day1, "장소", 37.5, 127.0, 1);

        mockMvc.perform(delete("/api/trips/" + t.getId()).with(loginAs("trip7", "여행유저7")))
                .andExpect(status().isNoContent());

        assertThat(tripRepository.findById(t.getId())).isEmpty();
        assertThat(tripPlaceRepository.findById(place.getId())).isEmpty();
    }

    @Test
    void 카탈로그_장소를_일차에_추가한다() throws Exception {
        User me = userRepository.save(new User("google", "trip8", "여행유저8", null));
        Trip t = trip(me, 1);
        placeRepository.save(new Place("gp-1", "경복궁", "attraction", 4.5, 100, null, 37.58, 126.97, "서울 종로구"));

        mockMvc.perform(post("/api/trips/" + t.getId() + "/days/1/places")
                        .with(loginAs("trip8", "여행유저8"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"googlePlaceId\":\"gp-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeName").value("경복궁"))
                .andExpect(jsonPath("$.visitOrder").value(1));
    }

    @Test
    void 카탈로그에_없는_googlePlaceId면_404() throws Exception {
        User me = userRepository.save(new User("google", "trip9", "여행유저9", null));
        Trip t = trip(me, 1);

        mockMvc.perform(post("/api/trips/" + t.getId() + "/days/1/places")
                        .with(loginAs("trip9", "여행유저9"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"googlePlaceId\":\"nope\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 장소_삭제_성공() throws Exception {
        User me = userRepository.save(new User("google", "trip10", "여행유저10", null));
        Trip t = trip(me, 1);
        Itinerary day1 = itineraryRepository.findByTripAndDay(t, 1).orElseThrow();
        TripPlace place = tripPlace(day1, "장소", 37.5, 127.0, 1);

        mockMvc.perform(delete("/api/trip-places/" + place.getId()).with(loginAs("trip10", "여행유저10")))
                .andExpect(status().isNoContent());

        assertThat(tripPlaceRepository.findById(place.getId())).isEmpty();
    }

    @Test
    void 타인_장소_삭제는_404() throws Exception {
        User me = userRepository.save(new User("google", "trip11", "여행유저11", null));
        User other = userRepository.save(new User("google", "trip12", "여행유저12", null));
        Trip otherTrip = trip(other, 1);
        Itinerary day1 = itineraryRepository.findByTripAndDay(otherTrip, 1).orElseThrow();
        TripPlace place = tripPlace(day1, "남의 장소", 37.5, 127.0, 1);

        mockMvc.perform(delete("/api/trip-places/" + place.getId()).with(loginAs("trip11", "여행유저11")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 순서를_아래로_바꾸면_인접_장소와_교체된다() throws Exception {
        User me = userRepository.save(new User("google", "trip13", "여행유저13", null));
        Trip t = trip(me, 1);
        Itinerary day1 = itineraryRepository.findByTripAndDay(t, 1).orElseThrow();
        TripPlace first = tripPlace(day1, "첫번째", 37.5, 127.0, 1);
        TripPlace second = tripPlace(day1, "두번째", 37.6, 127.1, 2);

        mockMvc.perform(patch("/api/trip-places/" + first.getId() + "/order")
                        .with(loginAs("trip13", "여행유저13"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"DOWN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitOrder").value(2));

        assertThat(tripPlaceRepository.findById(second.getId()).orElseThrow().getVisitOrder()).isEqualTo(1);
    }

    @Test
    void 순서_변경_방향이_잘못되면_400() throws Exception {
        User me = userRepository.save(new User("google", "trip14", "여행유저14", null));
        Trip t = trip(me, 1);
        Itinerary day1 = itineraryRepository.findByTripAndDay(t, 1).orElseThrow();
        TripPlace place = tripPlace(day1, "장소", 37.5, 127.0, 1);

        mockMvc.perform(patch("/api/trip-places/" + place.getId() + "/order")
                        .with(loginAs("trip14", "여행유저14"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"SIDEWAYS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 동선_최적화는_총_이동거리가_최소가_되도록_재배열한다() throws Exception {
        User me = userRepository.save(new User("google", "trip15", "여행유저15", null));
        Trip t = trip(me, 1);
        Itinerary day1 = itineraryRepository.findByTripAndDay(t, 1).orElseThrow();
        // A(37.500)---C(37.501)---B(37.502) 일직선상, C가 중간. 입력 순서는 A,B,C지만
        // 총 이동거리를 최소화하면 C가 반드시 가운데(index 1)로 와야 한다.
        TripPlace a = tripPlace(day1, "A", 37.500, 127.000, 1);
        tripPlace(day1, "B", 37.502, 127.000, 2);
        TripPlace c = tripPlace(day1, "C", 37.501, 127.000, 3);

        mockMvc.perform(post("/api/trips/" + t.getId() + "/days/1/optimize-route")
                        .with(loginAs("trip15", "여행유저15")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[1].id").value(c.getId()))
                .andExpect(jsonPath("$[1].visitOrder").value(2));

        assertThat(tripPlaceRepository.findById(a.getId()).orElseThrow().getVisitOrder()).isIn(1, 3);
    }

    @Test
    void 좌표_없는_장소는_동선_최적화에서_맨_뒤로_밀린다() throws Exception {
        User me = userRepository.save(new User("google", "trip16", "여행유저16", null));
        Trip t = trip(me, 1);
        Itinerary day1 = itineraryRepository.findByTripAndDay(t, 1).orElseThrow();
        TripPlace noCoord = tripPlace(day1, "좌표없음", null, null, 1);
        tripPlace(day1, "A", 37.500, 127.000, 2);
        tripPlace(day1, "B", 37.502, 127.000, 3);

        mockMvc.perform(post("/api/trips/" + t.getId() + "/days/1/optimize-route")
                        .with(loginAs("trip16", "여행유저16")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[2].id").value(noCoord.getId()));
    }

    @Test
    void 타인_여행의_동선_최적화는_404() throws Exception {
        User me = userRepository.save(new User("google", "trip17", "여행유저17", null));
        User other = userRepository.save(new User("google", "trip18", "여행유저18", null));
        Trip otherTrip = trip(other, 1);

        mockMvc.perform(post("/api/trips/" + otherTrip.getId() + "/days/1/optimize-route")
                        .with(loginAs("trip17", "여행유저17")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 존재하지_않는_날짜의_동선_최적화는_404() throws Exception {
        User me = userRepository.save(new User("google", "trip19", "여행유저19", null));
        Trip t = trip(me, 1);

        mockMvc.perform(post("/api/trips/" + t.getId() + "/days/99/optimize-route")
                        .with(loginAs("trip19", "여행유저19")))
                .andExpect(status().isNotFound());
    }
}
