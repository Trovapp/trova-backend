# 대안 찾기(플랜비 ⑤~⑦) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 일정 장소별 대안 찾기(필터+AI후보+리뷰요약+미리보기+교체), 빈 시간 대안 추천, 날씨 경보 연동을 하나의 메커니즘으로 구현한다.

**Architecture:** 백엔드에 신규 `AlternativeFinderService`(필터 기반 구글 Places 후보 검색)와 `GapRecommendationService`(빈 시간 감지+추천)를 추가하고, `TripService`에 교체/중간삽입 메서드를 얹는다. `WeatherRecoveryService`는 자체 카카오 대안 계산을 버리고 `Notification`에 `tripPlaceId`만 남겨 같은 대안 찾기 화면으로 연결한다. 앱은 새 `AlternativeFinderSheet`(비차단 BottomSheet, 지도가 뒤에서 계속 터치되는 이번 세션의 패턴 재사용) 하나로 필터→후보→미리보기→교체를 다 처리한다.

**Tech Stack:** Spring Boot(Java), Google Places API(New) `searchNearby`, 서울 열린데이터광장 실시간 도시데이터 API, Expo/React Native, `@gorhom/bottom-sheet`.

**Spec:** `docs/superpowers/specs/2026-09-08-alternative-finder-design.md`

## Global Constraints

- 후보 검색은 구글 Places(`GooglePlacesApiClient.searchNearby`)만 쓴다 — 카카오/네이버 혼용 금지(0-1 원칙)
- 이동수단별 평균 속도는 도보 4km/h, 대중교통 20km/h, 차량 30km/h — 실측 아닌 추정치임을 코드 주석에 명시
- 빈 시간 임계값은 30분, 서울 혼잡도 캐시 갱신 주기는 6시간 — 둘 다 실측 아닌 통상값
- 혼잡도 API 실패, 검색 실패는 절대 500을 던지지 않고 빈 결과/무시로 처리(대안 찾기는 부가 기능)
- 커밋 메시지는 한국어 `타입: 내용` 형식만, AI 서명/트레일러 절대 금지(CLAUDE.md)
- 백엔드 커밋 전 `./gradlew build`, 앱은 `npx tsc --noEmit` 통과 필수

---

### Task 1: `GooglePlacesApiClient.searchNearby`에 카테고리 필터(`includedType`) 추가

**Files:**
- Modify: `src/main/java/com/trova/backend/recommendation/GooglePlacesApiClient.java`
- Modify: `src/main/java/com/trova/backend/recommendation/GooglePlacesApiClientImpl.java`
- Test: `src/test/java/com/trova/backend/recommendation/GooglePlacesApiClientImplTest.java` (신규, 기존 테스트 없으면 새로 만든다 — 재시도 정책만 검증하는 가벼운 통합 테스트는 만들지 않고, 이 태스크는 컴파일+수동 확인으로 충분. 실제 검증은 Task 3의 `AlternativeFinderServiceTest`에서 이 메서드를 호출하는 서비스 레벨로 한다)

**Interfaces:**
- Consumes: 없음(기존 파일 확장)
- Produces: `GooglePlacesApiClient.searchNearby(double latitude, double longitude, double radiusMeters, String includedType)` — `includedType`이 null이면 타입 필터 없이 검색. 기존 3-인자 `searchNearby(lat, lng, radius)`는 유지되며 내부적으로 4-인자 버전에 `includedType=null`로 위임한다.

- [ ] **Step 1: 인터페이스에 4-인자 오버로드 추가**

`GooglePlacesApiClient.java`를 다음으로 교체:

```java
package com.trova.backend.recommendation;

public interface GooglePlacesApiClient {
    GooglePlacesNearbySearchResponse searchNearby(double latitude, double longitude, double radiusMeters);
    GooglePlacesNearbySearchResponse searchNearby(double latitude, double longitude, double radiusMeters, String includedType);
    GooglePlacesNearbySearchResponse searchText(String query);
    GooglePlacesDetailsResponse getDetails(String googlePlaceId);
}
```

- [ ] **Step 2: 구현체 수정**

`GooglePlacesApiClientImpl.java`에서 기존 3-인자 `searchNearby`/`doSearchNearby`를 아래로 교체(4-인자 버전을 추가하고 3-인자는 위임):

```java
    @Override
    public GooglePlacesNearbySearchResponse searchNearby(double latitude, double longitude, double radiusMeters) {
        return searchNearby(latitude, longitude, radiusMeters, null);
    }

    @Override
    public GooglePlacesNearbySearchResponse searchNearby(
            double latitude, double longitude, double radiusMeters, String includedType
    ) {
        return withRetry(() -> doSearchNearby(latitude, longitude, radiusMeters, includedType));
    }
```

그리고 `doSearchNearby`도 `includedType` 파라미터를 받아 body에 조건부로 추가하도록 교체:

```java
    private GooglePlacesNearbySearchResponse doSearchNearby(
            double latitude, double longitude, double radiusMeters, String includedType
    ) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "maxResultCount", MAX_RESULT_COUNT,
                "languageCode", "ko",
                "regionCode", "KR",
                "locationRestriction", Map.of(
                        "circle", Map.of(
                                "center", Map.of("latitude", latitude, "longitude", longitude),
                                "radius", radiusMeters
                        )
                )
        ));
        if (includedType != null) {
            body.put("includedTypes", List.of(includedType));
        }

        return restClient.post()
                .uri("/v1/places:searchNearby")
                .header("X-Goog-FieldMask", SEARCH_FIELD_MASK)
                .body(body)
                .retrieve()
                .body(GooglePlacesNearbySearchResponse.class);
    }
```

`Map.of(...)`가 불변 맵이라 `includedTypes`를 나중에 추가할 수 없으므로 `new HashMap<>(Map.of(...))`로 감쌌다. 파일 상단에 `import java.util.HashMap;`과 `import java.util.List;`가 없으면 추가한다(기존 파일에 `List` import가 이미 있는지 확인 — 없으면 추가).

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 기존 테스트(있다면) 회귀 확인**

Run: `./gradlew test --tests "com.trova.backend.recommendation.*"`
Expected: 기존 테스트 전부 통과(없으면 통과할 테스트가 0개인 채로 성공)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/trova/backend/recommendation/GooglePlacesApiClient.java src/main/java/com/trova/backend/recommendation/GooglePlacesApiClientImpl.java
git commit -m "feat: 구글 Places 근처 검색에 카테고리 필터 추가"
```

---

### Task 2: 카테고리 → 구글 타입 매핑 + `AlternativeFilter`/`AlternativeCandidate` 레코드

**Files:**
- Create: `src/main/java/com/trova/backend/recommendation/GoogleTypeMapper.java`
- Create: `src/main/java/com/trova/backend/recommendation/AlternativeFilter.java`
- Create: `src/main/java/com/trova/backend/recommendation/AlternativeCandidate.java`
- Test: `src/test/java/com/trova/backend/recommendation/GoogleTypeMapperTest.java`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `GoogleTypeMapper.toGoogleType(String freeTextCategory) -> Optional<String>`
  - `record AlternativeFilter(String category, Boolean indoorOnly, Double maxDistanceKm, Integer maxTravelMinutes, com.trova.backend.entity.TransportMode transportMode)` — 전부 nullable
  - `record AlternativeCandidate(Long placeId, String googlePlaceId, String name, String category, Double rating, Integer userRatingCount, Double latitude, Double longitude, String address, Double distanceToNextKm, Integer estimatedTravelMinutes, Boolean isCongestionAvailable, String congestionLevel)`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/trova/backend/recommendation/GoogleTypeMapperTest.java`:

```java
package com.trova.backend.recommendation;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleTypeMapperTest {

    @Test
    void 매핑된_카테고리는_구글_타입을_반환한다() {
        assertThat(GoogleTypeMapper.toGoogleType("카페")).isEqualTo(Optional.of("cafe"));
        assertThat(GoogleTypeMapper.toGoogleType("맛집")).isEqualTo(Optional.of("restaurant"));
        assertThat(GoogleTypeMapper.toGoogleType("박물관")).isEqualTo(Optional.of("museum"));
    }

    @Test
    void 매핑에_없는_카테고리는_빈값을_반환한다() {
        assertThat(GoogleTypeMapper.toGoogleType("아무말대잔치")).isEmpty();
    }

    @Test
    void null이나_공백은_빈값을_반환한다() {
        assertThat(GoogleTypeMapper.toGoogleType(null)).isEmpty();
        assertThat(GoogleTypeMapper.toGoogleType("  ")).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.recommendation.GoogleTypeMapperTest"`
Expected: FAIL — `GoogleTypeMapper` 클래스가 없어서 컴파일 에러

- [ ] **Step 3: `GoogleTypeMapper` 구현**

```java
package com.trova.backend.recommendation;

import java.util.Map;
import java.util.Optional;

/**
 * 사용자가 자유 입력한 카테고리 텍스트를 구글 Places API v1의 includedType
 * 열거값으로 매핑한다. Google이 받는 값은 고정된 타입 문자열뿐이라 자유 텍스트를
 * 그대로 넘길 수 없다 — 자주 쓰는 것만 우선 매핑하고, 매핑에 없으면 빈 값을
 * 반환해서 호출부가 반경 검색만 하고 이름/카테고리 텍스트로 후처리 필터링하게
 * 한다(검색 자체가 실패하지 않도록).
 */
public final class GoogleTypeMapper {

    private static final Map<String, String> CATEGORY_TO_GOOGLE_TYPE = Map.ofEntries(
            Map.entry("카페", "cafe"),
            Map.entry("커피", "cafe"),
            Map.entry("맛집", "restaurant"),
            Map.entry("음식점", "restaurant"),
            Map.entry("식당", "restaurant"),
            Map.entry("박물관", "museum"),
            Map.entry("미술관", "art_gallery"),
            Map.entry("쇼핑", "shopping_mall"),
            Map.entry("공원", "park"),
            Map.entry("술집", "bar"),
            Map.entry("바", "bar"),
            Map.entry("숙소", "lodging"),
            Map.entry("호텔", "lodging"),
            Map.entry("관광", "tourist_attraction"),
            Map.entry("영화관", "movie_theater")
    );

    private GoogleTypeMapper() {
    }

    public static Optional<String> toGoogleType(String freeTextCategory) {
        if (freeTextCategory == null || freeTextCategory.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(CATEGORY_TO_GOOGLE_TYPE.get(freeTextCategory.trim()));
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.recommendation.GoogleTypeMapperTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: `AlternativeFilter`/`AlternativeCandidate` 레코드 작성**

`src/main/java/com/trova/backend/recommendation/AlternativeFilter.java`:

```java
package com.trova.backend.recommendation;

import com.trova.backend.entity.TransportMode;

/** 대안 찾기 필터 — 전부 nullable(안 걸면 그 조건은 무시). */
public record AlternativeFilter(
        String category,
        Boolean indoorOnly,
        Double maxDistanceKm,
        Integer maxTravelMinutes,
        TransportMode transportMode
) {
}
```

`src/main/java/com/trova/backend/recommendation/AlternativeCandidate.java`:

```java
package com.trova.backend.recommendation;

public record AlternativeCandidate(
        Long placeId,
        String googlePlaceId,
        String name,
        String category,
        Double rating,
        Integer userRatingCount,
        Double latitude,
        Double longitude,
        String address,
        Double distanceToNextKm,
        Integer estimatedTravelMinutes,
        Boolean isCongestionAvailable,
        String congestionLevel
) {
}
```

- [ ] **Step 6: 전체 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/trova/backend/recommendation/GoogleTypeMapper.java src/main/java/com/trova/backend/recommendation/AlternativeFilter.java src/main/java/com/trova/backend/recommendation/AlternativeCandidate.java src/test/java/com/trova/backend/recommendation/GoogleTypeMapperTest.java
git commit -m "feat: 카테고리-구글타입 매핑 및 대안 찾기 필터/후보 타입 추가"
```

---

### Task 3: `AlternativeFinderService` (핵심 검색 로직, 혼잡도 제외)

**Files:**
- Create: `src/main/java/com/trova/backend/recommendation/AlternativeFinderService.java`
- Test: `src/test/java/com/trova/backend/recommendation/AlternativeFinderServiceTest.java`

**Interfaces:**
- Consumes: `GooglePlacesApiClient.searchNearby(lat, lng, radius, includedType)`(Task 1), `GoogleTypeMapper.toGoogleType`(Task 2), `AlternativeFilter`/`AlternativeCandidate`(Task 2), `PlaceCatalogService.upsertAll(List<GooglePlacesNearbySearchResponse.Place>)`(기존), `TripPlaceRepository.findById`(기존), `TripPlaceRepository.findByItineraryOrderByVisitOrder`(기존), `PlaceTaggingRunner.run(List<TagCandidate>, Long)`(기존)
- Produces: `AlternativeFinderService.findAlternatives(User user, Long tripPlaceId, AlternativeFilter filter) -> Optional<List<AlternativeCandidate>>` — `Optional.empty()`는 tripPlaceId가 없거나 소유자가 다를 때. 이 태스크에서는 `isCongestionAvailable=false, congestionLevel=null`로 고정(혼잡도는 Task 4에서 연결)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/trova/backend/recommendation/AlternativeFinderServiceTest.java`:

```java
package com.trova.backend.recommendation;

import com.trova.backend.entity.*;
import com.trova.backend.pipeline.PlaceTag;
import com.trova.backend.pipeline.PlaceTaggingRunner;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.TripPlaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlternativeFinderServiceTest {

    @Mock private GooglePlacesApiClient googlePlacesApiClient;
    @Mock private PlaceCatalogService placeCatalogService;
    @Mock private TripPlaceRepository tripPlaceRepository;
    @Mock private PlaceTaggingRunner placeTaggingRunner;
    @InjectMocks private AlternativeFinderService alternativeFinderService;

    private User user() {
        return new User("google", "alt-user", "테스트", null);
    }

    private TripPlace tripPlace(Long id, User owner, Double lat, Double lng) {
        Trip trip = new Trip(owner, "여행", null, null);
        Itinerary itinerary = new Itinerary(trip, 1, null);
        TripPlace place = new TripPlace(
                itinerary, "원래 장소", null, "cafe", lat, lng, null, null, 1, PlaceSource.NORMAL, null);
        try {
            var field = TripPlace.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(place, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return place;
    }

    @Test
    void 존재하지_않는_장소면_빈값을_반환한다() {
        when(tripPlaceRepository.findById(99L)).thenReturn(Optional.empty());

        Optional<List<AlternativeCandidate>> result =
                alternativeFinderService.findAlternatives(user(), 99L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result).isEmpty();
    }

    @Test
    void 타인_소유_장소면_빈값을_반환한다() {
        User owner = user();
        User other = new User("google", "other", "남", null);
        TripPlace place = tripPlace(1L, owner, 37.5, 127.0);
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));

        Optional<List<AlternativeCandidate>> result =
                alternativeFinderService.findAlternatives(other, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result).isEmpty();
    }

    @Test
    void 필터_없으면_검색_결과를_그대로_후보로_반환한다() {
        User owner = user();
        TripPlace place = tripPlace(1L, owner, 37.5, 127.0);
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(place.getItinerary())).thenReturn(List.of(place));

        var raw = new GooglePlacesNearbySearchResponse.Place(
                "gp-1", new GooglePlacesNearbySearchResponse.Place.DisplayName("대안카페"),
                List.of("cafe"), 4.3, 50, "PRICE_LEVEL_MODERATE",
                new GooglePlacesNearbySearchResponse.Place.Location(37.501, 127.001), "서울 어딘가");
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, null))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(raw)));

        Place upserted = new Place("gp-1", "대안카페", "cafe", 4.3, 50, "PRICE_LEVEL_MODERATE", 37.501, 127.001, "서울 어딘가");
        when(placeCatalogService.upsertAll(List.of(raw))).thenReturn(List.of(upserted));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0).name()).isEqualTo("대안카페");
        assertThat(result.get().get(0).googlePlaceId()).isEqualTo("gp-1");
    }

    @Test
    void 실내만_필터하면_공간태그가_INDOOR인_후보만_남는다() {
        User owner = user();
        TripPlace place = tripPlace(1L, owner, 37.5, 127.0);
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(place.getItinerary())).thenReturn(List.of(place));

        var rawIndoor = new GooglePlacesNearbySearchResponse.Place(
                "gp-in", new GooglePlacesNearbySearchResponse.Place.DisplayName("실내카페"),
                List.of("cafe"), 4.0, 10, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.501, 127.001), "주소1");
        var rawOutdoor = new GooglePlacesNearbySearchResponse.Place(
                "gp-out", new GooglePlacesNearbySearchResponse.Place.DisplayName("야외공원"),
                List.of("park"), 4.5, 20, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.502, 127.002), "주소2");
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, null))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(rawIndoor, rawOutdoor)));

        Place indoorPlace = new Place("gp-in", "실내카페", "cafe", 4.0, 10, null, 37.501, 127.001, "주소1");
        Place outdoorPlace = new Place("gp-out", "야외공원", "park", 4.5, 20, null, 37.502, 127.002, "주소2");
        when(placeCatalogService.upsertAll(List.of(rawIndoor, rawOutdoor)))
                .thenReturn(List.of(indoorPlace, outdoorPlace));

        when(placeTaggingRunner.run(anyList(), anyLong())).thenReturn(List.of(
                new PlaceTag(0, "조용한", "INDOOR"), new PlaceTag(1, "활기찬", "OUTDOOR")));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, true, null, null, null));

        assertThat(result).isPresent();
        assertThat(result.get()).extracting(AlternativeCandidate::name).containsExactly("실내카페");
    }

    @Test
    void 다음_장소가_있으면_거리를_계산하고_maxDistanceKm으로_필터한다() {
        User owner = user();
        Trip trip = new Trip(owner, "여행", null, null);
        Itinerary itinerary = new Itinerary(trip, 1, null);
        TripPlace place = new TripPlace(itinerary, "원래", null, "cafe", 37.500, 127.000, null, null, 1, PlaceSource.NORMAL, null);
        TripPlace next = new TripPlace(itinerary, "다음", null, "cafe", 37.510, 127.000, null, null, 2, PlaceSource.NORMAL, null);
        try {
            var f = TripPlace.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(place, 1L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary)).thenReturn(List.of(place, next));

        // 위도 0.01도 ≈ 1.11km — 후보를 next 바로 옆(약 1.1km)과 아주 먼 곳(약 5.5km) 두 개로 구성
        var near = new GooglePlacesNearbySearchResponse.Place(
                "gp-near", new GooglePlacesNearbySearchResponse.Place.DisplayName("가까운곳"),
                List.of("cafe"), null, null, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.511, 127.000), null);
        var far = new GooglePlacesNearbySearchResponse.Place(
                "gp-far", new GooglePlacesNearbySearchResponse.Place.DisplayName("먼곳"),
                List.of("cafe"), null, null, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.560, 127.000), null);
        when(googlePlacesApiClient.searchNearby(37.500, 127.000, 2000, null))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(near, far)));

        Place nearPlace = new Place("gp-near", "가까운곳", "cafe", null, null, null, 37.511, 127.000, null);
        Place farPlace = new Place("gp-far", "먼곳", "cafe", null, null, null, 37.560, 127.000, null);
        when(placeCatalogService.upsertAll(List.of(near, far))).thenReturn(List.of(nearPlace, farPlace));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, 2.0, null, null));

        assertThat(result).isPresent();
        assertThat(result.get()).extracting(AlternativeCandidate::name).containsExactly("가까운곳");
    }

    @Test
    void 구글_검색이_실패하면_500_대신_빈_목록을_반환한다() {
        User owner = user();
        TripPlace place = tripPlace(1L, owner, 37.5, 127.0);
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(place.getItinerary())).thenReturn(List.of(place));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, null))
                .thenThrow(new RuntimeException("Google Places API 장애"));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result).isPresent();
        assertThat(result.get()).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.recommendation.AlternativeFinderServiceTest"`
Expected: FAIL — `AlternativeFinderService` 없음(컴파일 에러)

- [ ] **Step 3: 구현**

```java
package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import com.trova.backend.entity.TransportMode;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.pipeline.PlaceTag;
import com.trova.backend.pipeline.PlaceTaggingRunner;
import com.trova.backend.repository.TripPlaceRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 일정 장소 하나를 기준으로 필터(카테고리/실내외/거리/이동시간)에 맞는 대안 후보를
 * 구글 Places 근처 검색으로 찾는다. 혼잡도는 별도 클라이언트(SeoulCongestionApiClient)가
 * 채워넣는다 — 이 서비스는 혼잡도 필드를 항상 false/null로 둔다.
 */
@Service
public class AlternativeFinderService {

    private static final double SEARCH_RADIUS_METERS = 2000;

    // 실측 아닌 통상적 평균 속도 추정치 — 실제 도로망을 반영하는 경로 API가 아니다.
    private static final Map<TransportMode, Double> AVERAGE_SPEED_KMH = Map.of(
            TransportMode.WALK, 4.0,
            TransportMode.TRANSIT, 20.0,
            TransportMode.CAR, 30.0
    );

    private final GooglePlacesApiClient googlePlacesApiClient;
    private final PlaceCatalogService placeCatalogService;
    private final TripPlaceRepository tripPlaceRepository;
    private final PlaceTaggingRunner placeTaggingRunner;

    public AlternativeFinderService(
            GooglePlacesApiClient googlePlacesApiClient,
            PlaceCatalogService placeCatalogService,
            TripPlaceRepository tripPlaceRepository,
            PlaceTaggingRunner placeTaggingRunner
    ) {
        this.googlePlacesApiClient = googlePlacesApiClient;
        this.placeCatalogService = placeCatalogService;
        this.tripPlaceRepository = tripPlaceRepository;
        this.placeTaggingRunner = placeTaggingRunner;
    }

    public Optional<List<AlternativeCandidate>> findAlternatives(User user, Long tripPlaceId, AlternativeFilter filter) {
        Optional<TripPlace> maybeTarget = tripPlaceRepository.findById(tripPlaceId)
                .filter(p -> p.getItinerary().getTrip().getUser().getId().equals(user.getId()));
        if (maybeTarget.isEmpty()) {
            return Optional.empty();
        }
        TripPlace target = maybeTarget.get();
        if (target.getLatitude() == null || target.getLongitude() == null) {
            return Optional.of(List.of());
        }

        String includedType = filter.category() != null
                ? GoogleTypeMapper.toGoogleType(filter.category()).orElse(null)
                : null;

        // 검색 실패(재시도 3회 소진 후 예외)를 500으로 흘려보내지 않는다 — 대안
        // 찾기는 부가 기능이라 빈 결과로 조용히 낮춘다(스펙 "에러 처리" 절 참고).
        GooglePlacesNearbySearchResponse response;
        try {
            response = googlePlacesApiClient.searchNearby(
                    target.getLatitude(), target.getLongitude(), SEARCH_RADIUS_METERS, includedType);
        } catch (Exception e) {
            return Optional.of(List.of());
        }
        List<GooglePlacesNearbySearchResponse.Place> raw =
                response.places() != null ? response.places() : List.of();
        if (raw.isEmpty()) {
            return Optional.of(List.of());
        }

        List<Place> candidates = placeCatalogService.upsertAll(raw);

        if (Boolean.TRUE.equals(filter.indoorOnly())) {
            candidates = filterIndoor(candidates);
        }

        // includedType으로 못 걸렀으면(매핑 없던 카테고리) 이름/카테고리 텍스트로 후처리 필터링.
        if (includedType == null && filter.category() != null && !filter.category().isBlank()) {
            String needle = filter.category().trim();
            candidates = candidates.stream()
                    .filter(p -> containsIgnoreCase(p.getName(), needle) || containsIgnoreCase(p.getCategory(), needle))
                    .toList();
        }

        TripPlace next = findNext(target);

        List<AlternativeCandidate> result = new ArrayList<>();
        for (Place candidate : candidates) {
            Double distanceToNextKm = null;
            Integer estimatedTravelMinutes = null;
            if (next != null && next.getLatitude() != null && next.getLongitude() != null) {
                distanceToNextKm = haversineKm(
                        candidate.getLatitude(), candidate.getLongitude(), next.getLatitude(), next.getLongitude());
                if (filter.transportMode() != null) {
                    double speedKmh = AVERAGE_SPEED_KMH.get(filter.transportMode());
                    estimatedTravelMinutes = (int) Math.round(distanceToNextKm / speedKmh * 60);
                }
            }

            if (filter.maxDistanceKm() != null && distanceToNextKm != null && distanceToNextKm > filter.maxDistanceKm()) {
                continue;
            }
            if (filter.maxTravelMinutes() != null && estimatedTravelMinutes != null
                    && estimatedTravelMinutes > filter.maxTravelMinutes()) {
                continue;
            }

            result.add(new AlternativeCandidate(
                    candidate.getId(), candidate.getGooglePlaceId(), candidate.getName(), candidate.getCategory(),
                    candidate.getRating(), candidate.getUserRatingCount(), candidate.getLatitude(), candidate.getLongitude(),
                    candidate.getAddress(), distanceToNextKm, estimatedTravelMinutes, false, null));
        }
        return Optional.of(result);
    }

    private TripPlace findNext(TripPlace target) {
        List<TripPlace> siblings = tripPlaceRepository.findByItineraryOrderByVisitOrder(target.getItinerary());
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getId().equals(target.getId())) {
                return i + 1 < siblings.size() ? siblings.get(i + 1) : null;
            }
        }
        return null;
    }

    private List<Place> filterIndoor(List<Place> candidates) {
        List<Place> needsTagging = candidates.stream().filter(p -> p.getSpace() == null).toList();
        if (!needsTagging.isEmpty()) {
            List<PlaceTaggingRunner.TagCandidate> tagCandidates = new ArrayList<>();
            for (int i = 0; i < needsTagging.size(); i++) {
                Place p = needsTagging.get(i);
                tagCandidates.add(new PlaceTaggingRunner.TagCandidate(
                        i, p.getName(), p.getCategory(), p.getRating(), p.getUserRatingCount(), p.getPriceLevel()));
            }
            List<PlaceTag> tags = placeTaggingRunner.run(tagCandidates, System.nanoTime());
            Map<Integer, PlaceTag> tagByIndex = tags.stream().collect(Collectors.toMap(PlaceTag::index, t -> t));
            for (int i = 0; i < needsTagging.size(); i++) {
                PlaceTag tag = tagByIndex.get(i);
                if (tag != null) {
                    needsTagging.get(i).applySpaceTag(tag.space());
                }
            }
        }
        return candidates.stream().filter(p -> "INDOOR".equals(p.getSpace())).toList();
    }

    private boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle.toLowerCase());
    }

    private static final double EARTH_RADIUS_KM = 6371.0;

    private double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }
}
```

`Place` 엔티티에 `applySpace(String)` setter가 이미 있는지 확인한다(`WeatherRecoveryService`가 쓰는 `TripPlace.applySpace`와 이름이 겹치지 않게, `Place` 쪽 메서드명을 실제 코드에서 확인 후 맞춰 쓴다 — 없으면 이 태스크에서 `Place.applySpaceTag(String space)` 세터를 추가한다. `src/main/java/com/trova/backend/entity/Place.java`에 `mood`/`space` 필드가 이미 있으므로 다음 메서드만 추가):

```java
    public void applySpaceTag(String space) {
        this.space = space;
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.recommendation.AlternativeFinderServiceTest"`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/trova/backend/recommendation/AlternativeFinderService.java src/main/java/com/trova/backend/entity/Place.java src/test/java/com/trova/backend/recommendation/AlternativeFinderServiceTest.java
git commit -m "feat: 필터 기반 대안 장소 검색 서비스 추가"
```

---

### Task 4: 서울 혼잡도 API 연동

**Files:**
- Create: `src/main/java/com/trova/backend/congestion/SeoulCongestionApiClient.java`
- Create: `src/main/java/com/trova/backend/congestion/SeoulCongestionApiClientImpl.java`
- Create: `src/main/java/com/trova/backend/congestion/SeoulCongestionResponse.java`
- Create: `src/main/java/com/trova/backend/congestion/SeoulCongestionAreaCache.java`
- Modify: `src/main/java/com/trova/backend/recommendation/AlternativeFinderService.java`
- Modify: `src/main/resources/application.yml`
- Modify: `.env.example`
- Test: `src/test/java/com/trova/backend/congestion/SeoulCongestionAreaCacheTest.java`

**Interfaces:**
- Consumes: 없음(외부 API 신규 연동)
- Produces: `SeoulCongestionApiClient.fetchCongestion(String areaName) -> Optional<SeoulCongestionResponse>`, `SeoulCongestionAreaCache.isKnownArea(String placeName) -> boolean`

- [ ] **Step 1: `application.yml`/`.env.example`에 키 추가**

`.env.example`에 추가:

```
SEOUL_OPENDATA_API_KEY=
```

`application.yml`에 추가(다른 외부 키들 옆에):

```yaml
app:
  congestion:
    seoul-opendata-api-key: ${SEOUL_OPENDATA_API_KEY:}
```

- [ ] **Step 2: 응답 레코드 작성**

`SeoulCongestionResponse.java` — 서울시 "실시간 도시데이터"(citydata) API는
`http://openapi.seoul.go.kr:8088/{키}/json/citydata/1/5/{장소명}` 형태로 호출하면
`CITYDATA.LIVE_PPLTN_STTS[0].AREA_CONGEST_LVL`(여유/보통/약간 붐빔/붐잡)에
혼잡도 문자열이 들어있다. **주의: 이 필드명은 공개 문서 기준이며, 실제 응답을
한 번 curl로 확인해서 정확한 키 이름을 검증한 뒤 아래 레코드를 맞춰야 한다**
(2번째 스텝에서 검증):

```java
package com.trova.backend.congestion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SeoulCongestionResponse(@JsonProperty("CITYDATA") CityData cityData) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CityData(@JsonProperty("LIVE_PPLTN_STTS") List<LivePopulation> livePopulation) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LivePopulation(
            @JsonProperty("AREA_CONGEST_LVL") String areaCongestLevel,
            @JsonProperty("AREA_CONGEST_MSG") String areaCongestMessage
    ) {
    }
}
```

- [ ] **Step 2.5: 실제 API로 필드명 검증(수동 스텝, 코드 아님)**

`.env`에 발급받은 `SEOUL_OPENDATA_API_KEY`를 넣고 다음을 실행:

```bash
curl "http://openapi.seoul.go.kr:8088/${SEOUL_OPENDATA_API_KEY}/json/citydata/1/5/광화문·덕수궁"
```

응답 JSON을 열어서 `CITYDATA`/`LIVE_PPLTN_STTS`/`AREA_CONGEST_LVL` 키 이름이 실제
응답과 일치하는지 확인한다. 다르면 Step 2의 레코드를 실제 키 이름으로 고친다.

- [ ] **Step 3: 클라이언트 인터페이스/구현**

```java
package com.trova.backend.congestion;

import java.util.Optional;

public interface SeoulCongestionApiClient {
    Optional<SeoulCongestionResponse> fetchCongestion(String areaName);
}
```

```java
package com.trova.backend.congestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;

/**
 * 서울 열린데이터광장 "서울시 실시간 도시데이터"(citydata) API — 서울 116~121곳
 * 주요 명소만 커버한다. 실패해도 대안 찾기 전체를 막지 않고 조용히 빈 값을
 * 반환한다(WeatherRecoveryService의 findIndoorAlternatives가 쓰던 것과 같은
 * "부가 기능은 실패해도 무시" 원칙).
 */
@Component
public class SeoulCongestionApiClientImpl implements SeoulCongestionApiClient {

    private static final Logger log = LoggerFactory.getLogger(SeoulCongestionApiClientImpl.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final String apiKey;

    public SeoulCongestionApiClientImpl(@Value("${app.congestion.seoul-opendata-api-key}") String apiKey) {
        this.apiKey = apiKey;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .baseUrl("http://openapi.seoul.go.kr:8088")
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public Optional<SeoulCongestionResponse> fetchCongestion(String areaName) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        try {
            SeoulCongestionResponse response = restClient.get()
                    .uri("/{key}/json/citydata/1/5/{areaName}", apiKey, areaName)
                    .retrieve()
                    .body(SeoulCongestionResponse.class);
            return Optional.ofNullable(response);
        } catch (Exception e) {
            log.warn("서울 혼잡도 API 조회 실패({}) — 배지 없이 진행", areaName, e);
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 4: 장소명 캐시(대안 검색이 매치 안 되는 후보까지 API를 부르지 않게)**

이 스펙에서 캐시는 "서울 주요 116~121곳 장소명 목록"을 뜻하는데, 그 목록 자체를
내려주는 별도 엔드포인트가 있는 게 아니라 citydata API에 등록된 고정 장소명
목록(공식 문서에 나열됨)이다. 목록을 코드에 상수로 박아두고, 매치되는 후보만
`fetchCongestion`을 호출한다 — 광화문·덕수궁, 명동 관광특구, 이태원 관광특구,
동대문 관광특구, 잠실 관광특구, 강남역, 홍대 관광특구 등 116~121개 전체를
넣는 건 이 태스크 범위를 벗어나므로, 우선 자주 언급되는 30개만 넣고
`// TODO 아님 — 나머지는 공식 목록 확인 후 추가` 같은 표현 대신, 정확히
"이 목록은 전체가 아니라 일부이며 확장 가능"이라고 주석에 명시한다:

```java
package com.trova.backend.congestion;

import java.util.Set;

/**
 * 서울시 "실시간 도시데이터"가 커버하는 장소명 목록의 일부(자주 여행지로 쓰이는
 * 30곳만 우선 등록). 전체 116~121곳 목록은 서울 열린데이터광장 공식 문서에
 * 있고, 필요해지면 이 Set에 추가하면 된다 — 코드 구조 변경 없음.
 */
public final class SeoulCongestionAreaCache {

    private static final Set<String> KNOWN_AREAS = Set.of(
            "광화문·덕수궁", "명동 관광특구", "이태원 관광특구", "동대문 관광특구",
            "잠실 관광특구", "강남역", "홍대 관광특구", "경복궁", "북촌한옥마을",
            "인사동", "여의도한강공원", "반포한강공원", "뚝섬한강공원", "잠실한강공원",
            "노량진", "남산공원", "서울숲공원", "청계천", "종로·청계 관광특구",
            "가로수길", "압구정로데오거리", "성수카페거리", "익선동", "서촌", "삼청동",
            "롯데월드타워 및 롯데월드몰", "DDP(동대문디자인플라자)", "여의도", "신촌·이대", "건대입구"
    );

    private SeoulCongestionAreaCache() {
    }

    public static boolean isKnownArea(String placeName) {
        return placeName != null && KNOWN_AREAS.contains(placeName.trim());
    }
}
```

- [ ] **Step 5: 실패하는 테스트 작성**

`src/test/java/com/trova/backend/congestion/SeoulCongestionAreaCacheTest.java`:

```java
package com.trova.backend.congestion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeoulCongestionAreaCacheTest {

    @Test
    void 등록된_장소명은_true를_반환한다() {
        assertThat(SeoulCongestionAreaCache.isKnownArea("경복궁")).isTrue();
    }

    @Test
    void 등록_안된_장소명은_false를_반환한다() {
        assertThat(SeoulCongestionAreaCache.isKnownArea("아무개카페")).isFalse();
    }

    @Test
    void null은_false를_반환한다() {
        assertThat(SeoulCongestionAreaCache.isKnownArea(null)).isFalse();
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.congestion.SeoulCongestionAreaCacheTest"`
Expected: PASS (3 tests)

- [ ] **Step 7: `AlternativeFinderService`에 혼잡도 연결**

생성자에 `SeoulCongestionApiClient`를 주입받고, 후보 생성 루프에서 `SeoulCongestionAreaCache.isKnownArea(candidate.getName())`이 true일 때만 `seoulCongestionApiClient.fetchCongestion(candidate.getName())`을 호출해 `isCongestionAvailable`/`congestionLevel`을 채운다:

```java
    // 생성자 파라미터에 SeoulCongestionApiClient seoulCongestionApiClient 추가, 필드도 추가

            boolean congestionAvailable = false;
            String congestionLevel = null;
            if (SeoulCongestionAreaCache.isKnownArea(candidate.getName())) {
                Optional<SeoulCongestionResponse> congestion = seoulCongestionApiClient.fetchCongestion(candidate.getName());
                if (congestion.isPresent() && congestion.get().cityData() != null
                        && congestion.get().cityData().livePopulation() != null
                        && !congestion.get().cityData().livePopulation().isEmpty()) {
                    congestionAvailable = true;
                    congestionLevel = congestion.get().cityData().livePopulation().get(0).areaCongestLevel();
                }
            }

            result.add(new AlternativeCandidate(
                    candidate.getId(), candidate.getGooglePlaceId(), candidate.getName(), candidate.getCategory(),
                    candidate.getRating(), candidate.getUserRatingCount(), candidate.getLatitude(), candidate.getLongitude(),
                    candidate.getAddress(), distanceToNextKm, estimatedTravelMinutes, congestionAvailable, congestionLevel));
```

(`import com.trova.backend.congestion.SeoulCongestionApiClient;`,
`import com.trova.backend.congestion.SeoulCongestionAreaCache;`,
`import com.trova.backend.congestion.SeoulCongestionResponse;` 추가.
Task 3의 `AlternativeFinderServiceTest`는 새 생성자 파라미터 때문에 컴파일이
깨지므로 `@Mock private SeoulCongestionApiClient seoulCongestionApiClient;`를
추가하고, `SeoulCongestionAreaCache.isKnownArea`가 테스트에 쓰인 장소명들
("대안카페", "가까운곳" 등)에 전부 false를 반환하므로 기존 테스트 동작은
그대로 유지된다 — 별도 stub 불필요.)

- [ ] **Step 8: 전체 컴파일 + 테스트**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/trova/backend/congestion/ src/main/java/com/trova/backend/recommendation/AlternativeFinderService.java src/main/resources/application.yml .env.example src/test/java/com/trova/backend/congestion/
git commit -m "feat: 서울 실시간 도시데이터로 대안 후보 혼잡도 배지 추가"
```

---

### Task 5: `GET /api/trip-places/{id}/alternatives` 엔드포인트

**Files:**
- Modify: `src/main/java/com/trova/backend/controller/TripController.java`
- Test: `src/test/java/com/trova/backend/controller/TripControllerTest.java`

**Interfaces:**
- Consumes: `AlternativeFinderService.findAlternatives(User, Long, AlternativeFilter)`(Task 4)
- Produces: `GET /api/trip-places/{id}/alternatives?category=&indoor=&maxDistanceKm=&maxTravelMinutes=&transportMode=` → `200 List<AlternativeCandidateResponse>` 또는 `404`

- [ ] **Step 1: 실패하는 테스트 작성**

`TripControllerTest.java`에 추가(기존 `trip(User, int)`/`tripPlace(...)` 헬퍼 재사용):

```java
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
```

이 테스트는 실제 구글 Places API를 호출하므로, `TripControllerTest`에
`@MockitoBean private GooglePlacesApiClient googlePlacesApiClient;`를 추가하고
`when(googlePlacesApiClient.searchNearby(anyDouble(), anyDouble(), anyDouble(), any()))`
`.thenReturn(new GooglePlacesNearbySearchResponse(List.of()))`로 빈 결과를
스텁한다(클래스 상단 `@BeforeEach`나 각 테스트에서). `PlacesControllerTest`가
이미 `@MockitoBean`으로 외부 서비스를 목킹하는 동일 패턴을 쓰고 있으니 그대로
따른다.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.controller.TripControllerTest"`
Expected: FAIL — 404/엔드포인트 없음(405 또는 404)

- [ ] **Step 3: 컨트롤러에 엔드포인트 추가**

`TripController` 생성자에 `AlternativeFinderService alternativeFinderService` 주입 추가.
`TripPlaceResponse` record 근처에 새 record 추가:

```java
    public record AlternativeCandidateResponse(
            Long placeId, String googlePlaceId, String name, String category,
            Double rating, Integer userRatingCount, Double latitude, Double longitude, String address,
            Double distanceToNextKm, Integer estimatedTravelMinutes,
            Boolean isCongestionAvailable, String congestionLevel
    ) {
        static AlternativeCandidateResponse from(com.trova.backend.recommendation.AlternativeCandidate c) {
            return new AlternativeCandidateResponse(
                    c.placeId(), c.googlePlaceId(), c.name(), c.category(), c.rating(), c.userRatingCount(),
                    c.latitude(), c.longitude(), c.address(), c.distanceToNextKm(), c.estimatedTravelMinutes(),
                    c.isCongestionAvailable(), c.congestionLevel());
        }
    }
```

엔드포인트 추가(`weatherCheck` 메서드 근처):

```java
    @GetMapping("/api/trip-places/{id}/alternatives")
    public ResponseEntity<List<AlternativeCandidateResponse>> findAlternatives(
            Authentication authentication, @PathVariable Long id,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean indoor,
            @RequestParam(required = false) Double maxDistanceKm,
            @RequestParam(required = false) Integer maxTravelMinutes,
            @RequestParam(required = false) String transportMode
    ) {
        User user = currentUserService.resolve(authentication);
        TransportMode mode = null;
        if (transportMode != null) {
            try {
                mode = TransportMode.valueOf(transportMode);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }
        var filter = new com.trova.backend.recommendation.AlternativeFilter(
                category, indoor, maxDistanceKm, maxTravelMinutes, mode);
        return alternativeFinderService.findAlternatives(user, id, filter)
                .map(candidates -> ResponseEntity.ok(candidates.stream().map(AlternativeCandidateResponse::from).toList()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.controller.TripControllerTest"`
Expected: PASS (전체)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/trova/backend/controller/TripController.java src/test/java/com/trova/backend/controller/TripControllerTest.java
git commit -m "feat: 대안 찾기 API 엔드포인트 추가"
```

---

### Task 6: 장소 교체(`replace`) API

**Files:**
- Modify: `src/main/java/com/trova/backend/entity/TripPlace.java`
- Modify: `src/main/java/com/trova/backend/service/TripService.java`
- Modify: `src/main/java/com/trova/backend/controller/TripController.java`
- Test: `src/test/java/com/trova/backend/controller/TripControllerTest.java`

**Interfaces:**
- Consumes: `PlaceRepository.findByGooglePlaceId`(기존)
- Produces: `TripPlace.applyReplacement(Place)`, `TripService.replacePlace(User, Long tripPlaceId, String googlePlaceId) -> Optional<TripPlace>`, `POST /api/trip-places/{id}/replace`

- [ ] **Step 1: `TripPlace.applyReplacement` 추가**

`TripPlace.java`에서 `applyGooglePlaceId` 근처에 추가:

```java
    /**
     * 대안으로 교체한다 — 방문순서/시간/이동수단은 그대로 두고(같은 시간대에
     * 다른 곳을 가는 것뿐이라 여전히 유효), 메모는 원래 장소 기준으로 쓰였을
     * 가능성이 커서 비운다. region은 Place 카탈로그에 없는 필드라 null로 —
     * addPlaceToDay가 NORMAL 출처 장소를 만들 때와 동일한 규칙.
     */
    public void applyReplacement(Place newPlace) {
        this.placeName = newPlace.getName();
        this.region = null;
        this.category = newPlace.getCategory();
        this.latitude = newPlace.getLatitude();
        this.longitude = newPlace.getLongitude();
        this.address = newPlace.getAddress();
        this.googlePlaceId = newPlace.getGooglePlaceId();
        this.savedPlaceId = null;
        this.memo = null;
    }
```

`region`/`category`/`latitude`/`longitude`/`address`/`googlePlaceId`/`savedPlaceId`/`memo`
필드가 `private final`이 아니라 일반 `private`인지 확인한다(생성자에서만 초기화되고
`final`로 선언돼 있으면 컴파일 에러 — 기존 `TripPlace.java`를 열어서 확인, `final`이면
이 필드들에서만 제거).

- [ ] **Step 2: `TripService.replacePlace` 추가**

`TripService.java`에 `resolveDetailsPlace` 근처 추가:

```java
    @Transactional
    public Optional<TripPlace> replacePlace(User user, Long tripPlaceId, String googlePlaceId) {
        return tripPlaceRepository.findById(tripPlaceId)
                .filter(p -> p.getItinerary().getTrip().getUser().getId().equals(user.getId()))
                .flatMap(place -> placeRepository.findByGooglePlaceId(googlePlaceId).map(newPlace -> {
                    place.applyReplacement(newPlace);
                    return tripPlaceRepository.save(place);
                }));
    }
```

- [ ] **Step 3: 실패하는 컨트롤러 테스트 작성**

`TripControllerTest.java`에 추가:

```java
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
                .andExpect(jsonPath("$.visitStartTime").value("10:00:00"));

        assertThat(tripPlaceRepository.findById(place.getId()).orElseThrow().getMemo()).isNull();
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
```

`TripControllerTest`에 `@Autowired private PlaceRepository placeRepository;`와
`@Autowired private TripService tripService;`가 없으면 추가한다.

- [ ] **Step 4: 테스트 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.controller.TripControllerTest"`
Expected: FAIL — 엔드포인트 없음

- [ ] **Step 5: 컨트롤러 엔드포인트 추가**

```java
    public record ReplaceRequest(String googlePlaceId) {
    }

    @PostMapping("/api/trip-places/{id}/replace")
    public ResponseEntity<TripPlaceResponse> replacePlace(
            Authentication authentication, @PathVariable Long id, @RequestBody ReplaceRequest request
    ) {
        if (request == null || request.googlePlaceId() == null || request.googlePlaceId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        return tripService.replacePlace(user, id, request.googlePlaceId())
                .map(place -> ResponseEntity.ok(TripPlaceResponse.from(place)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
```

- [ ] **Step 6: 테스트 통과 확인 + 전체 빌드**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/trova/backend/entity/TripPlace.java src/main/java/com/trova/backend/service/TripService.java src/main/java/com/trova/backend/controller/TripController.java src/test/java/com/trova/backend/controller/TripControllerTest.java
git commit -m "feat: 여행 장소를 대안으로 교체하는 API 추가"
```

---

### Task 7: 빈 시간 감지 + 추천(`GapRecommendationService`)

**Files:**
- Create: `src/main/java/com/trova/backend/recommendation/GapRecommendationService.java`
- Modify: `src/main/java/com/trova/backend/controller/TripController.java`
- Test: `src/test/java/com/trova/backend/recommendation/GapRecommendationServiceTest.java`
- Test: `src/test/java/com/trova/backend/controller/TripControllerTest.java`

**Interfaces:**
- Consumes: `TripPlaceRepository.findByItineraryOrderByVisitOrder`(기존), `GooglePlacesApiClient.searchNearby`(Task 1), `PlaceCatalogService.upsertAll`(기존)
- Produces: `GapRecommendationService.findGaps(User user, Long tripId, int day) -> Optional<List<Gap>>`, `record Gap(Long beforePlaceId, Long afterPlaceId, int gapMinutes, List<AlternativeCandidate> recommendations)`, `GET /api/trips/{tripId}/days/{day}/gap-recommendations`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/trova/backend/recommendation/GapRecommendationServiceTest.java`:

```java
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
    void 30분_넘게_비면_gap으로_잡고_중간지점을_검색한다() {
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
    void 30분_이하로_비면_gap으로_안_잡는다() {
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
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.recommendation.GapRecommendationServiceTest"`
Expected: FAIL — 클래스 없음

- [ ] **Step 3: 구현**

```java
package com.trova.backend.recommendation;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 시간이 입력된 연속 장소 사이에 30분 넘는 공백이 있으면, 두 장소의 중간지점
 * 기준으로 갈 만한 곳을 추천한다. 시간이 하나라도 비어있는 쌍은 건너뛴다 —
 * 임의로 시간을 추정하지 않는다.
 */
@Service
public class GapRecommendationService {

    private static final Duration GAP_THRESHOLD = Duration.ofMinutes(30);
    private static final double SEARCH_RADIUS_METERS = 1500;

    public record Gap(Long beforePlaceId, Long afterPlaceId, int gapMinutes, List<AlternativeCandidate> recommendations) {
    }

    private final TripRepository tripRepository;
    private final ItineraryRepository itineraryRepository;
    private final TripPlaceRepository tripPlaceRepository;
    private final GooglePlacesApiClient googlePlacesApiClient;
    private final PlaceCatalogService placeCatalogService;

    public GapRecommendationService(
            TripRepository tripRepository, ItineraryRepository itineraryRepository,
            TripPlaceRepository tripPlaceRepository, GooglePlacesApiClient googlePlacesApiClient,
            PlaceCatalogService placeCatalogService
    ) {
        this.tripRepository = tripRepository;
        this.itineraryRepository = itineraryRepository;
        this.tripPlaceRepository = tripPlaceRepository;
        this.googlePlacesApiClient = googlePlacesApiClient;
        this.placeCatalogService = placeCatalogService;
    }

    public Optional<List<Gap>> findGaps(User user, Long tripId, int day) {
        return tripRepository.findById(tripId)
                .filter(trip -> trip.getUser().getId().equals(user.getId()))
                .flatMap(trip -> itineraryRepository.findByTripAndDay(trip, day))
                .map(this::computeGaps);
    }

    private List<Gap> computeGaps(Itinerary itinerary) {
        List<TripPlace> places = tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary);
        List<Gap> gaps = new ArrayList<>();

        for (int i = 0; i < places.size() - 1; i++) {
            TripPlace before = places.get(i);
            TripPlace after = places.get(i + 1);
            LocalTime endTime = before.getVisitEndTime();
            LocalTime startTime = after.getVisitStartTime();
            if (endTime == null || startTime == null) {
                continue;
            }
            Duration gap = Duration.between(endTime, startTime);
            if (gap.compareTo(GAP_THRESHOLD) <= 0) {
                continue;
            }
            if (before.getLatitude() == null || before.getLongitude() == null
                    || after.getLatitude() == null || after.getLongitude() == null) {
                continue;
            }

            double midLat = (before.getLatitude() + after.getLatitude()) / 2;
            double midLng = (before.getLongitude() + after.getLongitude()) / 2;
            // 검색 실패를 500으로 흘려보내지 않는다 — AlternativeFinderService와 같은 원칙.
            GooglePlacesNearbySearchResponse response;
            try {
                response = googlePlacesApiClient.searchNearby(midLat, midLng, SEARCH_RADIUS_METERS, null);
            } catch (Exception e) {
                response = new GooglePlacesNearbySearchResponse(List.of());
            }
            List<GooglePlacesNearbySearchResponse.Place> raw =
                    response.places() != null ? response.places() : List.of();
            List<Place> candidates = raw.isEmpty() ? List.of() : placeCatalogService.upsertAll(raw);

            List<AlternativeCandidate> recommendations = candidates.stream()
                    .map(c -> new AlternativeCandidate(
                            c.getId(), c.getGooglePlaceId(), c.getName(), c.getCategory(), c.getRating(),
                            c.getUserRatingCount(), c.getLatitude(), c.getLongitude(), c.getAddress(),
                            null, null, false, null))
                    .toList();

            gaps.add(new Gap(before.getId(), after.getId(), (int) gap.toMinutes(), recommendations));
        }
        return gaps;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.recommendation.GapRecommendationServiceTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: 컨트롤러 엔드포인트 추가 + 실패하는 테스트**

`TripControllerTest.java`에 추가:

```java
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
```

`TripController` 생성자에 `GapRecommendationService gapRecommendationService` 주입 추가.
`AlternativeCandidateResponse` 근처에 추가:

```java
    public record GapResponse(
            Long beforePlaceId, Long afterPlaceId, int gapMinutes, List<AlternativeCandidateResponse> recommendations
    ) {
        static GapResponse from(GapRecommendationService.Gap gap) {
            return new GapResponse(
                    gap.beforePlaceId(), gap.afterPlaceId(), gap.gapMinutes(),
                    gap.recommendations().stream().map(AlternativeCandidateResponse::from).toList());
        }
    }

    @GetMapping("/api/trips/{tripId}/days/{day}/gap-recommendations")
    public ResponseEntity<List<GapResponse>> gapRecommendations(
            Authentication authentication, @PathVariable Long tripId, @PathVariable int day
    ) {
        User user = currentUserService.resolve(authentication);
        return gapRecommendationService.findGaps(user, tripId, day)
                .map(gaps -> ResponseEntity.ok(gaps.stream().map(GapResponse::from).toList()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
```

(`import com.trova.backend.recommendation.GapRecommendationService;` 추가)

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/trova/backend/recommendation/GapRecommendationService.java src/main/java/com/trova/backend/controller/TripController.java src/test/java/com/trova/backend/recommendation/GapRecommendationServiceTest.java src/test/java/com/trova/backend/controller/TripControllerTest.java
git commit -m "feat: 빈 시간 대안 추천 API 추가"
```

---

### Task 8: 빈 시간 자리에 장소 중간 삽입(`insertPlaceAfter`)

**Files:**
- Modify: `src/main/java/com/trova/backend/service/TripService.java`
- Modify: `src/main/java/com/trova/backend/controller/TripController.java`
- Test: `src/test/java/com/trova/backend/controller/TripControllerTest.java`

**Interfaces:**
- Consumes: `TripPlaceRepository.findByItineraryOrderByVisitOrder`(기존), `PlaceRepository.findByGooglePlaceId`(기존)
- Produces: `TripService.insertPlaceAfter(User, Long afterTripPlaceId, String googlePlaceId) -> Optional<TripPlace>`, `POST /api/trip-places/insert`

- [ ] **Step 1: 실패하는 테스트 작성**

`TripControllerTest.java`에 추가:

```java
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
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.controller.TripControllerTest"`
Expected: FAIL — 엔드포인트 없음

- [ ] **Step 3: `TripService.insertPlaceAfter` 구현**

```java
    @Transactional
    public Optional<TripPlace> insertPlaceAfter(User user, Long afterTripPlaceId, String googlePlaceId) {
        return tripPlaceRepository.findById(afterTripPlaceId)
                .filter(p -> p.getItinerary().getTrip().getUser().getId().equals(user.getId()))
                .flatMap(after -> placeRepository.findByGooglePlaceId(googlePlaceId).map(newPlace -> {
                    List<TripPlace> siblings =
                            tripPlaceRepository.findByItineraryOrderByVisitOrder(after.getItinerary());
                    for (TripPlace sibling : siblings) {
                        if (sibling.getVisitOrder() > after.getVisitOrder()) {
                            sibling.applyVisitOrder(sibling.getVisitOrder() + 1);
                            tripPlaceRepository.save(sibling);
                        }
                    }
                    TripPlace inserted = new TripPlace(
                            after.getItinerary(), newPlace.getName(), null, newPlace.getCategory(),
                            newPlace.getLatitude(), newPlace.getLongitude(), null, newPlace.getAddress(),
                            after.getVisitOrder() + 1, PlaceSource.NORMAL, null);
                    inserted.applyGooglePlaceId(newPlace.getGooglePlaceId());
                    return tripPlaceRepository.save(inserted);
                }));
    }
```

- [ ] **Step 4: 컨트롤러 엔드포인트 추가**

```java
    public record InsertRequest(Long afterTripPlaceId, String googlePlaceId) {
    }

    @PostMapping("/api/trip-places/insert")
    public ResponseEntity<TripPlaceResponse> insertPlace(
            Authentication authentication, @RequestBody InsertRequest request
    ) {
        if (request == null || request.afterTripPlaceId() == null
                || request.googlePlaceId() == null || request.googlePlaceId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        return tripService.insertPlaceAfter(user, request.afterTripPlaceId(), request.googlePlaceId())
                .map(place -> ResponseEntity.ok(TripPlaceResponse.from(place)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
```

- [ ] **Step 5: 테스트 통과 확인 + 전체 빌드**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/trova/backend/service/TripService.java src/main/java/com/trova/backend/controller/TripController.java src/test/java/com/trova/backend/controller/TripControllerTest.java
git commit -m "feat: 빈 시간 추천 장소를 일정 중간에 삽입하는 API 추가"
```

---

### Task 9: 날씨 알림을 대안 찾기로 연결(`Notification` 단순화)

**Files:**
- Modify: `src/main/java/com/trova/backend/entity/Notification.java`
- Delete: `src/main/java/com/trova/backend/entity/NotificationAlternative.java`
- Modify: `src/main/java/com/trova/backend/service/WeatherRecoveryService.java`
- Modify: `src/main/java/com/trova/backend/controller/NotificationController.java`
- Test: 기존 `WeatherRecoveryService`/`NotificationController` 테스트가 있으면 수정, 없으면 컨트롤러 테스트 신규 작성

**Interfaces:**
- Consumes: 없음
- Produces: `Notification` 생성자가 `(User, Itinerary, String title, String body, Double precipitationProb, Long tripPlaceId)`로 변경. `NotificationController.NotificationResponse`에 `tripPlaceId` 필드, `alternatives` 필드 제거.

- [ ] **Step 1: 기존 테스트 파일 확인**

Run: `find src/test -iname "*Notification*" -o -iname "*WeatherRecovery*"`
Expected: 있으면 이후 스텝에서 같이 고친다. 이 플랜 작성 시점 기준으로는 존재하지 않는 것으로 확인됨(신규 테스트만 추가) — 실행해서 실제로 없는지 재확인하고, 있으면 그 파일도 함께 수정 대상에 넣는다.

- [ ] **Step 2: `Notification` 엔티티 수정**

`Notification.java` 전체를 아래로 교체:

```java
package com.trova.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 날씨 체크 결과로 생긴 인앱 알림. 폴링으로만 확인한다(푸시/이메일 없음 — Trova는
 * 웹이라 모바일 푸시 인프라가 없어서 0-1 원칙대로 Plan B와 다르게 감).
 *
 * 대안 장소는 더 이상 알림에 미리 계산해서 담아두지 않는다 — tripPlaceId로 그
 * 장소의 대안 찾기 화면(GET /api/trip-places/{id}/alternatives)을 직접 연다.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(optional = false)
    @JoinColumn(name = "itinerary_id", nullable = false)
    private Itinerary itinerary;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @Column(name = "precipitation_prob", nullable = false)
    private Double precipitationProb;

    @Column(name = "trip_place_id", nullable = false)
    private Long tripPlaceId;

    @Column(name = "is_read", nullable = false)
    private boolean isRead;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Notification() {
    }

    public Notification(
            User user, Itinerary itinerary, String title, String body,
            Double precipitationProb, Long tripPlaceId
    ) {
        this.user = user;
        this.itinerary = itinerary;
        this.title = title;
        this.body = body;
        this.precipitationProb = precipitationProb;
        this.tripPlaceId = tripPlaceId;
        this.isRead = false;
        this.createdAt = LocalDateTime.now();
    }

    public void markRead() {
        this.isRead = true;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public Itinerary getItinerary() { return itinerary; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public Double getPrecipitationProb() { return precipitationProb; }
    public Long getTripPlaceId() { return tripPlaceId; }
    public boolean isRead() { return isRead; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 3: `NotificationAlternative.java` 삭제**

```bash
git rm src/main/java/com/trova/backend/entity/NotificationAlternative.java
```

- [ ] **Step 4: `WeatherRecoveryService` 단순화**

`checkAndNotify`에서 `findIndoorAlternatives(reference)` 호출과 `List<NotificationAlternative> alternatives` 변수를 제거하고, `Notification` 생성 인자를 `reference.getId()`로 바꾼다. `findIndoorAlternatives` 메서드 전체와, 이제 안 쓰는 `kakaoLocalApiClient`/`KakaoKeywordSearchResponse` import를 제거한다. `KakaoLocalApiClient` 필드/생성자 파라미터도 이 서비스에서만 제거(다른 서비스는 계속 씀):

```java
        List<TripPlace> outdoor = places.stream()
                .filter(p -> "OUTDOOR".equals(p.getSpace()))
                .filter(p -> p.getLatitude() != null && p.getLongitude() != null)
                .toList();
        if (outdoor.isEmpty()) {
            return Optional.empty();
        }

        TripPlace reference = outdoor.get(0);
        OpenWeatherForecastResponse forecast =
                openWeatherApiClient.forecast(reference.getLatitude(), reference.getLongitude());
        double maxPop = maxPopForDate(forecast, itinerary.getDate());
        if (maxPop < RAIN_PROBABILITY_THRESHOLD) {
            return Optional.empty();
        }

        Notification notification = new Notification(
                itinerary.getTrip().getUser(), itinerary, "비 소식이 있어요",
                String.format(
                        "%d일차(%s)에 강수확률 %.0f%%예요. %s 근처 실내 대안을 확인해보세요.",
                        itinerary.getDay(), itinerary.getDate(), maxPop * 100, reference.getPlaceName()),
                maxPop, reference.getId());
        return Optional.of(notificationRepository.save(notification));
    }
```

생성자와 필드에서 `KakaoLocalApiClient kakaoLocalApiClient` 제거, `MAX_ALTERNATIVES` 상수 제거, `findIndoorAlternatives` 메서드 전체 삭제, `import com.trova.backend.geocoding.KakaoKeywordSearchResponse;`/`import com.trova.backend.geocoding.KakaoLocalApiClient;`/`import com.trova.backend.entity.NotificationAlternative;` 제거.

- [ ] **Step 5: `NotificationController` 응답 변경**

```java
    public record NotificationResponse(
            Long id, Long tripId, Integer day, String title, String body,
            Double precipitationProb, Long tripPlaceId, String createdAt
    ) {
        static NotificationResponse from(Notification n) {
            return new NotificationResponse(
                    n.getId(), n.getItinerary().getTrip().getId(), n.getItinerary().getDay(),
                    n.getTitle(), n.getBody(), n.getPrecipitationProb(), n.getTripPlaceId(),
                    n.getCreatedAt().toString());
        }
    }
```

`AlternativeResponse` record와 `import java.util.List;`가 다른 곳에서 안 쓰이면 제거(컴파일러가
unused import를 에러로 잡지는 않지만 정리 차원 — CLAUDE.md 코드 스타일).

- [ ] **Step 6: 컴파일 + 전체 테스트**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. 실패하면 `WeatherRecoveryService`/`NotificationController`를 참조하는 다른 코드(예: 스케줄러)가 옛 생성자/필드를 쓰고 있는지 확인하고 맞춰 고친다.

- [ ] **Step 7: DB에서 옛 테이블 정리(로컬/개발 환경, 수동)**

`ddl-auto: update`는 컬럼 삭제(alternatives 필드 제거)나 테이블 삭제를 자동으로 안 한다.
로컬 Postgres(Supabase)에 접속해서 수동으로 실행:

```sql
DROP TABLE IF EXISTS notification_alternatives;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS trip_place_id BIGINT;
-- 기존 알림 로우가 있다면 trip_place_id가 비어서 NOT NULL 제약을 못 거는 경우, 먼저 지운다:
DELETE FROM notifications WHERE trip_place_id IS NULL;
ALTER TABLE notifications ALTER COLUMN trip_place_id SET NOT NULL;
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/trova/backend/entity/Notification.java src/main/java/com/trova/backend/service/WeatherRecoveryService.java src/main/java/com/trova/backend/controller/NotificationController.java
git rm src/main/java/com/trova/backend/entity/NotificationAlternative.java
git commit -m "feat: 날씨 알림을 대안 찾기 화면으로 연결(자체 대안 계산 제거)"
```

---

### Task 10: 앱 API 클라이언트 함수 추가

**Files:**
- Modify: `/Users/gimtaehyeong/Desktop/trova-app/src/lib/api/trips.ts`
- Create: `/Users/gimtaehyeong/Desktop/trova-app/src/lib/api/notifications.ts`

**Interfaces:**
- Consumes: 없음(백엔드 API 호출부만)
- Produces:
  - `getAlternatives(tripPlaceId: number, filter: AlternativeFilter) -> Promise<AlternativeCandidate[]>`
  - `replacePlace(tripPlaceId: number, googlePlaceId: string) -> Promise<TripPlace>`
  - `getGapRecommendations(tripId: number, day: number) -> Promise<Gap[]>`
  - `insertPlaceAfter(afterTripPlaceId: number, googlePlaceId: string) -> Promise<TripPlace>`
  - `listNotifications() -> Promise<Notification[]>`, `dismissNotification(id: number) -> Promise<void>`

- [ ] **Step 1: `trips.ts`에 타입 + 함수 추가**

`trips.ts` 끝에 추가:

```typescript
export type AlternativeFilter = {
  category?: string;
  indoor?: boolean;
  maxDistanceKm?: number;
  maxTravelMinutes?: number;
  transportMode?: "WALK" | "TRANSIT" | "CAR";
};

export type AlternativeCandidate = {
  placeId: number;
  googlePlaceId: string;
  name: string;
  category: string | null;
  rating: number | null;
  userRatingCount: number | null;
  latitude: number;
  longitude: number;
  address: string | null;
  distanceToNextKm: number | null;
  estimatedTravelMinutes: number | null;
  isCongestionAvailable: boolean;
  congestionLevel: string | null;
};

export type Gap = {
  beforePlaceId: number;
  afterPlaceId: number;
  gapMinutes: number;
  recommendations: AlternativeCandidate[];
};

export async function getAlternatives(tripPlaceId: number, filter: AlternativeFilter): Promise<AlternativeCandidate[]> {
  const params = new URLSearchParams();
  if (filter.category) params.set("category", filter.category);
  if (filter.indoor !== undefined) params.set("indoor", String(filter.indoor));
  if (filter.maxDistanceKm !== undefined) params.set("maxDistanceKm", String(filter.maxDistanceKm));
  if (filter.maxTravelMinutes !== undefined) params.set("maxTravelMinutes", String(filter.maxTravelMinutes));
  if (filter.transportMode) params.set("transportMode", filter.transportMode);

  const res = await apiFetch(`/api/trip-places/${tripPlaceId}/alternatives?${params.toString()}`);
  if (!res.ok) {
    throw new Error(`GET /api/trip-places/${tripPlaceId}/alternatives failed: ${res.status}`);
  }
  return res.json();
}

export async function replacePlace(tripPlaceId: number, googlePlaceId: string): Promise<TripPlace> {
  const res = await apiFetch(`/api/trip-places/${tripPlaceId}/replace`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ googlePlaceId }),
  });
  if (!res.ok) {
    throw new Error(`POST /api/trip-places/${tripPlaceId}/replace failed: ${res.status}`);
  }
  return res.json();
}

export async function getGapRecommendations(tripId: number, day: number): Promise<Gap[]> {
  const res = await apiFetch(`/api/trips/${tripId}/days/${day}/gap-recommendations`);
  if (!res.ok) {
    throw new Error(`GET /api/trips/${tripId}/days/${day}/gap-recommendations failed: ${res.status}`);
  }
  return res.json();
}

export async function insertPlaceAfter(afterTripPlaceId: number, googlePlaceId: string): Promise<TripPlace> {
  const res = await apiFetch(`/api/trip-places/insert`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ afterTripPlaceId, googlePlaceId }),
  });
  if (!res.ok) {
    throw new Error(`POST /api/trip-places/insert failed: ${res.status}`);
  }
  return res.json();
}
```

- [ ] **Step 2: `notifications.ts` 신규 작성**

```typescript
import { apiFetch } from "@/lib/api/client";

export type Notification = {
  id: number;
  tripId: number;
  day: number;
  title: string;
  body: string;
  precipitationProb: number;
  tripPlaceId: number;
  createdAt: string;
};

export async function listNotifications(): Promise<Notification[]> {
  const res = await apiFetch("/api/notifications");
  if (!res.ok) {
    throw new Error(`GET /api/notifications failed: ${res.status}`);
  }
  return res.json();
}

export async function dismissNotification(id: number): Promise<void> {
  const res = await apiFetch(`/api/notifications/${id}/dismiss`, { method: "POST" });
  if (!res.ok) {
    throw new Error(`POST /api/notifications/${id}/dismiss failed: ${res.status}`);
  }
}
```

- [ ] **Step 3: 타입체크**

Run: `cd /Users/gimtaehyeong/Desktop/trova-app && npx tsc --noEmit`
Expected: exit code 0

- [ ] **Step 4: Commit**

```bash
cd /Users/gimtaehyeong/Desktop/trova-app
git add src/lib/api/trips.ts src/lib/api/notifications.ts
git commit -m "feat: 대안 찾기/빈 시간/알림 API 클라이언트 함수 추가"
```

---

### Task 11: `AlternativeFinderSheet` 컴포넌트(필터 → 후보 → 미리보기 → 교체)

**Files:**
- Create: `/Users/gimtaehyeong/Desktop/trova-app/src/components/AlternativeFinderSheet.tsx`

**Interfaces:**
- Consumes: `getAlternatives`/`replacePlace`(Task 10), `PlaceReviewContent`(기존, `showMiniMap={false}` — 이 시트 자체에 미리보기 지도가 따로 있으므로 리뷰 안의 미니맵은 끈다), `InlineMap`(기존), `AppText`/`colors`(기존)
- Produces: `<AlternativeFinderSheet tripPlaceId={number|null} initialIndoor={boolean} onReplaced={() => void} onClose={() => void} />` — `tripPlaceId`가 null이면 닫힘. TripDetailScreen/날씨 배너 양쪽에서 재사용.

- [ ] **Step 1: 컴포넌트 작성**

`PlaceReviewModal.tsx`의 `PlaceReviewSheet`(이번 세션에서 만든, `BottomSheet`를 `index={-1}` 기본값으로 항상 마운트해두고 ref로 열고 닫는 패턴 — 전체화면 Modal과 달리 뒤 화면이 계속 터치되고, 살짝 내려도 선택 상태가 유지됨)와 정확히 같은 구조로 만든다:

```tsx
import { useEffect, useRef, useState } from "react";
import { Pressable, ScrollView, TextInput, View } from "react-native";
import BottomSheet, { BottomSheetScrollView } from "@gorhom/bottom-sheet";
import { useQueryClient } from "@tanstack/react-query";
import { AppText } from "@/components/AppText";
import { InlineMap } from "@/components/InlineMap";
import { PlaceReviewContent } from "@/components/PlaceReviewModal";
import { colors } from "@/lib/theme";
import {
  getAlternatives,
  replacePlace,
  type AlternativeCandidate,
  type AlternativeFilter,
} from "@/lib/api/trips";

const SNAP_POINTS = ["55%", "85%"];
const TRANSPORT_LABEL: Record<"WALK" | "TRANSIT" | "CAR", string> = {
  WALK: "도보",
  TRANSIT: "대중교통",
  CAR: "차량",
};

export function AlternativeFinderSheet({
  tripPlaceId,
  initialIndoor = false,
  onReplaced,
  onClose,
}: {
  tripPlaceId: number | null;
  initialIndoor?: boolean;
  onReplaced: () => void;
  onClose: () => void;
}) {
  const sheetRef = useRef<BottomSheet>(null);
  const queryClient = useQueryClient();
  const [category, setCategory] = useState("");
  const [indoor, setIndoor] = useState(initialIndoor);
  const [transportMode, setTransportMode] = useState<"WALK" | "TRANSIT" | "CAR" | null>(null);
  const [maxDistanceKm, setMaxDistanceKm] = useState("");
  const [candidates, setCandidates] = useState<AlternativeCandidate[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [selected, setSelected] = useState<AlternativeCandidate | null>(null);
  const [reviewCandidateId, setReviewCandidateId] = useState<number | null>(null);
  const [replacing, setReplacing] = useState(false);

  const isOpen = tripPlaceId !== null;

  useEffect(() => {
    if (isOpen) {
      setIndoor(initialIndoor);
      setCandidates(null);
      setSelected(null);
      sheetRef.current?.snapToIndex(0);
    } else {
      sheetRef.current?.close();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tripPlaceId]);

  async function handleSearch() {
    if (tripPlaceId === null || searching) return;
    setSearching(true);
    setSearchError(null);
    setSelected(null);
    try {
      const filter: AlternativeFilter = {
        category: category.trim() || undefined,
        indoor: indoor || undefined,
        maxDistanceKm: maxDistanceKm ? Number(maxDistanceKm) : undefined,
        transportMode: transportMode ?? undefined,
      };
      setCandidates(await getAlternatives(tripPlaceId, filter));
    } catch {
      setSearchError("대안을 찾지 못했어요. 다시 시도해주세요.");
    } finally {
      setSearching(false);
    }
  }

  async function handleReplace() {
    if (tripPlaceId === null || selected === null || replacing) return;
    setReplacing(true);
    try {
      await replacePlace(tripPlaceId, selected.googlePlaceId);
      await queryClient.invalidateQueries({ queryKey: ["trip"] });
      onReplaced();
    } catch {
      setSearchError("교체하지 못했어요. 다시 시도해주세요.");
    } finally {
      setReplacing(false);
    }
  }

  return (
    <BottomSheet ref={sheetRef} index={-1} snapPoints={SNAP_POINTS} enableDynamicSizing={false} onClose={onClose}>
      <BottomSheetScrollView contentContainerStyle={{ padding: 20, gap: 12 }}>
        <AppText weight="medium" style={{ fontSize: 16 }}>
          대안 찾기
        </AppText>

        <View style={{ flexDirection: "row", gap: 8 }}>
          <TextInput
            value={category}
            onChangeText={setCategory}
            placeholder="카테고리(예: 카페, 박물관)"
            style={{
              flex: 1,
              height: 40,
              borderWidth: 1,
              borderColor: colors.border,
              borderRadius: 10,
              paddingHorizontal: 12,
              fontFamily: "NotoSansKR_400Regular",
            }}
          />
          <Pressable
            onPress={() => setIndoor((v) => !v)}
            style={{
              paddingHorizontal: 12,
              height: 40,
              borderRadius: 10,
              justifyContent: "center",
              backgroundColor: indoor ? colors.accent : colors.bgMuted,
            }}
          >
            <AppText style={{ color: indoor ? "#fff" : colors.inkMuted, fontSize: 13 }}>실내만</AppText>
          </Pressable>
        </View>

        <View style={{ flexDirection: "row", gap: 8 }}>
          {(["WALK", "TRANSIT", "CAR"] as const).map((mode) => (
            <Pressable
              key={mode}
              onPress={() => setTransportMode((current) => (current === mode ? null : mode))}
              style={{
                paddingVertical: 6,
                paddingHorizontal: 12,
                borderRadius: 14,
                backgroundColor: transportMode === mode ? colors.accent : colors.bgMuted,
              }}
            >
              <AppText style={{ fontSize: 12, color: transportMode === mode ? "#fff" : colors.inkMuted }}>
                {TRANSPORT_LABEL[mode]}
              </AppText>
            </Pressable>
          ))}
          <TextInput
            value={maxDistanceKm}
            onChangeText={setMaxDistanceKm}
            placeholder="다음 장소까지 최대 거리(km)"
            keyboardType="numeric"
            style={{
              flex: 1,
              height: 32,
              borderWidth: 1,
              borderColor: colors.border,
              borderRadius: 8,
              paddingHorizontal: 10,
              fontSize: 12,
              fontFamily: "NotoSansKR_400Regular",
            }}
          />
        </View>

        <Pressable
          onPress={handleSearch}
          disabled={searching}
          style={{
            height: 44,
            borderRadius: 10,
            backgroundColor: colors.accent,
            justifyContent: "center",
            alignItems: "center",
            opacity: searching ? 0.6 : 1,
          }}
        >
          <AppText weight="medium" style={{ color: "#fff" }}>
            {searching ? "찾는 중..." : "대안 찾기"}
          </AppText>
        </Pressable>

        {searchError && <AppText style={{ color: colors.accent }}>{searchError}</AppText>}

        {candidates !== null && candidates.length === 0 && (
          <AppText style={{ color: colors.inkMuted, textAlign: "center", padding: 12 }}>
            조건에 맞는 대안을 찾지 못했어요.
          </AppText>
        )}

        {candidates?.map((candidate) => (
          <View
            key={candidate.placeId}
            style={{
              padding: 12,
              borderRadius: 12,
              borderWidth: selected?.placeId === candidate.placeId ? 2 : 1,
              borderColor: selected?.placeId === candidate.placeId ? colors.accent : colors.border,
              gap: 6,
            }}
          >
            <Pressable onPress={() => setSelected(candidate)}>
              <AppText weight="medium" numberOfLines={1}>
                {candidate.name}
              </AppText>
              <AppText style={{ fontSize: 12, color: colors.inkMuted }}>
                {[
                  candidate.category,
                  candidate.rating !== null ? `⭐ ${candidate.rating.toFixed(1)}` : null,
                  candidate.distanceToNextKm !== null ? `다음 장소까지 ${candidate.distanceToNextKm.toFixed(1)}km` : null,
                  candidate.estimatedTravelMinutes !== null ? `약 ${candidate.estimatedTravelMinutes}분` : null,
                  candidate.isCongestionAvailable ? `혼잡도: ${candidate.congestionLevel}` : null,
                ]
                  .filter(Boolean)
                  .join(" · ")}
              </AppText>
            </Pressable>
            <Pressable onPress={() => setReviewCandidateId(candidate.placeId)}>
              <AppText style={{ fontSize: 12, color: colors.accent }}>리뷰 보기</AppText>
            </Pressable>
          </View>
        ))}

        {selected && (
          <View style={{ gap: 8, marginTop: 8 }}>
            <AppText weight="medium" style={{ fontSize: 13 }}>
              미리보기
            </AppText>
            <InlineMap
              pins={[{ id: "selected", latitude: selected.latitude, longitude: selected.longitude, color: colors.accent }]}
              height={140}
              showPath={false}
            />
            <Pressable
              onPress={handleReplace}
              disabled={replacing}
              style={{
                height: 44,
                borderRadius: 10,
                backgroundColor: colors.accent,
                justifyContent: "center",
                alignItems: "center",
                opacity: replacing ? 0.6 : 1,
              }}
            >
              <AppText weight="medium" style={{ color: "#fff" }}>
                {replacing ? "교체 중..." : "이 장소로 교체"}
              </AppText>
            </Pressable>
          </View>
        )}

        {reviewCandidateId !== null && (
          <View style={{ marginTop: 8, borderTopWidth: 1, borderTopColor: colors.border, paddingTop: 12 }}>
            <PlaceReviewContent placeId={reviewCandidateId} showMiniMap={false} />
          </View>
        )}
      </BottomSheetScrollView>
    </BottomSheet>
  );
}
```

`InlineMap`의 `pins[].color` 타입이 `string`(옵셔널)이고 `id`가 `string`인지
기존 `InlineMap.tsx`를 열어서 정확히 확인하고 맞춘다(이번 세션에서 이미
`{id, latitude, longitude, color?}` 형태로 확장돼 있으므로 그대로 맞을 것).

- [ ] **Step 2: 타입체크**

Run: `npx tsc --noEmit`
Expected: exit code 0

- [ ] **Step 3: Commit**

```bash
git add src/components/AlternativeFinderSheet.tsx
git commit -m "feat: 대안 찾기 바텀시트 컴포넌트 추가"
```

---

### Task 12: `TripDetailScreen`에 대안 찾기 + 빈 시간 카드 연결

**Files:**
- Modify: `/Users/gimtaehyeong/Desktop/trova-app/src/screens/TripDetailScreen.tsx`
- Modify: `/Users/gimtaehyeong/Desktop/trova-app/src/components/PlaceRow.tsx`

**Interfaces:**
- Consumes: `AlternativeFinderSheet`(Task 11), `getGapRecommendations`/`insertPlaceAfter`(Task 10)
- Produces: `PlaceRow`에 `onFindAlternative?: () => void` prop 추가(있으면 ⋮ 버튼 렌더링)

- [ ] **Step 1: `PlaceRow`에 대안 찾기 진입점 추가**

`PlaceRow.tsx`의 props 타입에 `onFindAlternative?: () => void;` 추가하고,
`dragHandle` 렌더링 블록 앞(또는 옆)에 ⋮ 버튼을 추가한다 — `SavedPlacesScreen.tsx`에서
이미 쓴 "⋮ 더보기 메뉴" 진입점과 같은 문자(`⋮`), 같은 `hitSlop`:

```tsx
        {onFindAlternative && (
          <Pressable onPress={onFindAlternative} hitSlop={10} style={{ justifyContent: "center", paddingHorizontal: 4 }}>
            <AppText style={{ fontSize: 16, color: colors.inkMuted }}>⋮</AppText>
          </Pressable>
        )}
        {dragHandle && (
```

(기존 `{dragHandle && (...)}` 블록 바로 앞에 삽입 — 두 블록이 나란히 렌더링되어도
됨. `onFindAlternative`를 함수 파라미터 구조분해에도 추가.)

- [ ] **Step 2: `TripDetailScreen`에 상태 + 시트 연결**

`reviewTarget` 상태 근처에 추가:

```typescript
  const [alternativeTargetId, setAlternativeTargetId] = useState<number | null>(null);
  const gapRecommendationsQuery = useQuery({
    queryKey: ["gapRecommendations", tripId, currentActiveDay],
    queryFn: () => getGapRecommendations(tripId, currentActiveDay),
  });
```

`renderPlaceItem` 안의 `<PlaceRow ...>`에 `onFindAlternative={() => setAlternativeTargetId(place.id)}` prop 추가.

화면 트리 최하단(`<PlaceReviewSheet .../>` 옆, `</View>` 안)에 추가:

```tsx
    <AlternativeFinderSheet
      tripPlaceId={alternativeTargetId}
      onReplaced={() => {
        setAlternativeTargetId(null);
        reload();
      }}
      onClose={() => setAlternativeTargetId(null)}
    />
```

`import { AlternativeFinderSheet } from "@/components/AlternativeFinderSheet";`,
`import { getGapRecommendations, insertPlaceAfter, ... } from "@/lib/api/trips";` 추가
(기존 import 목록에 함수만 추가).

- [ ] **Step 3: 빈 시간 카드 렌더링**

`ItemSeparatorComponent`는 지금 고정 `<View style={{height:12}}/>`인데, 특정 두
장소 사이에만 빈 시간 카드를 넣어야 하므로 `DraggableFlatList`의
`ItemSeparatorComponent`는 함수형으로 바꾸지 않고(드래그 중 인덱스가 흔들려서
복잡해짐), 대신 `renderPlaceItem`이 반환하는 `<View>` 안에서 "이 장소 다음"
위치에 gap 카드를 조건부로 붙인다 — `renderPlaceItem` 리턴을 다음으로 교체:

```tsx
  function renderPlaceItem({ item: place, getIndex, drag, isActive }: RenderItemParams<TripPlace>) {
    const index = getIndex() ?? 0;
    const gap = (gapRecommendationsQuery.data ?? []).find((g) => g.beforePlaceId === place.id);
    return (
      <View style={{ opacity: isActive ? 0.9 : 1, gap: 12 }}>
        <PlaceRow
          place={place}
          index={index}
          isLast={index === places.length - 1}
          distanceKm={null}
          editable
          disabled={busy}
          color={dayColor}
          dragHandle={{ onPressIn: drag }}
          onPressInfo={() => setReviewTarget({ kind: "tripPlace", id: place.id })}
          onFindAlternative={() => setAlternativeTargetId(place.id)}
        >
          {/* 기존 시간/이동수단/메모 UI 그대로 유지 */}
        </PlaceRow>
        {gap && gap.recommendations.length > 0 && (
          <Pressable
            onPress={() => setGapCardFor(gap)}
            style={{
              marginLeft: 38,
              padding: 10,
              borderRadius: 10,
              borderWidth: 1,
              borderStyle: "dashed",
              borderColor: colors.border,
            }}
          >
            <AppText style={{ fontSize: 12, color: colors.accent }}>
              {gap.gapMinutes}분 비어요 — 이 사이 갈 곳 추천받기
            </AppText>
          </Pressable>
        )}
      </View>
    );
  }
```

(`{/* 기존 시간/이동수단/메모 UI 그대로 유지 */}` 부분은 실제로는 지금
`renderPlaceItem` 안에 이미 있는 시간/이동수단/메모 `<View>` 블록 전체를
그대로 옮겨 붙인다 — 삭제/신규 작성 아님, `<PlaceRow>` 여는 태그와 닫는
태그 사이 기존 내용을 그대로 두고 `onFindAlternative` prop만 추가하는 것.)

`gapCardFor` 상태와 그 값을 쓰는 작은 후보 목록 시트를 추가:

```typescript
  const [gapCardFor, setGapCardFor] = useState<Gap | null>(null);
```

화면 트리에 추가:

```tsx
    <Modal visible={gapCardFor !== null} transparent animationType="slide" onRequestClose={() => setGapCardFor(null)}>
      <Pressable style={{ flex: 1, backgroundColor: "rgba(0,0,0,0.3)", justifyContent: "flex-end" }} onPress={() => setGapCardFor(null)}>
        <Pressable
          style={{ maxHeight: "70%", backgroundColor: colors.bg, borderTopLeftRadius: 16, borderTopRightRadius: 16, padding: 16, gap: 10 }}
          onPress={(e) => e.stopPropagation()}
        >
          <AppText weight="medium">이 사이 갈 만한 곳</AppText>
          {gapCardFor?.recommendations.map((r) => (
            <Pressable
              key={r.placeId}
              onPress={async () => {
                if (!gapCardFor) return;
                await insertPlaceAfter(gapCardFor.beforePlaceId, r.googlePlaceId);
                setGapCardFor(null);
                await reload();
                await queryClient.invalidateQueries({ queryKey: ["gapRecommendations", tripId, currentActiveDay] });
              }}
              style={{ padding: 12, borderRadius: 10, borderWidth: 1, borderColor: colors.border }}
            >
              <AppText weight="medium" numberOfLines={1}>{r.name}</AppText>
              {r.address && <AppText style={{ fontSize: 12, color: colors.inkMuted }}>{r.address}</AppText>}
            </Pressable>
          ))}
        </Pressable>
      </Pressable>
    </Modal>
```

`import type { Gap } from "@/lib/api/trips";`(type-only import) 추가. 기존
`import { Platform, Pressable, TextInput, View } from "react-native";`에
`Modal`이 없으므로 `Modal`도 추가한다. 이 작은 확인용 모달은 지도가 뒤에
없어서(리스트만 있음) 기존 `DayPickerSheet`처럼 막는 `Modal`을 그대로 써도
문제없다(비차단 시트가 필요한 건 지도가 같이 있는 `AlternativeFinderSheet`뿐).

- [ ] **Step 4: 타입체크**

Run: `npx tsc --noEmit`
Expected: exit code 0

- [ ] **Step 5: Commit**

```bash
git add src/screens/TripDetailScreen.tsx src/components/PlaceRow.tsx
git commit -m "feat: 여행 상세에 대안 찾기와 빈 시간 추천 카드 연결"
```

---

### Task 13: `WeatherAlertBanner` 컴포넌트 + 홈/여행 상세 연결

**Files:**
- Create: `/Users/gimtaehyeong/Desktop/trova-app/src/components/WeatherAlertBanner.tsx`
- Modify: `/Users/gimtaehyeong/Desktop/trova-app/src/screens/HomeScreen.tsx`
- Modify: `/Users/gimtaehyeong/Desktop/trova-app/src/screens/TripDetailScreen.tsx`

**Interfaces:**
- Consumes: `listNotifications`/`dismissNotification`(Task 10)
- Produces: `<WeatherAlertBanner tripId={number|undefined} onOpenAlternative={(tripPlaceId: number) => void} />` — `tripId`를 주면 그 여행의 알림만, 안 주면(홈 탭) 전체 알림 중 첫 번째만 배너로 보여준다.

- [ ] **Step 1: 컴포넌트 작성**

```tsx
import { Pressable, View } from "react-native";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AppText } from "@/components/AppText";
import { colors } from "@/lib/theme";
import { dismissNotification, listNotifications } from "@/lib/api/notifications";

export function WeatherAlertBanner({
  tripId,
  onOpenAlternative,
}: {
  tripId?: number;
  // 홈 탭(tripId 없음)에서는 그 알림이 속한 여행으로 이동만 시키면 되고,
  // 여행 상세 화면(tripId 있음)에서는 바로 대안 찾기 시트를 열어야 해서
  // 어느 여행/어느 장소인지 둘 다 호출부에 넘겨준다.
  onOpenAlternative: (tripId: number, tripPlaceId: number) => void;
}) {
  const queryClient = useQueryClient();
  const notificationsQuery = useQuery({ queryKey: ["notifications"], queryFn: listNotifications });

  const notifications = (notificationsQuery.data ?? []).filter((n) => tripId === undefined || n.tripId === tripId);
  const target = notifications[0];
  if (!target) return null;

  async function handleDismiss() {
    await dismissNotification(target.id);
    await queryClient.invalidateQueries({ queryKey: ["notifications"] });
  }

  return (
    <Pressable
      onPress={() => onOpenAlternative(target.tripId, target.tripPlaceId)}
      style={{
        flexDirection: "row",
        alignItems: "center",
        gap: 8,
        padding: 12,
        borderRadius: 12,
        backgroundColor: colors.accentBg,
      }}
    >
      <AppText style={{ fontSize: 18 }}>☔</AppText>
      <View style={{ flex: 1 }}>
        <AppText weight="medium" style={{ fontSize: 13 }}>
          {target.title}
        </AppText>
        <AppText style={{ fontSize: 12, color: colors.inkMuted }} numberOfLines={2}>
          {target.body}
        </AppText>
      </View>
      <Pressable onPress={handleDismiss} hitSlop={10}>
        <AppText style={{ fontSize: 16, color: colors.inkMuted }}>✕</AppText>
      </Pressable>
    </Pressable>
  );
}
```

- [ ] **Step 2: `HomeScreen`에 연결**

홈 탭에서는 대안 찾기 시트를 바로 열지 않고 그 알림이 속한 여행 상세로
이동만 시킨다(대안 찾기 시트는 여행 상세 화면에만 있음) — 이동한 뒤 그
화면의 배너를 다시 탭하면 Step 3에서 시트가 열린다. `HomeScreen.tsx`의
인사말 바로 아래(URL 입력 카드 전)에 추가:

```tsx
        <WeatherAlertBanner
          onOpenAlternative={(tripId) => navigation.navigate("TripDetail", { id: tripId })}
        />
```

`import { WeatherAlertBanner } from "@/components/WeatherAlertBanner";` 추가.

- [ ] **Step 3: `TripDetailScreen`에 연결**

여기서는 이미 그 여행 화면 안이므로 탭하면 바로 `AlternativeFinderSheet`가
열리게 한다(Task 12에서 만든 `alternativeTargetId` 상태 재사용). `initialIndoor`는
항상 `false`로 시작해서 사용자가 시트 안에서 직접 "실내만"을 켜게 한다 — 날씨
알림에서 열렸다고 자동으로 실내 필터를 켜버리면 일반 "대안 찾기" 진입과
구분이 안 돼서, 문맥 구분은 이후 필요해지면 별도로 다룬다(YAGNI).
`ListHeaderComponent`의 날씨 확인 버튼 위/아래에 추가:

```tsx
          <WeatherAlertBanner
            tripId={tripId}
            onOpenAlternative={(_tripId, tripPlaceId) => setAlternativeTargetId(tripPlaceId)}
          />
```

`import { WeatherAlertBanner } from "@/components/WeatherAlertBanner";` 추가.

- [ ] **Step 4: 타입체크**

Run: `npx tsc --noEmit`
Expected: exit code 0

- [ ] **Step 5: Commit**

```bash
git add src/components/WeatherAlertBanner.tsx src/screens/HomeScreen.tsx src/screens/TripDetailScreen.tsx
git commit -m "feat: 날씨 알림 배너를 홈/여행 상세에 연결"
```

---

## Self-Review 메모(계획 작성자용, 실행 시 참고)

- 스펙의 A~F 절 전부 태스크로 커버됨: A(Task 9), B(Task 3), C(Task 11의 미리보기),
  D(Task 6), E(Task 7+8), F(Task 11~13)
- Task 13 Step 2에서 `onOpenAlternative` 시그니처를 코드 작성 도중 수정하는
  구조로 남겨뒀다 — 실제 구현자는 Step 1의 최종 코드에 이미 반영된 2-인자
  시그니처로 바로 작성하면 된다(Step 2 본문은 "왜 그렇게 됐는지"를 남긴 것)
- Task 4의 서울 API 응답 필드명은 실제 API 호출로 검증이 필요하다고 명시함 —
  이건 placeholder가 아니라 외부 API 스펙 확인이 필요한 정상적인 구현 스텝
