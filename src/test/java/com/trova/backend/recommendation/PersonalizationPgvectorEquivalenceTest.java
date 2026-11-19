package com.trova.backend.recommendation;

import com.trova.backend.embedding.GeminiEmbeddingClient;
import com.trova.backend.embedding.GeminiTextClient;
import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.SignalType;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.entity.UserPreferenceSignal;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserPreferenceSignalRepository;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

/**
 * 빈 시간 추천의 개인화 묶음 SQL(#482, findCandidateSimilarities)이 실제 PostgreSQL+pgvector에서
 * 기존 후보별 경로(findEmbeddingText → findTopSimilarSignals)와 같은 점수·같은 추천 순서를 내는지 확인한다.
 * H2에는 embedding 칸이 없어 양쪽 모두 0점으로 폴백하므로, 이 비교는 컨테이너에서만 의미가 있다.
 * 운영과 같은 PostgreSQL 17 주 버전의 pgvector 공식 이미지를 쓰고, Docker가 없는 환경(CI 등)에서는 건너뛴다.
 * 외부 API(Google·Gemini)는 목으로 막는다 — 임베딩이 없는 후보의 임베딩 생성 요청은 빈 결과로 끝난다.
 */
@Testcontainers(disabledWithoutDocker = true)
// 컨테이너가 JVM 종료 전에 먼저 내려가므로 종료 때 표 지우기(create-drop)는 하지 않는다 — 컨테이너째 버린다.
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create")
// 컨테이너는 이 클래스가 끝나면 내려가므로 컨텍스트도 바로 닫는다 — 캐시에 남으면 테스트 JVM 힙(기본 512MiB)을
// 계속 차지해 뒤에 오는 내장 Kafka 테스트가 OutOfMemoryError로 시작하지 못했다(전체 실행에서 확인).
@DirtiesContext
class PersonalizationPgvectorEquivalenceTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    private static final int DIMENSIONS = 768;

    @MockitoBean private GooglePlacesApiClient googlePlacesApiClient;
    @MockitoBean private GeminiEmbeddingClient geminiEmbeddingClient;
    @MockitoBean private GeminiTextClient geminiTextClient;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private PlaceRepository places;
    @Autowired private UserPreferenceSignalRepository signals;
    @Autowired private TripRepository trips;
    @Autowired private ItineraryRepository itineraries;
    @Autowired private TripPlaceRepository tripPlaces;
    @Autowired private PersonalizationService personalizationService;
    @Autowired private GapRecommendationService gapRecommendationService;

    private final Random random = new Random(482);

    /** 사용자 이름 → 사용자. 신호 구성은 fixture() 주석 참고. */
    private Map<String, User> usersByName;
    /** 후보(카탈로그 장소) — Google 검색이 이 순서로 돌려준다. */
    private List<Place> candidates;

    @BeforeEach
    void pgvector() {
        // 운영처럼 embedding은 JPA 매핑 밖의 vector(768) 칸이다(PlaceRepository 주석). Hibernate가 표를 만든 뒤 붙인다.
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("ALTER TABLE places ADD COLUMN IF NOT EXISTS embedding vector(768)");
    }

    // ---- 벡터·픽스처 ----

    private float[] randomUnit() {
        float[] v = new float[DIMENSIONS];
        double norm = 0;
        for (int i = 0; i < DIMENSIONS; i++) {
            v[i] = (float) random.nextGaussian();
            norm += v[i] * v[i];
        }
        for (int i = 0; i < DIMENSIONS; i++) {
            v[i] = (float) (v[i] / Math.sqrt(norm));
        }
        return v;
    }

    private static float[] negate(float[] v) {
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = -v[i];
        }
        return out;
    }

    /** 0.5 비율로 섞은 벡터 — 서로 적당히 가까운 후보·신호를 만들어 순위가 고르게 갈리게 한다. */
    private static float[] mix(float[] a, float[] b) {
        float[] out = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (a[i] + b[i]) / 2;
        }
        return out;
    }

    /** PlaceEmbeddingService.toVectorLiteral과 같은 모양. */
    private static String literal(float[] v) {
        return "[" + IntStream.range(0, v.length).mapToObj(i -> String.valueOf(v[i])).collect(Collectors.joining(",")) + "]";
    }

    private Place place(String label, float[] embedding) {
        Place place = places.save(new Place("pgv-" + label + "-" + UUID.randomUUID(), "장소-" + label, "restaurant",
                4.0, 100, null, 37.5, 127.0, "주소-" + label));
        if (embedding != null) {
            jdbc.update("UPDATE places SET embedding = CAST(? AS vector) WHERE id = ?", literal(embedding), place.getId());
        }
        return place;
    }

    private void signal(User user, Place place, SignalType type) {
        signals.save(new UserPreferenceSignal(user, place, type));
    }

    private User user(String name) {
        return users.save(new User("google", "pgv-" + name + "-" + UUID.randomUUID(), name, null));
    }

    /**
     * 사용자 5명과 후보 14개.
     * - A: 신호 11개(장소 10곳) — 무작위 7곳, 같은 벡터 두 곳(거리 동점), 같은 장소에 신호 두 번(중복), embedding 없는 곳, 0 벡터.
     * - B: 신호 6개 — 무작위 4곳 + 같은 벡터 W 두 곳. 후보 -W에서는 W 두 곳이 5·6위로 동점이라 top-5 경계가 동점이다.
     * - C: 신호 2개 — 무작위 1곳 + 0 벡터(코사인 거리 NaN이 top-5 안에 들어간다).
     * - D: 신호 없음. E: embedding 없는 장소 신호만.
     * 후보: 무작위·신호 근처 10개, 신호와 같은 벡터(거리 0), -W, 0 벡터, embedding 없음.
     */
    private void fixture() {
        float[][] s = new float[8][];
        for (int i = 0; i < s.length; i++) {
            s[i] = randomUnit();
        }
        float[] w = randomUnit();
        List<Place> signalPlaces = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            signalPlaces.add(place("s" + i, s[i]));
        }
        Place tieA = place("tie-a", s[4]);
        Place tieB = place("tie-b", s[4]);
        Place noEmbedding = place("s-null", null);
        Place zero = place("s-zero", new float[DIMENSIONS]);
        Place wA = place("w-a", w);
        Place wB = place("w-b", w);
        Place s7 = place("s7", s[7]);

        usersByName = new LinkedHashMap<>();
        User a = user("A");
        for (Place p : signalPlaces) {
            signal(a, p, SignalType.BOOKMARK);
        }
        signal(a, signalPlaces.get(0), SignalType.TRIP_PLACE_ADDED);
        signal(a, tieA, SignalType.BOOKMARK);
        signal(a, tieB, SignalType.GAP_INSERTED);
        signal(a, noEmbedding, SignalType.BOOKMARK);
        signal(a, zero, SignalType.BOOKMARK);
        usersByName.put("A", a);

        User b = user("B");
        signal(b, signalPlaces.get(1), SignalType.BOOKMARK);
        signal(b, signalPlaces.get(5), SignalType.BOOKMARK);
        signal(b, signalPlaces.get(6), SignalType.BOOKMARK);
        signal(b, s7, SignalType.BOOKMARK);
        signal(b, wA, SignalType.BOOKMARK);
        signal(b, wB, SignalType.BOOKMARK);
        usersByName.put("B", b);

        User c = user("C");
        signal(c, signalPlaces.get(2), SignalType.BOOKMARK);
        signal(c, zero, SignalType.BOOKMARK);
        usersByName.put("C", c);

        usersByName.put("D", user("D"));

        User e = user("E");
        signal(e, noEmbedding, SignalType.BOOKMARK);
        usersByName.put("E", e);

        candidates = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            float[] near = i % 2 == 0 ? mix(s[i % 8], randomUnit()) : randomUnit();
            candidates.add(place("c" + i, near));
        }
        candidates.add(place("c-same", s[3]));
        candidates.add(place("c-neg-w", negate(w)));
        candidates.add(place("c-zero", new float[DIMENSIONS]));
        candidates.add(place("c-null", null));
    }

    /** 변경 전 후보별 경로의 top-5 유사도(거리 순). */
    private List<Double> legacyTopSimilarities(User user, Place candidate) {
        Optional<String> text = places.findEmbeddingText(candidate.getId());
        if (text.isEmpty()) {
            return List.of();
        }
        return signals.findTopSimilarSignals(user.getId(), text.get()).stream()
                .map(UserPreferenceSignalRepository.SimilarSignal::getSimilarity).toList();
    }

    private static List<Long> bits(List<Double> values) {
        return values.stream().map(Double::doubleToLongBits).toList();
    }

    // ---- 테스트 ----

    @Test
    void 묶음_조회의_후보별_top5와_점수가_기존_후보별_조회와_비트_단위로_같다() {
        fixture();
        int pairs = 0;
        int nonZero = 0;
        int nan = 0;
        for (Map.Entry<String, User> entry : usersByName.entrySet()) {
            User user = entry.getValue();
            Map<Long, List<Double>> batched = new HashMap<>();
            for (UserPreferenceSignalRepository.CandidateSimilarity row : signals.findCandidateSimilarities(
                    user.getId(), candidates.stream().map(Place::getId).toList())) {
                batched.computeIfAbsent(row.getCandidateId(), id -> new ArrayList<>()).add(row.getSimilarity());
            }
            Map<Long, Double> scores = personalizationService.personalizationScores(user.getId(), candidates);
            for (Place candidate : candidates) {
                // 1) 줄 단위: 같은 top-5 유사도가 같은 순서로 나온다(동점 신호는 값이 같아 순서와 무관).
                assertThat(bits(batched.getOrDefault(candidate.getId(), List.of())))
                        .as("%s / %s top-5", entry.getKey(), candidate.getName())
                        .isEqualTo(bits(legacyTopSimilarities(user, candidate)));
                // 2) 점수: 후보별 경로와 묶음 경로 모두 유한한 top-5 신호만 평균한다.
                double legacy = personalizationService.personalizationScore(user, candidate);
                double current = scores.getOrDefault(candidate.getId(), 0.0);
                double expected = legacyTopSimilarities(user, candidate).stream()
                        .mapToDouble(Double::doubleValue).filter(Double::isFinite).average().orElse(0.0);
                assertThat(Double.doubleToLongBits(current)).isEqualTo(Double.doubleToLongBits(expected));
                assertThat(Double.doubleToLongBits(current))
                        .as("%s / %s 점수 %s vs %s", entry.getKey(), candidate.getName(), current, legacy)
                        .isEqualTo(Double.doubleToLongBits(legacy));
                pairs++;
                if (Double.isNaN(legacy)) {
                    nan++;
                } else if (legacy != 0.0) {
                    nonZero++;
                }
            }
        }
        System.out.println("P7 pgvector equivalence pairs=" + pairs + " nonZero=" + nonZero + " nan=" + nan);
        // 비교가 0점끼리만 맞춘 것이 아님을 확인한다: A·B의 임베딩 있는 후보(무작위·같은 벡터·-W 12개)는 0이 아닌 점수다.
        assertThat(nonZero).isGreaterThanOrEqualTo(24);
        assertThat(nan).isZero();
        Place zero = candidates.get(12);
        for (User user : usersByName.values()) {
            assertThat(personalizationService.personalizationScores(user.getId(), List.of(zero))
                    .getOrDefault(zero.getId(), 0.0)).isZero();
        }
        assertThat(personalizationService.personalizationScores(usersByName.get("A").getId(), List.of())).isEmpty();
    }

    @Test
    void 빈_시간_추천_순서가_기존_후보별_점수로_정렬한_순서와_같다() {
        fixture();
        List<GooglePlacesNearbySearchResponse.Place> raw = candidates.stream()
                .map(c -> new GooglePlacesNearbySearchResponse.Place(
                        c.getGooglePlaceId(), new GooglePlacesNearbySearchResponse.Place.DisplayName(c.getName()),
                        List.of("restaurant"), c.getRating(), c.getUserRatingCount(), null,
                        new GooglePlacesNearbySearchResponse.Place.Location(37.5, 127.0), c.getAddress()))
                .toList();
        when(googlePlacesApiClient.searchNearby(anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(new GooglePlacesNearbySearchResponse(raw));
        List<Long> rawOrder = candidates.stream().map(Place::getId).toList();

        int reordered = 0;
        for (Map.Entry<String, User> entry : usersByName.entrySet()) {
            User user = entry.getValue();
            Trip trip = trips.save(new Trip(user, "여행", null, null));
            Itinerary day = itineraries.save(new Itinerary(trip, 1, null));
            TripPlace only = new TripPlace(day, "장소", "서울", "cafe", 37.5, 127.0, null, null, 1, PlaceSource.NORMAL, null);
            only.applyDetails(LocalTime.of(9, 0), LocalTime.of(10, 0), null, null);
            tripPlaces.save(only);

            // 변경 전 GapRecommendationService.recommend의 정렬: 후보마다 personalizationScore를 따로 불렀다.
            Map<Long, Double> legacyScore = new HashMap<>();
            for (Place c : candidates) {
                legacyScore.put(c.getId(), Math.max(PlaceScoring.baseScore(c), 1.0)
                        * (1 + personalizationService.personalizationScore(user, c) * 0.6));
            }
            List<Long> expected = candidates.stream()
                    .sorted(Comparator.comparingDouble((Place c) -> legacyScore.get(c.getId())).reversed())
                    .map(Place::getId).toList();

            List<GapRecommendationService.Gap> gaps = gapRecommendationService
                    .findGaps(user.getId(), trip.getId(), 1).orElseThrow();
            assertThat(gaps).hasSize(1);
            List<Long> actual = gaps.get(0).recommendations().stream().map(AlternativeCandidate::placeId).toList();
            assertThat(actual).as("사용자 %s 추천 순서", entry.getKey()).isEqualTo(expected);
            if (!actual.equals(rawOrder)) {
                reordered++;
            }
        }
        System.out.println("P7 pgvector order users=" + usersByName.size() + " reorderedByPersonalization=" + reordered);
        // 기본 점수가 모두 같아 순서는 개인화로만 갈린다 — 유효한 신호가 있는 A·B는 Google 순서와 달라야 비교가 의미 있다.
        // C도 0 벡터 신호를 제외한 유한한 신호로 재정렬되고, D·E는 모두 0점이라 Google 순서 그대로다.
        assertThat(reordered).isGreaterThanOrEqualTo(3);
    }
}
