package com.trova.backend.controller;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.GooglePlacesApiClient;
import com.trova.backend.recommendation.GooglePlacesNearbySearchResponse;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.service.TripService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;
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

    @Autowired
    private TripService tripService;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private SavedPlaceRepository savedPlaceRepository;

    /** 영상 처리 작업 하나와 1일차 장소 하나를 만든다. */
    private ProcessingJob videoJob(User user, String url) {
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(user, url, SourcePlatform.YOUTUBE));
        savedPlaceRepository.save(new SavedPlace(job, user, "장소", "부산", "cafe", 35.1, 129.0, 1, 1));
        return job;
    }

    @MockitoBean
    private GooglePlacesApiClient googlePlacesApiClient;

    // embedding 컬럼은 pgvector 타입이라 H2 테스트 DB 스키마에 없다(PlaceRepository 주석
    // 참고). 대안/빈시간 추천 경로에 PlaceEmbeddingService.ensureEmbeddings가 실제로
    // 연결된 뒤(개인화 랭킹 Task 6) 실제 빈을 타면 findIdsWithEmbedding 네이티브 쿼리가
    // "Column EMBEDDING not found"로 깨진다 — 다른 외부 연동(GooglePlacesApiClient)과
    // 같은 이유로 목 처리한다.
    @MockitoBean
    private PlaceEmbeddingService placeEmbeddingService;

    @BeforeEach
    void stubGooglePlaces() {
        when(googlePlacesApiClient.searchNearby(anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of()));
    }

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

    @Test
    void 대안_찾기는_후보_목록을_반환한다() throws Exception {
        User me = userRepository.save(new User("google", "alt1", "대안유저1", null));
        Trip t = trip(me, 1);
        Itinerary day1 = itineraryRepository.findByTripAndDay(t, 1).orElseThrow();
        TripPlace place = tripPlace(day1, "장소", 37.5, 127.0, 1);

        mockMvc.perform(get("/api/trip-places/" + place.getId() + "/alternatives")
                        .with(loginAs("alt1", "대안유저1")))
                .andExpect(status().isOk());
    }

    @Test
    void 대안_찾기는_필터_쿼리파라미터를_반영해_실제_후보를_바인딩한다() throws Exception {
        User me = userRepository.save(new User("google", "alt4", "대안유저4", null));
        Trip t = trip(me, 1);
        Itinerary day1 = itineraryRepository.findByTripAndDay(t, 1).orElseThrow();
        TripPlace place = tripPlace(day1, "장소", 37.5, 127.0, 1);

        var raw = new GooglePlacesNearbySearchResponse.Place(
                "gp-alt-4", new GooglePlacesNearbySearchResponse.Place.DisplayName("대안카페"),
                List.of("cafe"), 4.5, 30, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.501, 127.001), "서울 어딘가");
        when(googlePlacesApiClient.searchNearby(anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(raw)));

        mockMvc.perform(get("/api/trip-places/" + place.getId() + "/alternatives")
                        .with(loginAs("alt4", "대안유저4"))
                        .param("category", "카페")
                        .param("maxDistanceKm", "5.0")
                        .param("transportMode", "WALK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].googlePlaceId").value("gp-alt-4"))
                .andExpect(jsonPath("$[0].name").value("대안카페"));
    }

    @Test
    void 타인_소유_장소의_대안_찾기는_404() throws Exception {
        User me = userRepository.save(new User("google", "alt2", "대안유저2", null));
        User other = userRepository.save(new User("google", "alt3", "대안유저3", null));
        Trip otherTrip = trip(other, 1);
        Itinerary day1 = itineraryRepository.findByTripAndDay(otherTrip, 1).orElseThrow();
        TripPlace place = tripPlace(day1, "남의 장소", 37.5, 127.0, 1);

        mockMvc.perform(get("/api/trip-places/" + place.getId() + "/alternatives")
                        .with(loginAs("alt2", "대안유저2")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 대안으로_교체하면_이름_좌표_카테고리가_바뀌고_시간은_유지된다() throws Exception {
        User me = userRepository.save(new User("google", "rep1", "교체유저1", null));
        Trip t = trip(me, 1);
        Itinerary day1 = itineraryRepository.findByTripAndDay(t, 1).orElseThrow();
        TripPlace place = tripPlace(day1, "원래장소", 37.5, 127.0, 1);
        tripService.updateDetails(me, place.getId(), java.time.LocalTime.of(10, 0), null, null, "원래 메모");
        placeRepository.save(new Place("gp-new", "새장소", "restaurant", 4.1, 20, null, 37.6, 127.1, "새 주소"));

        mockMvc.perform(post("/api/trip-places/" + place.getId() + "/replace")
                        .with(loginAs("rep1", "교체유저1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"googlePlaceId\":\"gp-new\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeName").value("새장소"))
                .andExpect(jsonPath("$.category").value("restaurant"))
                .andExpect(jsonPath("$.region").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.latitude").value(37.6))
                .andExpect(jsonPath("$.longitude").value(127.1))
                .andExpect(jsonPath("$.address").value("새 주소"))
                .andExpect(jsonPath("$.googlePlaceId").value("gp-new"))
                .andExpect(jsonPath("$.visitStartTime").value("10:00:00"));

        TripPlace updated = tripPlaceRepository.findById(place.getId()).orElseThrow();
        assertThat(updated.getMemo()).isNull();
        assertThat(updated.getVisitOrder()).isEqualTo(1);
    }

    @Test
    void 빈_시간이_있으면_추천_목록을_반환한다() throws Exception {
        User me = userRepository.save(new User("google", "gapc1", "빈시간유저1", null));
        Trip t = trip(me, 1);
        Itinerary day1 = itineraryRepository.findByTripAndDay(t, 1).orElseThrow();
        TripPlace a = tripPlace(day1, "A", 37.500, 127.000, 1);
        TripPlace b = tripPlace(day1, "B", 37.510, 127.000, 2);
        tripService.updateDetails(me, a.getId(), null, java.time.LocalTime.of(10, 0), null, null);
        tripService.updateDetails(me, b.getId(), java.time.LocalTime.of(11, 0), null, null, null);

        mockMvc.perform(get("/api/trips/" + t.getId() + "/days/1/gap-recommendations")
                        .with(loginAs("gapc1", "빈시간유저1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].gapMinutes").value(60));
    }

    @Test
    void 존재하지_않는_googlePlaceId로_교체하면_404() throws Exception {
        User me = userRepository.save(new User("google", "rep2", "교체유저2", null));
        Trip t = trip(me, 1);
        Itinerary day1 = itineraryRepository.findByTripAndDay(t, 1).orElseThrow();
        TripPlace place = tripPlace(day1, "장소", 37.5, 127.0, 1);

        mockMvc.perform(post("/api/trip-places/" + place.getId() + "/replace")
                        .with(loginAs("rep2", "교체유저2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"googlePlaceId\":\"nope\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 특정_장소_뒤에_삽입하면_뒤_장소들의_순서가_밀린다() throws Exception {
        User me = userRepository.save(new User("google", "ins1", "삽입유저1", null));
        Trip t = trip(me, 1);
        Itinerary day1 = itineraryRepository.findByTripAndDay(t, 1).orElseThrow();
        TripPlace a = tripPlace(day1, "A", 37.5, 127.0, 1);
        TripPlace b = tripPlace(day1, "B", 37.6, 127.1, 2);
        placeRepository.save(new Place("gp-mid", "중간장소", "cafe", null, null, null, 37.55, 127.05, null));

        mockMvc.perform(post("/api/trip-places/insert")
                        .with(loginAs("ins1", "삽입유저1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"afterTripPlaceId\":" + a.getId() + ",\"googlePlaceId\":\"gp-mid\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeName").value("중간장소"))
                .andExpect(jsonPath("$.visitOrder").value(2));

        assertThat(tripPlaceRepository.findById(b.getId()).orElseThrow().getVisitOrder()).isEqualTo(3);
    }

    @Test
    void 타인_소유_장소_뒤에_삽입하면_404() throws Exception {
        User me = userRepository.save(new User("google", "ins2", "삽입유저2", null));
        User other = userRepository.save(new User("google", "ins3", "삽입유저3", null));
        Trip otherTrip = trip(other, 1);
        Itinerary day1 = itineraryRepository.findByTripAndDay(otherTrip, 1).orElseThrow();
        TripPlace place = tripPlace(day1, "장소", 37.5, 127.0, 1);
        placeRepository.save(new Place("gp-x", "X", "cafe", null, null, null, 37.5, 127.0, null));

        mockMvc.perform(post("/api/trip-places/insert")
                        .with(loginAs("ins2", "삽입유저2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"afterTripPlaceId\":" + place.getId() + ",\"googlePlaceId\":\"gp-x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 영상으로_만든_여행_조회_같은_영상을_다시_추출한_작업에서도_찾는다() throws Exception {
        User me = userRepository.save(new User("google", "video-trip-1", "영상유저", null));
        ProcessingJob first = videoJob(me, "https://www.youtube.com/shorts/CtrlDup01");
        Trip trip = tripService.confirmVideoPlacesIntoTrip(
                me, "부산 여행", savedPlaceRepository.findByProcessingJob(first), null);
        ProcessingJob again = videoJob(me, "https://youtu.be/CtrlDup01?si=share");

        mockMvc.perform(get("/api/places/videos/" + again.getId() + "/trip").with(loginAs("video-trip-1", "영상유저")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(trip.getId()));
    }

    @Test
    void 영상으로_만든_여행_조회_없으면_404() throws Exception {
        User me = userRepository.save(new User("google", "video-trip-2", "영상유저2", null));
        ProcessingJob job = videoJob(me, "https://youtu.be/CtrlNone02");

        mockMvc.perform(get("/api/places/videos/" + job.getId() + "/trip").with(loginAs("video-trip-2", "영상유저2")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 영상으로_만든_여행_조회_남의_작업이면_404() throws Exception {
        User owner = userRepository.save(new User("google", "video-trip-3", "주인", null));
        userRepository.save(new User("google", "video-trip-4", "남", null));
        ProcessingJob job = videoJob(owner, "https://youtu.be/CtrlOwner03");
        tripService.confirmVideoPlacesIntoTrip(owner, "주인 여행", savedPlaceRepository.findByProcessingJob(job), null);

        mockMvc.perform(get("/api/places/videos/" + job.getId() + "/trip").with(loginAs("video-trip-4", "남")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 같은_영상을_다시_추출한_작업으로_여행을_확정하면_새로_만들지_않고_기존_여행을_돌려준다() throws Exception {
        User me = userRepository.save(new User("google", "video-trip-5", "영상유저5", null));
        ProcessingJob first = videoJob(me, "https://www.youtube.com/watch?v=CtrlConf05");
        Trip trip = tripService.confirmVideoPlacesIntoTrip(
                me, "부산 여행", savedPlaceRepository.findByProcessingJob(first), null);
        ProcessingJob again = videoJob(me, "https://youtu.be/CtrlConf05");
        long before = tripRepository.findByUserOrderByCreatedAtDesc(me).size();

        mockMvc.perform(post("/api/places/videos/" + again.getId() + "/confirm-trip")
                        .with(loginAs("video-trip-5", "영상유저5"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"또 만든 여행\",\"startDate\":\"2026-10-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(trip.getId()));
        assertThat(tripRepository.findByUserOrderByCreatedAtDesc(me)).hasSize((int) before);
    }
}
