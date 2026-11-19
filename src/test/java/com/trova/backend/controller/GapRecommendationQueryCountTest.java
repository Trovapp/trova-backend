package com.trova.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.embedding.GeminiEmbeddingClient;
import com.trova.backend.embedding.GeminiTextClient;
import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.recommendation.GapRecommendationService;
import com.trova.backend.recommendation.GooglePlacesApiClient;
import com.trova.backend.recommendation.GooglePlacesNearbySearchResponse;
import com.trova.backend.recommendation.PersonalizationService;
import com.trova.backend.recommendation.PlaceCatalogService;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.recommendation.PlaceScoring;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import com.trova.backend.service.UserAccountService;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 빈 시간 추천(P7, #482)의 응답·권한·SQL 수를 테스트 트랜잭션 없이 실제 HTTP 요청으로 잰다.
// SQL 수는 운영 P4 지표(trova.db.queries.per.request)와 같은 계측기로 센다.
// P2/P3/P6 성능 테스트의 격리 컨텍스트를 그대로 공유한다(설정이 한 글자라도 다르면 새 컨텍스트가 생긴다).
// 새 컨텍스트를 만들면 컨텍스트 캐시(32개)가 넘쳐 다른 컨텍스트가 밀려 닫힌다. 예전에는 기본 testdb 이름이 모두 같아
// create-drop이 공유 표를 지워 ApiSessionCreationIntegrationTest가 깨졌다(지금은 테스트 설정이 컨텍스트마다 다른 DB를 쓴다).
// 컨텍스트 수를 늘리지 않도록 외부 API는 @MockitoBean 대신 테스트 동안만 서비스 필드에 목을 끼우고 끝나면 되돌린다.
// H2에는 pgvector embedding 칸이 없어 임베딩 존재 확인·개인화 조회가 실패하고 0점으로 폴백한다(Gemini 호출 없음, 아래에서 확인).
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:p2-query-count;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.jpa.properties.hibernate.generate_statistics=true",
        "app.admin.users=google:p2-admin"})
class GapRecommendationQueryCountTest {

    private static final String URI = "/api/trips/{tripId}/days/{day}/gap-recommendations";

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private ItineraryRepository itineraries;
    @Autowired private TripPlaceRepository tripPlaces;
    @Autowired private PlaceCatalogService placeCatalogService;
    @Autowired private PlaceEmbeddingService placeEmbeddingService;
    @Autowired private PersonalizationService personalizationService;
    @Autowired private UserAccountService userAccountService;
    @Autowired private MeterRegistry meterRegistry;

    @Autowired private GapRecommendationService gapRecommendationService;

    private final GooglePlacesApiClient googlePlacesApiClient = mock(GooglePlacesApiClient.class);
    private final GeminiEmbeddingClient geminiEmbeddingClient = mock(GeminiEmbeddingClient.class);
    private final GeminiTextClient geminiTextClient = mock(GeminiTextClient.class);
    private final Map<String, Object> originals = new HashMap<>();

    private final ObjectMapper mapper = new ObjectMapper();
    /** 검색 종류(식당이면 "restaurant", 아니면 "any")별로 Google이 돌려줄 원시 후보. */
    private Map<String, List<GooglePlacesNearbySearchResponse.Place>> rawByType = new HashMap<>();
    private boolean googleFails;

    private void swap(Object bean, String field, Object replacement) {
        Object target = AopTestUtils.getTargetObject(bean);
        originals.put(bean.getClass().getName() + "#" + field, ReflectionTestUtils.getField(target, field));
        ReflectionTestUtils.setField(target, field, replacement);
    }

    private void restore(Object bean, String field) {
        Object target = AopTestUtils.getTargetObject(bean);
        ReflectionTestUtils.setField(target, field, originals.get(bean.getClass().getName() + "#" + field));
    }

    @AfterEach
    void restoreClients() {
        restore(gapRecommendationService, "googlePlacesApiClient");
        restore(placeEmbeddingService, "geminiEmbeddingClient");
        restore(personalizationService, "geminiEmbeddingClient");
        restore(personalizationService, "geminiTextClient");
        // 임베딩 생성·추천 이유 생성 같은 Gemini 호출은 이 경로에서 한 번도 일어나지 않아야 한다.
        verifyNoInteractions(geminiEmbeddingClient, geminiTextClient);
    }

    @BeforeEach
    void stubGoogle() {
        swap(gapRecommendationService, "googlePlacesApiClient", googlePlacesApiClient);
        swap(placeEmbeddingService, "geminiEmbeddingClient", geminiEmbeddingClient);
        swap(personalizationService, "geminiEmbeddingClient", geminiEmbeddingClient);
        swap(personalizationService, "geminiTextClient", geminiTextClient);
        rawByType = new HashMap<>();
        googleFails = false;
        when(googlePlacesApiClient.searchNearby(anyDouble(), anyDouble(), anyDouble(), any())).thenAnswer(inv -> {
            if (googleFails) {
                throw new IllegalStateException("Google 검색 실패");
            }
            String type = inv.getArgument(3);
            return new GooglePlacesNearbySearchResponse(rawByType.getOrDefault(type == null ? "any" : type, List.of()));
        });
    }

    // ---- 변경 전 경로(GapRecommendationService, 장소·후보 엔티티와 후보별 개인화 조회)를 그대로 옮긴 기준값 ----

    private static final Duration GAP_THRESHOLD = Duration.ofMinutes(30);
    private static final LocalTime DAY_END = LocalTime.of(21, 0);
    private static final Set<String> NOT_A_STOP = Set.of(
            "lodging", "hotel", "motel", "hostel", "guest_house", "resort_hotel", "extended_stay_hotel",
            "subway_station", "train_station", "transit_station", "bus_station", "light_rail_station", "airport",
            "university", "school", "primary_school", "secondary_school", "parking", "gas_station");

    private Optional<List<GapRecommendationService.Gap>> legacyFindGaps(User user, Long tripId, int day) {
        return trips.findById(tripId)
                .filter(trip -> trip.getUser().getId().equals(user.getId()))
                .flatMap(trip -> itineraries.findByTripAndDay(trip, day))
                .map(itinerary -> legacyComputeGaps(itinerary, user));
    }

    private List<GapRecommendationService.Gap> legacyComputeGaps(Itinerary itinerary, User user) {
        List<TripPlace> places = tripPlaces.findByItineraryOrderByVisitOrder(itinerary);
        List<GapRecommendationService.Gap> gaps = new ArrayList<>();
        for (int i = 0; i < places.size() - 1; i++) {
            TripPlace before = places.get(i);
            TripPlace after = places.get(i + 1);
            LocalTime endTime = before.getVisitEndTime();
            LocalTime startTime = after.getVisitStartTime();
            if (endTime == null || startTime == null) continue;
            Duration gap = Duration.between(endTime, startTime);
            if (gap.compareTo(GAP_THRESHOLD) <= 0) continue;
            if (before.getLatitude() == null || before.getLongitude() == null
                    || after.getLatitude() == null || after.getLongitude() == null) continue;
            double midLat = (before.getLatitude() + after.getLatitude()) / 2;
            double midLng = (before.getLongitude() + after.getLongitude()) / 2;
            gaps.add(new GapRecommendationService.Gap(before.getId(), after.getId(), (int) gap.toMinutes(),
                    legacyRecommend(user, midLat, midLng, endTime, startTime, List.of(before, after))));
        }
        if (!places.isEmpty()) {
            TripPlace last = places.get(places.size() - 1);
            LocalTime endTime = last.getVisitEndTime();
            if (endTime != null && endTime.isBefore(DAY_END) && last.getLatitude() != null && last.getLongitude() != null) {
                Duration gap = Duration.between(endTime, DAY_END);
                if (gap.compareTo(GAP_THRESHOLD) > 0) {
                    gaps.add(new GapRecommendationService.Gap(last.getId(), null, (int) gap.toMinutes(),
                            legacyRecommend(user, last.getLatitude(), last.getLongitude(), endTime, DAY_END, List.of(last))));
                }
            }
        }
        return gaps;
    }

    private List<AlternativeCandidate> legacyRecommend(User user, double lat, double lng, LocalTime from, LocalTime to,
                                                       List<TripPlace> neighbors) {
        String includedType = overlaps(from, to, LocalTime.of(11, 0), LocalTime.of(14, 0))
                || overlaps(from, to, LocalTime.of(17, 0), LocalTime.of(20, 0)) ? "restaurant" : null;
        GooglePlacesNearbySearchResponse response;
        try {
            response = googlePlacesApiClient.searchNearby(lat, lng, 1500, includedType);
        } catch (Exception e) {
            response = new GooglePlacesNearbySearchResponse(List.of());
        }
        List<GooglePlacesNearbySearchResponse.Place> raw = response.places() != null ? response.places() : List.of();
        List<Place> candidates = raw.isEmpty() ? List.of() : placeCatalogService.upsertAll(raw);
        placeEmbeddingService.ensureEmbeddings(candidates);
        List<Place> filtered = candidates.stream()
                .filter(c -> neighbors.stream().noneMatch(n -> n.getGooglePlaceId() != null
                        && n.getGooglePlaceId().equals(c.getGooglePlaceId())))
                .filter(c -> !NOT_A_STOP.contains(c.getCategory()))
                .toList();
        Map<Long, Double> scoreByPlaceId = new HashMap<>();
        for (Place c : filtered) {
            double score = Math.max(PlaceScoring.baseScore(c), 1.0)
                    * (1 + personalizationService.personalizationScore(user, c) * 0.6);
            scoreByPlaceId.put(c.getId(), score);
        }
        return filtered.stream()
                .sorted(Comparator.comparingDouble((Place c) -> scoreByPlaceId.get(c.getId())).reversed())
                .map(c -> new AlternativeCandidate(
                        c.getId(), c.getGooglePlaceId(), c.getName(), c.getCategory(), c.getRating(),
                        c.getUserRatingCount(), c.getLatitude(), c.getLongitude(), c.getAddress(),
                        null, null, false, null, null))
                .toList();
    }

    private static boolean overlaps(LocalTime from, LocalTime to, LocalTime windowFrom, LocalTime windowTo) {
        return from.isBefore(windowTo) && to.isAfter(windowFrom);
    }

    private String legacyJson(User user, Long tripId, int day) throws Exception {
        List<GapRecommendationService.Gap> gaps = legacyFindGaps(user, tripId, day).orElseThrow();
        return mapper.writeValueAsString(gaps.stream().map(TripController.GapResponse::from).toList());
    }

    // ---- 픽스처 ----

    private User user() {
        return users.save(new User("google", "p7-" + UUID.randomUUID(), "사용자", null));
    }

    private String bearer(User user) {
        return "Bearer " + jwt.issue(user);
    }

    private Trip trip(User user, int days) {
        Trip trip = trips.save(new Trip(user, "여행", null, null));
        for (int d = 1; d <= days; d++) {
            itineraries.save(new Itinerary(trip, d, null));
        }
        return trip;
    }

    private Itinerary day(Trip trip, int day) {
        return itineraries.findByTripAndDay(trip, day).orElseThrow();
    }

    /** 시간은 "HH:mm" 또는 null. 좌표가 null이면 좌표 없는 장소. */
    private TripPlace place(Itinerary itinerary, int order, String start, String end, Double lat, Double lng,
                            String googlePlaceId) {
        TripPlace place = new TripPlace(itinerary, "장소" + order, "서울", "cafe", lat, lng, null, null,
                order, PlaceSource.NORMAL, null);
        place.applyDetails(start == null ? null : LocalTime.parse(start), end == null ? null : LocalTime.parse(end),
                null, null);
        if (googlePlaceId != null) place.applyGooglePlaceId(googlePlaceId);
        return tripPlaces.save(place);
    }

    private static GooglePlacesNearbySearchResponse.Place raw(String id, String type, Double rating, Integer reviews) {
        return new GooglePlacesNearbySearchResponse.Place(
                id, new GooglePlacesNearbySearchResponse.Place.DisplayName("이름-" + id),
                type == null ? null : List.of(type), rating, reviews, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.5, 127.0), "주소-" + id);
    }

    /** 평점·리뷰 수가 섞인 후보 n개(앞쪽 이름이 같은 점수끼리 묶이게 일부러 동점도 넣는다). */
    private static List<GooglePlacesNearbySearchResponse.Place> candidates(String prefix, int n) {
        List<GooglePlacesNearbySearchResponse.Place> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Double rating = i % 4 == 3 ? null : 3.0 + (i % 3) * 0.5;
            Integer reviews = i % 5 == 4 ? null : 10 * (i % 3);
            list.add(raw(prefix + "-" + i, i % 2 == 0 ? "cafe" : "tourist_attraction", rating, reviews));
        }
        return list;
    }

    private String request(String bearer, Long tripId, int day) throws Exception {
        return mvc.perform(get("/api/trips/" + tripId + "/days/" + day + "/gap-recommendations")
                        .header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void assertSameAsLegacy(User user, Long tripId, int day) throws Exception {
        String actual = request(bearer(user), tripId, day);
        assertThat(mapper.readTree(actual)).isEqualTo(mapper.readTree(legacyJson(user, tripId, day)));
    }

    private double sqlTotal() {
        DistributionSummary summary = meterRegistry.find("trova.db.queries.per.request")
                .tags("uri", URI, "method", "GET").summary();
        return summary == null ? 0 : summary.totalAmount();
    }

    /** 사용자 확인 캐시·장소 카탈로그를 데운 뒤 다음 요청 한 번의 SQL 수. */
    private long warmSql(String bearer, Long tripId, int day) throws Exception {
        request(bearer, tripId, day);
        double before = sqlTotal();
        request(bearer, tripId, day);
        return Math.round(sqlTotal() - before);
    }

    private double medianMillis(String bearer, Long tripId, int day) throws Exception {
        for (int i = 0; i < 10; i++) request(bearer, tripId, day);
        double[] samples = new double[30];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            request(bearer, tripId, day);
            samples[i] = (System.nanoTime() - start) / 1_000_000.0;
        }
        Arrays.sort(samples);
        return (samples[14] + samples[15]) / 2;
    }

    // ---- 테스트 ----

    @Test
    void 장소와_후보가_어떤_모양이어도_응답이_기존과_같다() throws Exception {
        // 1) 장소 0개
        User u1 = user();
        Trip t1 = trip(u1, 1);
        assertSameAsLegacy(u1, t1.getId(), 1);
        assertThat(request(bearer(u1), t1.getId(), 1)).isEqualTo("[]");

        // 2) 장소 1개, 시간 없음 → 빈 시간 없음
        User u2 = user();
        Trip t2 = trip(u2, 1);
        place(day(t2, 1), 1, null, null, 37.5, 127.0, null);
        rawByType.put("any", candidates("c2", 3));
        assertSameAsLegacy(u2, t2.getId(), 1);
        assertThat(request(bearer(u2), t2.getId(), 1)).isEqualTo("[]");

        // 3) 장소 1개가 10:00에 끝남 → 하루 끝까지 빈 시간 하나. 점심·저녁이 걸리므로 식당 검색.
        //    숙소·역(NOT_A_STOP)과 자기 자신(같은 googlePlaceId)은 빠진다. 동점 후보는 원래 순서를 지킨다.
        User u3 = user();
        Trip t3 = trip(u3, 1);
        place(day(t3, 1), 1, "09:00", "10:00", 37.5, 127.0, "c3-self");
        List<GooglePlacesNearbySearchResponse.Place> c3 = new ArrayList<>(candidates("c3", 12));
        c3.add(raw("c3-self", "cafe", 5.0, 100));
        c3.add(raw("c3-hotel", "lodging", 5.0, 100));
        c3.add(raw("c3-station", "subway_station", 5.0, 100));
        rawByType.put("restaurant", c3);
        assertSameAsLegacy(u3, t3.getId(), 1);

        // 4) 여러 장소: 사이 빈 시간(60분, 점심 → 식당), 30분 이하(건너뜀), 시간 빠짐, 좌표 없음, 하루 끝(저녁 → 식당)
        User u4 = user();
        Trip t4 = trip(u4, 2);
        Itinerary d4 = day(t4, 2);
        place(d4, 1, "09:00", "10:00", 37.50, 127.00, "c4-a");
        place(d4, 2, "11:00", "12:00", 37.51, 127.01, null);
        place(d4, 3, "12:20", "13:00", 37.52, 127.02, null);
        place(d4, 4, "15:00", null, 37.53, 127.03, null);
        place(d4, 5, "16:00", "16:30", null, null, null);
        place(d4, 6, "18:00", "19:00", 37.55, 127.05, "c4-f");
        place(d4, 7, "20:00", "20:10", 37.56, 127.06, null);
        rawByType.put("restaurant", candidates("c4r", 20));
        rawByType.put("any", candidates("c4a", 7));
        assertSameAsLegacy(u4, t4.getId(), 2);
        // 1→2(60분), 3→4(120분), 6→7(60분), 7→하루 끝(50분). 2→3은 20분, 4는 끝 시간 없음, 5는 좌표 없음.
        assertThat(mapper.readTree(request(bearer(u4), t4.getId(), 2))).hasSize(4);

        // 5) 빈 시간은 있지만 후보 0개
        User u5 = user();
        Trip t5 = trip(u5, 1);
        place(day(t5, 1), 1, "09:00", "10:00", 37.5, 127.0, null);
        place(day(t5, 1), 2, "15:00", "16:00", 37.6, 127.1, null);
        rawByType = new HashMap<>();
        assertSameAsLegacy(u5, t5.getId(), 1);

        // 6) 빈 시간 없음: 촘촘한 일정, 마지막이 20:45에 끝남(15분)
        User u6 = user();
        Trip t6 = trip(u6, 1);
        place(day(t6, 1), 1, "19:00", "20:00", 37.5, 127.0, null);
        place(day(t6, 1), 2, "20:20", "20:45", 37.6, 127.1, null);
        rawByType.put("restaurant", candidates("c6", 5));
        assertSameAsLegacy(u6, t6.getId(), 1);
        assertThat(request(bearer(u6), t6.getId(), 1)).isEqualTo("[]");

        // 7) Google 검색 실패 → 추천 없이 빈 시간만
        User u7 = user();
        Trip t7 = trip(u7, 1);
        place(day(t7, 1), 1, "09:00", "10:00", 37.5, 127.0, null);
        googleFails = true;
        assertSameAsLegacy(u7, t7.getId(), 1);
    }

    @Test
    void types가_없는_Google_후보도_분류_null로_유지하고_200을_반환한다() throws Exception {
        User owner = user();
        Trip trip = trip(owner, 1);
        place(day(trip, 1), 1, "09:00", "10:00", 37.5, 127.0, null);
        String prefix = "missing-types-" + UUID.randomUUID();
        rawByType.put("restaurant", List.of(raw(prefix, null, 4.0, 10),
                raw(prefix + "-normal", "restaurant", 3.0, 10),
                raw(prefix + "-hotel", "lodging", 5.0, 100)));

        com.fasterxml.jackson.databind.JsonNode recommendations = mapper.readTree(
                request(bearer(owner), trip.getId(), 1)).get(0).get("recommendations");
        assertThat(recommendations).hasSize(2);
        assertThat(recommendations.get(0).get("googlePlaceId").asText()).isEqualTo(prefix);
        assertThat(recommendations.get(0).get("category").isNull()).isTrue();
        assertThat(recommendations.get(1).get("googlePlaceId").asText()).isEqualTo(prefix + "-normal");
    }

    @Test
    void 남의_여행_없는_여행_없는_일차는_404이고_인증이_없으면_401이다() throws Exception {
        User owner = user();
        User stranger = user();
        Trip trip = trip(owner, 1);
        place(day(trip, 1), 1, "09:00", "10:00", 37.5, 127.0, null);
        String path = "/api/trips/" + trip.getId() + "/days/";
        mvc.perform(get(path + "1/gap-recommendations").header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/trips/" + Long.MAX_VALUE + "/days/1/gap-recommendations")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isNotFound());
        mvc.perform(get(path + "2/gap-recommendations").header("Authorization", bearer(owner)))
                .andExpect(status().isNotFound());
        mvc.perform(get(path + "1/gap-recommendations"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path + "1/gap-recommendations").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
    }

    @Test
    void 빈_시간_추천으로_예열한_뒤_탈퇴하면_같은_토큰은_401이다() throws Exception {
        User owner = user();
        String bearer = bearer(owner);
        Trip trip = trip(owner, 1);
        request(bearer, trip.getId(), 1); // 사용자 확인을 메모리에 기억시킨다(ADR 0036)

        userAccountService.withdraw(users.findById(owner.getId()).orElseThrow());

        mvc.perform(get("/api/trips/" + trip.getId() + "/days/1/gap-recommendations").header("Authorization", bearer))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/trips/" + Long.MAX_VALUE + "/days/1/gap-recommendations").header("Authorization", bearer))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 장소_수가_늘어도_빈_시간이_없으면_SQL은_한_번이다() throws Exception {
        List<Long> sql = new ArrayList<>();
        for (int count : new int[] {0, 1, 5, 20}) {
            User user = user();
            Trip trip = trip(user, 1);
            for (int i = 1; i <= count; i++) {
                place(day(trip, 1), i, null, null, 37.5, 127.0, null);
            }
            sql.add(warmSql(bearer(user), trip.getId(), 1));
        }
        System.out.println("P7 no-gap places=0,1,5,20 SQL=" + sql);
        assertThat(sql).containsExactly(1L, 1L, 1L, 1L);
    }

    @Test
    void 후보_수가_늘어도_빈_시간_하나의_SQL은_일정하다() throws Exception {
        List<Long> sql = new ArrayList<>();
        List<String> medians = new ArrayList<>();
        for (int count : new int[] {0, 1, 5, 20}) {
            User user = user();
            Trip trip = trip(user, 1);
            // 장소 3개, 시간은 마지막 장소만(하루 끝 빈 시간 하나)
            place(day(trip, 1), 1, null, null, 37.5, 127.0, null);
            place(day(trip, 1), 2, null, null, 37.5, 127.0, null);
            place(day(trip, 1), 3, "09:00", "10:00", 37.5, 127.0, null);
            rawByType.put("restaurant", candidates("q" + count + "-" + UUID.randomUUID(), count));
            String bearer = bearer(user);
            sql.add(warmSql(bearer, trip.getId(), 1));
            medians.add(String.format("%.3f", medianMillis(bearer, trip.getId(), 1)));
        }
        System.out.println("P7 one-gap candidates=0,1,5,20 SQL=" + sql + " medianMs=" + medians);
        assertThat(sql).containsExactly(2L, 5L, 5L, 5L);
    }

    @Test
    void 빈_시간_수만큼만_SQL이_늘고_후보마다_늘지_않는다() throws Exception {
        List<Long> sql = new ArrayList<>();
        for (int gaps : new int[] {1, 2, 3}) {
            User user = user();
            Trip trip = trip(user, 1);
            // 장소를 2시간 간격으로 두면 장소 사이 빈 시간이 gaps-1개, 마지막(19:00 이전 종료)이 하루 끝 1개
            for (int i = 0; i < gaps; i++) {
                String start = String.format("%02d:00", 9 + i * 2);
                String end = String.format("%02d:00", 10 + i * 2);
                place(day(trip, 1), i + 1, start, end, 37.5 + i * 0.01, 127.0, null);
            }
            rawByType.put("restaurant", candidates("g" + gaps + "r-" + UUID.randomUUID(), 20));
            rawByType.put("any", candidates("g" + gaps + "a-" + UUID.randomUUID(), 20));
            sql.add(warmSql(bearer(user), trip.getId(), 1));
        }
        System.out.println("P7 gaps=1,2,3 candidates=20 SQL=" + sql);
        assertThat(sql).containsExactly(5L, 9L, 13L);
    }
}
