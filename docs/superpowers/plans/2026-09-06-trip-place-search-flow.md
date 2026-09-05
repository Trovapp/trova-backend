# 여행 장소등록 플로우 개편 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 여행에 장소를 수동으로 추가하는 플로우를 카카오 검색 즉시등록 방식에서, 구글 플레이스 검색→상세(AI 리뷰요약)→즐겨찾기→방문시간/이동수단/메모 등록 방식으로 개편한다.

**Architecture:** 백엔드는 `GooglePlacesApiClient`에 텍스트검색/상세조회를 추가하고, 리뷰요약은 기존 Python+Runner Gemini 패턴을 그대로 재사용한다. `TripPlace`에 4개 필드(googlePlaceId/방문시간/이동수단/메모)를 nullable로 추가해 기존 VIDEO 출처 데이터에는 영향이 없게 한다. 프론트엔드는 `TripDetailView`의 검색 입력 한 줄을 검색/찜한장소 탭 UI로 교체한다.

**Tech Stack:** Spring Boot 4.1.0/Java 21, JPA/Hibernate(`ddl-auto: update`), Python3(Gemini 호출), Next.js/React 19/TypeScript.

**Spec:** `docs/superpowers/specs/2026-09-05-trip-place-search-flow-design.md` (커밋 b6368c1)

## Global Constraints

- 카카오 로컬 API는 이 플로우에서 완전히 빠진다 — 리뷰/평점 재현이 목적이므로 구글 플레이스로 전환한다(스펙 "결정 사항").
- Place Details API(리뷰 포함)는 유료 티어 — 사용자가 "상세보기"를 누를 때만 호출하고, 결과는 `Place.reviewSummary`에 영구 캐시해서 같은 장소를 다시 조회해도 API를 또 부르지 않는다.
- 방문시간/이동수단/메모는 전부 선택 입력 — 모달로 강제하지 않고, 장소만 고르면 바로 등록되고 나머지는 나중에 편하게 채운다.
- Gemini 호출은 이 프로젝트의 기존 관례(Python 스크립트 + Java record + `OutputParser` + `*Runner`, `ProcessBuilder`, stderr `TROVA_API_LOG:` 마커)를 그대로 따른다 — Gemini를 직접 호출하는 Java HTTP 클라이언트를 새로 만들지 않는다.
- 외부 API 호출(Google Places Details, Gemini)은 전부 `ApiCallLogService`로 로깅한다(비용 추적 원칙).
- 커밋 메시지는 `타입: 내용` 형식만 사용(feat/fix/docs/test/refactor 등), AI 서명/트레일러/이모지 금지(trova-backend CLAUDE.md).
- 커밋 전 `./gradlew build`(백엔드), `npx next build` + `npx tsc --noEmit`(프론트엔드) 실행.
- 이 플랜은 `confirmVideoPlacesIntoTrip`(VIDEO 출처) 경로를 건드리지 않는다 — `TripService.addPlaceToDay`(NORMAL 출처)만 대상.

---

## Task 1: 엔티티 필드 추가 (Place, TripPlace, TransportMode)

**Files:**
- Modify: `src/main/java/com/trova/backend/entity/Place.java`
- Modify: `src/main/java/com/trova/backend/entity/TripPlace.java`
- Create: `src/main/java/com/trova/backend/entity/TransportMode.java`
- Test: `src/test/java/com/trova/backend/entity/PlaceTest.java` (신규)
- Test: `src/test/java/com/trova/backend/entity/TripPlaceTest.java` (신규)

**Interfaces:**
- Produces: `Place.applyReviewSummary(String)`, `Place.getReviewSummary()`, `Place.getReviewSummaryGeneratedAt()`; `TripPlace.applyGooglePlaceId(String)`, `TripPlace.applyDetails(LocalTime, LocalTime, TransportMode, String)`, `TripPlace.getGooglePlaceId()`, `TripPlace.getVisitStartTime()`, `TripPlace.getVisitEndTime()`, `TripPlace.getArrivalTransportMode()`, `TripPlace.getMemo()`; `TransportMode` enum(WALK/TRANSIT/CAR) — Task 2 이후 전부가 이 인터페이스에 의존한다.

- [ ] **Step 1: 실패하는 테스트 작성 (Place)**

```java
package com.trova.backend.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceTest {

    @Test
    void applyReviewSummary는_요약과_생성시각을_함께_저장한다() {
        Place place = new Place("g1", "카페A", "cafe", 4.5, 100, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "주소");

        place.applyReviewSummary("전반적으로 만족도가 높은 곳이에요.");

        assertThat(place.getReviewSummary()).isEqualTo("전반적으로 만족도가 높은 곳이에요.");
        assertThat(place.getReviewSummaryGeneratedAt()).isNotNull();
        assertThat(place.getReviewSummaryGeneratedAt()).isBeforeOrEqualTo(LocalDateTime.now());
    }
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.entity.PlaceTest"`
Expected: FAIL — `applyReviewSummary`/`getReviewSummary`/`getReviewSummaryGeneratedAt` method not found

- [ ] **Step 3: Place에 필드/메서드 추가**

`Place.java`에서 `lastSyncedAt` 필드 선언 바로 아래에 추가:

```java
    @Column(name = "review_summary", columnDefinition = "TEXT")
    private String reviewSummary;

    @Column(name = "review_summary_generated_at")
    private LocalDateTime reviewSummaryGeneratedAt;
```

`applyTags` 메서드 바로 아래에 추가:

```java
    public void applyReviewSummary(String reviewSummary) {
        this.reviewSummary = reviewSummary;
        this.reviewSummaryGeneratedAt = LocalDateTime.now();
    }
```

getter 목록에 추가:

```java
    public String getReviewSummary() { return reviewSummary; }
    public LocalDateTime getReviewSummaryGeneratedAt() { return reviewSummaryGeneratedAt; }
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.entity.PlaceTest"`
Expected: PASS

- [ ] **Step 5: TransportMode 엔티티 작성**

```java
package com.trova.backend.entity;

/** TripPlace에 도착할 때 이전 장소에서 쓴 이동수단. 하루의 첫 장소는 항상 null이다. */
public enum TransportMode {
    WALK,
    TRANSIT,
    CAR
}
```

- [ ] **Step 6: 실패하는 테스트 작성 (TripPlace)**

```java
package com.trova.backend.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class TripPlaceTest {

    private TripPlace newTripPlace() {
        return new TripPlace(
                null, "돈사돈", "제주", "음식점", 33.4, 126.5, null, "제주 노형동",
                1, PlaceSource.NORMAL, null);
    }

    @Test
    void applyGooglePlaceId는_값을_저장한다() {
        TripPlace place = newTripPlace();

        place.applyGooglePlaceId("g-donsadon");

        assertThat(place.getGooglePlaceId()).isEqualTo("g-donsadon");
    }

    @Test
    void applyDetails는_null이_아닌_필드만_갱신한다() {
        TripPlace place = newTripPlace();
        place.applyDetails(LocalTime.of(11, 0), LocalTime.of(12, 30), TransportMode.WALK, "고기 맛집");

        place.applyDetails(null, null, TransportMode.CAR, null);

        assertThat(place.getVisitStartTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(place.getVisitEndTime()).isEqualTo(LocalTime.of(12, 30));
        assertThat(place.getArrivalTransportMode()).isEqualTo(TransportMode.CAR);
        assertThat(place.getMemo()).isEqualTo("고기 맛집");
    }
}
```

- [ ] **Step 7: 테스트 실행 → 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.entity.TripPlaceTest"`
Expected: FAIL — `applyGooglePlaceId`/`applyDetails`/관련 getter not found

- [ ] **Step 8: TripPlace에 필드/메서드 추가**

`space` 필드 선언 바로 아래에 추가(`import java.time.LocalTime;` 상단에 추가):

```java
    @Column(name = "google_place_id")
    private String googlePlaceId;

    @Column(name = "visit_start_time")
    private LocalTime visitStartTime;

    @Column(name = "visit_end_time")
    private LocalTime visitEndTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "arrival_transport_mode")
    private TransportMode arrivalTransportMode;

    @Column(columnDefinition = "TEXT")
    private String memo;
```

`applyVisitOrder` 메서드 바로 아래에 추가:

```java
    public void applyGooglePlaceId(String googlePlaceId) {
        this.googlePlaceId = googlePlaceId;
    }

    public void applyDetails(
            LocalTime visitStartTime, LocalTime visitEndTime, TransportMode arrivalTransportMode, String memo
    ) {
        if (visitStartTime != null) this.visitStartTime = visitStartTime;
        if (visitEndTime != null) this.visitEndTime = visitEndTime;
        if (arrivalTransportMode != null) this.arrivalTransportMode = arrivalTransportMode;
        if (memo != null) this.memo = memo;
    }
```

getter 목록에 추가:

```java
    public String getGooglePlaceId() { return googlePlaceId; }
    public LocalTime getVisitStartTime() { return visitStartTime; }
    public LocalTime getVisitEndTime() { return visitEndTime; }
    public TransportMode getArrivalTransportMode() { return arrivalTransportMode; }
    public String getMemo() { return memo; }
```

- [ ] **Step 9: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.entity.TripPlaceTest"`
Expected: PASS

- [ ] **Step 10: 전체 빌드 확인 후 커밋**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (`ddl-auto: update`이므로 스키마 마이그레이션 파일 불필요)

```bash
git add src/main/java/com/trova/backend/entity/Place.java \
        src/main/java/com/trova/backend/entity/TripPlace.java \
        src/main/java/com/trova/backend/entity/TransportMode.java \
        src/test/java/com/trova/backend/entity/PlaceTest.java \
        src/test/java/com/trova/backend/entity/TripPlaceTest.java
git commit -m "feat: Place 리뷰요약 캐시와 TripPlace 방문정보 필드 추가"
```

---

## Task 2: GooglePlacesApiClient 확장 (searchText, getDetails)

**Files:**
- Modify: `src/main/java/com/trova/backend/recommendation/GooglePlacesApiClient.java`
- Modify: `src/main/java/com/trova/backend/recommendation/GooglePlacesApiClientImpl.java`
- Create: `src/main/java/com/trova/backend/recommendation/GooglePlacesDetailsResponse.java`

**Interfaces:**
- Consumes: 없음 (Task 1과 독립)
- Produces: `GooglePlacesApiClient.searchText(String) -> GooglePlacesNearbySearchResponse`, `GooglePlacesApiClient.getDetails(String) -> GooglePlacesDetailsResponse` — Task 5(PlaceSearchService), Task 6(PlaceReviewService)가 이 시그니처에 의존한다.

이 클라이언트는 얇은 HTTP 래퍼라(기존 `searchNearby`도 마찬가지) 이 프로젝트 관례상 전용 단위테스트가 없다 — `RecommendationServiceTest`처럼 인터페이스를 Mockito로 대체해서 테스트한다(Task 5, 6에서 다룸). 이 태스크는 구현만 하고 빌드로 컴파일만 확인한다.

- [ ] **Step 1: GooglePlacesDetailsResponse 작성**

```java
package com.trova.backend.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Places API (New)의 getDetails 응답. reviews는 Enterprise + Atmosphere 티어(유료)라
 * DETAILS_FIELD_MASK를 통해 이 호출에서만 요청한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GooglePlacesDetailsResponse(String id, List<Review> reviews) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Review(ReviewText text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReviewText(String text) {
    }
}
```

- [ ] **Step 2: GooglePlacesApiClient 인터페이스에 메서드 추가**

```java
package com.trova.backend.recommendation;

public interface GooglePlacesApiClient {
    GooglePlacesNearbySearchResponse searchNearby(double latitude, double longitude, double radiusMeters);
    GooglePlacesNearbySearchResponse searchText(String query);
    GooglePlacesDetailsResponse getDetails(String googlePlaceId);
}
```

- [ ] **Step 3: GooglePlacesApiClientImpl 전체 교체**

파일 전체를 아래 내용으로 교체한다(재시도 루프를 `withRetry` 헬퍼로 뽑아 3개 메서드가 공유하게 리팩터, 필드마스크를 검색용/상세용으로 분리):

```java
package com.trova.backend.recommendation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class GooglePlacesApiClientImpl implements GooglePlacesApiClient {

    private static final Logger log = LoggerFactory.getLogger(GooglePlacesApiClientImpl.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MILLIS = 500;
    private static final int MAX_RESULT_COUNT = 20;

    // 비용을 낮은 티어(Basic + Pro Data)로만 유지하려고 최소 필드만 요청한다.
    // editorialSummary 등 Enterprise 티어 필드는 의도적으로 뺐다.
    private static final String SEARCH_FIELD_MASK = String.join(",",
            "places.id", "places.displayName", "places.types", "places.rating",
            "places.userRatingCount", "places.priceLevel", "places.location", "places.formattedAddress");

    // reviews는 Enterprise + Atmosphere 티어라 유료 — getDetails()에서만 요청한다.
    private static final String DETAILS_FIELD_MASK = "id,reviews";

    private final RestClient restClient;

    public GooglePlacesApiClientImpl(@Value("${app.google.places-api-key}") String apiKey) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        this.restClient = RestClient.builder()
                .baseUrl("https://places.googleapis.com")
                .defaultHeader("X-Goog-Api-Key", apiKey)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public GooglePlacesNearbySearchResponse searchNearby(double latitude, double longitude, double radiusMeters) {
        return withRetry(() -> doSearchNearby(latitude, longitude, radiusMeters));
    }

    @Override
    public GooglePlacesNearbySearchResponse searchText(String query) {
        return withRetry(() -> doSearchText(query));
    }

    @Override
    public GooglePlacesDetailsResponse getDetails(String googlePlaceId) {
        return withRetry(() -> doGetDetails(googlePlaceId));
    }

    private <T> T withRetry(Supplier<T> call) {
        RuntimeException lastFailure = null;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return call.get();
            } catch (HttpClientErrorException.TooManyRequests
                     | HttpServerErrorException
                     | ResourceAccessException e) {
                lastFailure = e;
                if (attempt == MAX_ATTEMPTS - 1) {
                    break;
                }
                long delay = RETRY_BASE_DELAY_MILLIS * (attempt + 1);
                log.warn("Google Places API 일시 오류({}/{}): {} — {}ms 후 재시도",
                        attempt + 1, MAX_ATTEMPTS, e.getMessage(), delay);
                sleep(delay);
            }
        }

        throw lastFailure;
    }

    private GooglePlacesNearbySearchResponse doSearchNearby(double latitude, double longitude, double radiusMeters) {
        Map<String, Object> body = Map.of(
                "maxResultCount", MAX_RESULT_COUNT,
                "locationRestriction", Map.of(
                        "circle", Map.of(
                                "center", Map.of("latitude", latitude, "longitude", longitude),
                                "radius", radiusMeters
                        )
                )
        );

        return restClient.post()
                .uri("/v1/places:searchNearby")
                .header("X-Goog-FieldMask", SEARCH_FIELD_MASK)
                .body(body)
                .retrieve()
                .body(GooglePlacesNearbySearchResponse.class);
    }

    private GooglePlacesNearbySearchResponse doSearchText(String query) {
        Map<String, Object> body = Map.of(
                "textQuery", query,
                "maxResultCount", MAX_RESULT_COUNT
        );

        return restClient.post()
                .uri("/v1/places:searchText")
                .header("X-Goog-FieldMask", SEARCH_FIELD_MASK)
                .body(body)
                .retrieve()
                .body(GooglePlacesNearbySearchResponse.class);
    }

    private GooglePlacesDetailsResponse doGetDetails(String googlePlaceId) {
        return restClient.get()
                .uri("/v1/places/{id}", googlePlaceId)
                .header("X-Goog-FieldMask", DETAILS_FIELD_MASK)
                .retrieve()
                .body(GooglePlacesDetailsResponse.class);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Google Places API 재시도 대기 중 인터럽트", e);
        }
    }
}
```

- [ ] **Step 4: 빌드 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (기존 `RecommendationServiceTest`는 `searchNearby`만 쓰므로 이 시점엔 영향 없음)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/trova/backend/recommendation/GooglePlacesApiClient.java \
        src/main/java/com/trova/backend/recommendation/GooglePlacesApiClientImpl.java \
        src/main/java/com/trova/backend/recommendation/GooglePlacesDetailsResponse.java
git commit -m "feat: 구글 플레이스 텍스트검색/상세조회 API 연동 추가"
```

---

## Task 3: 리뷰요약 Gemini 파이프라인 (Python 스크립트 + record + Parser)

**Files:**
- Create: `pipeline-test/summarize_reviews.py`
- Create: `src/main/java/com/trova/backend/pipeline/ReviewSummary.java`
- Create: `src/main/java/com/trova/backend/pipeline/ReviewSummaryOutputParser.java`
- Test: `src/test/java/com/trova/backend/pipeline/ReviewSummaryOutputParserTest.java`

**Interfaces:**
- Consumes: 없음
- Produces: `ReviewSummary(String summary)` record, `ReviewSummaryOutputParser.parse(String stdout) -> ReviewSummary` — Task 4(Runner)가 이 파서에 의존한다.

- [ ] **Step 1: summarize_reviews.py 작성**

`tag_places.py`와 동일한 구조(공유 헬퍼 재사용, self-repair 위임)로 작성한다:

```python
#!/usr/bin/env python3
"""장소 리뷰 텍스트 목록을 Gemini로 2~3문장 요약한다.

리뷰 하나하나를 따로 호출하지 않고, 한 장소의 리뷰 전체를 한 번에 모아 호출한다
(비용 절약, Trova의 낮은 Gemini RPM 한도에 맞춤 — tag_places.py와 동일한 원칙).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from extract_places import DEFAULT_MODEL, call_gemini_with_repair, load_api_key

SUMMARY_PROMPT = """당신은 장소 리뷰들을 읽고 핵심을 2~3문장으로 요약하는 도구입니다.
장점/단점이 갈리면 균형 있게 담으세요. 과장하지 말고 리뷰에 실제로 있는 내용만 쓰세요.

다음 형식의 JSON 객체만 출력하세요. 다른 텍스트는 출력하지 마세요.
{"summary": "..."}
"""


def _validate_summary(payload) -> dict:
    if not isinstance(payload, dict):
        raise ValueError(f"응답이 객체가 아님: {payload!r}")
    summary = payload.get("summary")
    if not isinstance(summary, str) or not summary.strip():
        raise ValueError(f"summary 필드가 비어있거나 없습니다: {payload!r}")
    return payload


def summarize_reviews(review_texts: list[str], model: str = DEFAULT_MODEL) -> dict:
    if not review_texts:
        raise ValueError("review_texts가 비어있습니다")
    api_key = load_api_key()
    parts = [
        {"text": f"리뷰 목록: {json.dumps(review_texts, ensure_ascii=False)}"},
        {"text": SUMMARY_PROMPT},
    ]

    def _parse(text: str) -> dict:
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            raise ValueError(f"유효한 JSON이 아님: {text[:500]}")
        return _validate_summary(payload)

    return call_gemini_with_repair(parts, model, api_key, "summarize_reviews", _parse)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: summarize_reviews.py <reviews.json>", file=sys.stderr)
        raise SystemExit(2)

    input_path = Path(sys.argv[1])
    if not input_path.exists():
        raise SystemExit(f"file not found: {input_path}")

    review_texts = json.loads(input_path.read_text(encoding="utf-8"))
    result = summarize_reviews(review_texts)
    print(json.dumps(result, ensure_ascii=False, indent=2))
```

- [ ] **Step 2: 스크립트 수동 스모크 테스트**

Run: `cd pipeline-test && GEMINI_API_KEY=$(grep GEMINI_API_KEY .env | cut -d= -f2) python3 -c "from summarize_reviews import summarize_reviews; print(summarize_reviews(['음식이 맛있어요', '직원이 친절해요', '주차가 불편해요']))"`
Expected: `{'summary': '...'}` 형태의 실제 Gemini 요약 출력 (2~3문장)

- [ ] **Step 3: ReviewSummary record 작성**

```java
package com.trova.backend.pipeline;

public record ReviewSummary(String summary) {
}
```

- [ ] **Step 4: 실패하는 테스트 작성 (ReviewSummaryOutputParser)**

```java
package com.trova.backend.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewSummaryOutputParserTest {

    @Test
    void 정상_JSON을_ReviewSummary로_파싱한다() {
        String stdout = "{\"summary\": \"전반적으로 만족도가 높은 곳이에요.\"}";

        ReviewSummary result = ReviewSummaryOutputParser.parse(stdout);

        assertThat(result.summary()).isEqualTo("전반적으로 만족도가 높은 곳이에요.");
    }

    @Test
    void summary_필드가_없으면_예외를_던진다() {
        String stdout = "{}";

        assertThatThrownBy(() -> ReviewSummaryOutputParser.parse(stdout))
                .isInstanceOf(PipelineException.class);
    }

    @Test
    void 유효하지_않은_JSON이면_예외를_던진다() {
        assertThatThrownBy(() -> ReviewSummaryOutputParser.parse("이건 JSON이 아님"))
                .isInstanceOf(PipelineException.class);
    }
}
```

- [ ] **Step 5: 테스트 실행 → 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.pipeline.ReviewSummaryOutputParserTest"`
Expected: FAIL — `ReviewSummaryOutputParser` class not found

- [ ] **Step 6: ReviewSummaryOutputParser 작성**

```java
package com.trova.backend.pipeline;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class ReviewSummaryOutputParser {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ReviewSummaryOutputParser() {
    }

    public static ReviewSummary parse(String stdout) {
        ReviewSummary summary;
        try {
            summary = MAPPER.readValue(stdout, ReviewSummary.class);
        } catch (Exception e) {
            throw new PipelineException("리뷰 요약 출력 파싱 실패: " + e.getMessage(), e);
        }
        if (summary.summary() == null || summary.summary().isBlank()) {
            throw new PipelineException("리뷰 요약 출력에 summary 필드가 없습니다: " + stdout);
        }
        return summary;
    }
}
```

- [ ] **Step 7: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.pipeline.ReviewSummaryOutputParserTest"`
Expected: PASS

- [ ] **Step 8: 커밋**

```bash
git add pipeline-test/summarize_reviews.py \
        src/main/java/com/trova/backend/pipeline/ReviewSummary.java \
        src/main/java/com/trova/backend/pipeline/ReviewSummaryOutputParser.java \
        src/test/java/com/trova/backend/pipeline/ReviewSummaryOutputParserTest.java
git commit -m "feat: 리뷰요약 Gemini 스크립트와 출력 파서 추가"
```

---

## Task 4: ReviewSummaryRunner

**Files:**
- Create: `src/main/java/com/trova/backend/pipeline/ReviewSummaryRunner.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application.yml`

**Interfaces:**
- Consumes: `ReviewSummaryOutputParser.parse(String) -> ReviewSummary` (Task 3), `ApiCallLogService.recordFromStderr(String, Long)` (기존)
- Produces: `ReviewSummaryRunner.run(List<String> reviewTexts, Long requestId) -> ReviewSummary` — Task 6(PlaceReviewService)이 이 시그니처에 의존한다.

- [ ] **Step 1: application.yml에 스크립트 경로 키 추가**

`src/main/resources/application.yml`의 `tag-places-script-path` 줄 바로 아래에 추가:

```yaml
    summarize-reviews-script-path: ${SUMMARIZE_REVIEWS_SCRIPT_PATH:pipeline-test/summarize_reviews.py}
```

`src/test/resources/application.yml`의 `tag-places-script-path` 줄 바로 아래에 추가:

```yaml
    summarize-reviews-script-path: pipeline-test/summarize_reviews.py
```

- [ ] **Step 2: ReviewSummaryRunner 작성 (PlaceTaggingRunner와 동일 골격)**

```java
package com.trova.backend.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.service.ApiCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 장소 리뷰 텍스트 목록을 Gemini로 2~3문장 요약한다(요청당 1회 호출, 리뷰당 별도 호출 아님). */
@Component
public class ReviewSummaryRunner {

    private static final Logger log = LoggerFactory.getLogger(ReviewSummaryRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_MINUTES = 2;
    private static final long STDOUT_JOIN_TIMEOUT_MILLIS = 30_000;

    private final String scriptPath;
    private final String workDirBase;
    private final String geminiApiKey;
    private final ApiCallLogService apiCallLogService;

    public ReviewSummaryRunner(
            @Value("${app.pipeline.summarize-reviews-script-path}") String scriptPath,
            @Value("${app.pipeline.work-dir}") String workDirBase,
            @Value("${app.pipeline.gemini-api-key}") String geminiApiKey,
            ApiCallLogService apiCallLogService
    ) {
        this.scriptPath = scriptPath;
        this.workDirBase = workDirBase;
        this.geminiApiKey = geminiApiKey;
        this.apiCallLogService = apiCallLogService;
    }

    public ReviewSummary run(List<String> reviewTexts, Long requestId) {
        Path workDir = Path.of(workDirBase, "review-summary-" + requestId);
        Path inputFile = workDir.resolve("reviews.json");

        try {
            Files.createDirectories(workDir);
            Files.writeString(inputFile, MAPPER.writeValueAsString(reviewTexts), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PipelineException("리뷰 요약 입력 파일 작성 실패: " + e.getMessage(), e);
        }

        ProcessBuilder builder = new ProcessBuilder("python3", scriptPath, inputFile.toString());
        builder.environment().put("GEMINI_API_KEY", geminiApiKey);

        File stderrFile = null;
        try {
            stderrFile = File.createTempFile("trova-review-summary-", ".stderr");
            builder.redirectError(stderrFile);

            Process process = builder.start();

            StringBuilder stdoutBuffer = new StringBuilder();
            Thread stdoutReader = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    stdoutBuffer.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    log.warn("리뷰 요약 stdout 읽기 실패", e);
                }
            }, "review-summary-stdout-" + requestId);
            stdoutReader.setDaemon(true);
            stdoutReader.start();

            boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.error("리뷰 요약 실행 시간 초과({}분)", TIMEOUT_MINUTES);
                throw new PipelineException("리뷰 요약 실행 시간 초과: requestId=" + requestId);
            }

            stdoutReader.join(STDOUT_JOIN_TIMEOUT_MILLIS);

            String stderrContent = readStderr(stderrFile);
            apiCallLogService.recordFromStderr(stderrContent, requestId);

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("리뷰 요약 실행 실패(exit={}): {}", exitCode, stderrContent);
                throw new PipelineException("리뷰 요약 실행 실패(exit=" + exitCode + "): " + stderrContent);
            }

            return ReviewSummaryOutputParser.parse(stdoutBuffer.toString());
        } catch (IOException e) {
            throw new PipelineException("리뷰 요약 프로세스 시작 실패: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PipelineException("리뷰 요약 실행 중 인터럽트: " + e.getMessage(), e);
        } finally {
            if (stderrFile != null && !stderrFile.delete()) {
                log.warn("리뷰 요약 stderr 임시 파일 삭제 실패: {}", stderrFile.getAbsolutePath());
            }
            try {
                Files.deleteIfExists(inputFile);
                Files.deleteIfExists(workDir);
            } catch (IOException e) {
                log.warn("리뷰 요약 작업 디렉터리 정리 실패: {}", workDir, e);
            }
        }
    }

    private String readStderr(File stderrFile) {
        try {
            return Files.readString(stderrFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(stderr 읽기 실패: " + e.getMessage() + ")";
        }
    }
}
```

이 클래스는 `PlaceTaggingRunner`와 동일 골격이라(그 클래스도 전용 단위테스트가 없음) 별도 테스트를 만들지 않는다 — Task 6에서 `PlaceReviewService`가 이 클래스를 Mockito로 대체해서 검증한다.

- [ ] **Step 3: 빌드 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/com/trova/backend/pipeline/ReviewSummaryRunner.java \
        src/main/resources/application.yml \
        src/test/resources/application.yml
git commit -m "feat: 리뷰요약 Gemini Runner 추가"
```

---

## Task 5: PlaceCatalogService 추출 + PlaceSearchService 신설

**Files:**
- Create: `src/main/java/com/trova/backend/recommendation/PlaceCatalogService.java`
- Modify: `src/main/java/com/trova/backend/recommendation/RecommendationService.java`
- Modify: `src/test/java/com/trova/backend/recommendation/RecommendationServiceTest.java`
- Test: `src/test/java/com/trova/backend/recommendation/PlaceCatalogServiceTest.java` (신규)
- Create: `src/main/java/com/trova/backend/recommendation/PlaceSearchService.java`
- Test: `src/test/java/com/trova/backend/recommendation/PlaceSearchServiceTest.java` (신규)

**Interfaces:**
- Consumes: `GooglePlacesApiClient.searchText(String)` (Task 2), `PlaceRepository.findByGooglePlaceIdIn(List<String>)` (기존)
- Produces: `PlaceCatalogService.upsertAll(List<GooglePlacesNearbySearchResponse.Place>) -> List<Place>`, `PlaceSearchService.search(String query) -> List<Place>` — Task 7(엔드포인트)이 `PlaceSearchService.search`에 의존한다.

`RecommendationService`가 이미 upsert 로직을 갖고 있는데(private 메서드), 그걸 `PlaceCatalogService`로 뽑아서 `PlaceSearchService`와 공유한다(DRY). `RecommendationService`는 태깅 결과 저장(`placeRepository.save`)이 여전히 필요하므로 `PlaceRepository` 의존은 그대로 유지한다.

- [ ] **Step 1: PlaceCatalogService 작성**

```java
package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import com.trova.backend.repository.PlaceRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Google Places 원시 후보를 Place 카탈로그에 upsert한다(배치 조회로 N+1 방지).
 * RecommendationService(근처 검색)와 PlaceSearchService(텍스트 검색) 양쪽에서 공유한다.
 */
@Service
public class PlaceCatalogService {

    private final PlaceRepository placeRepository;

    public PlaceCatalogService(PlaceRepository placeRepository) {
        this.placeRepository = placeRepository;
    }

    public List<Place> upsertAll(List<GooglePlacesNearbySearchResponse.Place> rawCandidates) {
        List<String> googleIds = rawCandidates.stream()
                .map(GooglePlacesNearbySearchResponse.Place::id)
                .toList();
        Map<String, Place> existingByGoogleId = placeRepository.findByGooglePlaceIdIn(googleIds).stream()
                .collect(Collectors.toMap(Place::getGooglePlaceId, p -> p));

        List<Place> result = new ArrayList<>();
        for (GooglePlacesNearbySearchResponse.Place raw : rawCandidates) {
            Place existing = existingByGoogleId.get(raw.id());
            if (existing != null) {
                result.add(existing);
                continue;
            }
            String category = raw.types() != null && !raw.types().isEmpty() ? raw.types().get(0) : null;
            String name = raw.displayName() != null ? raw.displayName().text() : null;
            Double lat = raw.location() != null ? raw.location().latitude() : null;
            Double lng = raw.location() != null ? raw.location().longitude() : null;
            Place created = placeRepository.save(new Place(
                    raw.id(), name, category, raw.rating(), raw.userRatingCount(),
                    raw.priceLevel(), lat, lng, raw.formattedAddress()));
            result.add(created);
        }
        return result;
    }
}
```

- [ ] **Step 2: PlaceCatalogServiceTest 작성**

```java
package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import com.trova.backend.repository.PlaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaceCatalogServiceTest {

    @Mock
    private PlaceRepository placeRepository;

    @InjectMocks
    private PlaceCatalogService placeCatalogService;

    private GooglePlacesNearbySearchResponse.Place rawPlace(String id, String name) {
        return new GooglePlacesNearbySearchResponse.Place(
                id, new GooglePlacesNearbySearchResponse.Place.DisplayName(name), List.of("cafe"),
                4.5, 100, "PRICE_LEVEL_MODERATE",
                new GooglePlacesNearbySearchResponse.Place.Location(37.5, 127.0), "서울 어딘가");
    }

    @Test
    void 기존에_있는_장소는_그대로_반환하고_새로_저장하지_않는다() {
        var raw = rawPlace("g1", "카페A");
        Place existing = new Place("g1", "카페A", "cafe", 4.5, 100, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "서울 어딘가");
        when(placeRepository.findByGooglePlaceIdIn(List.of("g1"))).thenReturn(List.of(existing));

        List<Place> result = placeCatalogService.upsertAll(List.of(raw));

        assertThat(result).containsExactly(existing);
        verify(placeRepository, never()).save(any());
    }

    @Test
    void 새_후보는_저장해서_반환한다() {
        var raw = rawPlace("g1", "카페A");
        when(placeRepository.findByGooglePlaceIdIn(List.of("g1"))).thenReturn(List.of());
        when(placeRepository.save(any(Place.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Place> result = placeCatalogService.upsertAll(List.of(raw));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getGooglePlaceId()).isEqualTo("g1");
        assertThat(result.get(0).getName()).isEqualTo("카페A");
    }
}
```

- [ ] **Step 3: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.recommendation.PlaceCatalogServiceTest"`
Expected: PASS

- [ ] **Step 4: RecommendationService 리팩터 (upsert를 PlaceCatalogService 호출로 교체)**

파일 전체를 아래로 교체한다(`private List<Place> upsert(...)` 메서드 삭제, 생성자에 `PlaceCatalogService` 추가, `recommend()`에서 `upsert(rawCandidates)` 호출을 `placeCatalogService.upsertAll(rawCandidates)`로 교체 — 그 외 로직은 동일):

```java
package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import com.trova.backend.entity.User;
import com.trova.backend.entity.UserPreference;
import com.trova.backend.pipeline.PlaceTag;
import com.trova.backend.pipeline.PlaceTaggingRunner;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.UserPreferenceRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Google Places 근처 검색 → 후보 upsert(PlaceCatalogService, N+1 방지) → 하드필터+스코어링으로
 * 퍼널 → 아직 안 태깅된 것만 Gemini로 배치 태깅(요청당 최대 1회 호출) → 최종 상위 N개 반환.
 */
@Service
public class RecommendationService {

    private static final int MIN_REVIEW_COUNT = 1;
    private static final int FUNNEL_TOP_N = 7;
    private static final int FINAL_TOP_N = 5;
    private static final double PREFERENCE_BOOST_WEIGHT = 0.5;

    private final GooglePlacesApiClient googlePlacesApiClient;
    private final PlaceCatalogService placeCatalogService;
    private final PlaceRepository placeRepository;
    private final PlaceTaggingRunner placeTaggingRunner;
    private final UserPreferenceRepository userPreferenceRepository;

    public RecommendationService(
            GooglePlacesApiClient googlePlacesApiClient,
            PlaceCatalogService placeCatalogService,
            PlaceRepository placeRepository,
            PlaceTaggingRunner placeTaggingRunner,
            UserPreferenceRepository userPreferenceRepository
    ) {
        this.googlePlacesApiClient = googlePlacesApiClient;
        this.placeCatalogService = placeCatalogService;
        this.placeRepository = placeRepository;
        this.placeTaggingRunner = placeTaggingRunner;
        this.userPreferenceRepository = userPreferenceRepository;
    }

    public List<Place> recommend(User user, double latitude, double longitude, double radiusMeters) {
        List<GooglePlacesNearbySearchResponse.Place> rawCandidates =
                googlePlacesApiClient.searchNearby(latitude, longitude, radiusMeters).places();
        if (rawCandidates == null || rawCandidates.isEmpty()) {
            return List.of();
        }

        List<Place> upserted = placeCatalogService.upsertAll(rawCandidates);

        List<Place> funnel = upserted.stream()
                .filter(p -> p.getUserRatingCount() != null && p.getUserRatingCount() >= MIN_REVIEW_COUNT)
                .sorted(Comparator.comparingDouble(this::score).reversed())
                .limit(FUNNEL_TOP_N)
                .toList();

        tagMissing(funnel);

        Map<String, Double> preferenceByMood = userPreferenceRepository.findByUser(user).stream()
                .collect(Collectors.toMap(UserPreference::getMood, UserPreference::getScore));

        return funnel.stream()
                .sorted(Comparator.comparingDouble((Place p) -> scoreWithPreference(p, preferenceByMood)).reversed())
                .limit(FINAL_TOP_N)
                .toList();
    }

    private double scoreWithPreference(Place place, Map<String, Double> preferenceByMood) {
        double base = score(place);
        double preference = place.getMood() != null ? preferenceByMood.getOrDefault(place.getMood(), 0.0) : 0.0;
        return base + preference * PREFERENCE_BOOST_WEIGHT;
    }

    private void tagMissing(List<Place> funnel) {
        List<Place> needsTagging = funnel.stream().filter(p -> p.getMood() == null).toList();
        if (needsTagging.isEmpty()) {
            return;
        }

        List<PlaceTaggingRunner.TagCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < needsTagging.size(); i++) {
            Place p = needsTagging.get(i);
            candidates.add(new PlaceTaggingRunner.TagCandidate(
                    i, p.getName(), p.getCategory(), p.getRating(), p.getUserRatingCount(), p.getPriceLevel()));
        }

        long requestId = System.nanoTime();
        List<PlaceTag> tags = placeTaggingRunner.run(candidates, requestId);
        Map<Integer, PlaceTag> tagByIndex = tags.stream()
                .collect(Collectors.toMap(PlaceTag::index, t -> t));

        for (int i = 0; i < needsTagging.size(); i++) {
            PlaceTag tag = tagByIndex.get(i);
            if (tag == null) {
                continue;
            }
            Place p = needsTagging.get(i);
            p.applyTags(tag.mood(), tag.space());
            placeRepository.save(p);
        }
    }

    private double score(Place place) {
        double rating = place.getRating() != null ? place.getRating() : 0.0;
        int reviewCount = place.getUserRatingCount() != null ? place.getUserRatingCount() : 0;
        return rating * Math.log(reviewCount + 1);
    }
}
```

- [ ] **Step 5: RecommendationServiceTest 마이그레이션 (PlaceRepository 직접 stub → PlaceCatalogService stub)**

파일 전체를 아래로 교체한다:

```java
package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import com.trova.backend.entity.User;
import com.trova.backend.entity.UserPreference;
import com.trova.backend.pipeline.PlaceTag;
import com.trova.backend.pipeline.PlaceTaggingRunner;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.UserPreferenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock
    private GooglePlacesApiClient googlePlacesApiClient;

    @Mock
    private PlaceCatalogService placeCatalogService;

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private PlaceTaggingRunner placeTaggingRunner;

    @Mock
    private UserPreferenceRepository userPreferenceRepository;

    @InjectMocks
    private RecommendationService recommendationService;

    private final User user = new User("google", "recommendation-test", "테스트유저", null);

    private GooglePlacesNearbySearchResponse.Place rawPlace(
            String id, String name, String category, Double rating, Integer reviewCount
    ) {
        return new GooglePlacesNearbySearchResponse.Place(
                id, new GooglePlacesNearbySearchResponse.Place.DisplayName(name), List.of(category),
                rating, reviewCount, "PRICE_LEVEL_MODERATE",
                new GooglePlacesNearbySearchResponse.Place.Location(37.5, 127.0), "서울 어딘가");
    }

    @Test
    void 새_후보는_저장하고_리뷰없는_후보는_필터링하고_태깅해서_반환한다() {
        var withReviews = rawPlace("g1", "카페A", "cafe", 4.5, 100);
        var noReviews = rawPlace("g2", "무명카페", "cafe", null, 0);
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 1000))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(withReviews, noReviews)));
        Place placeWithReviews = new Place("g1", "카페A", "cafe", 4.5, 100, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "서울 어딘가");
        Place placeNoReviews = new Place("g2", "무명카페", "cafe", null, 0, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "서울 어딘가");
        when(placeCatalogService.upsertAll(List.of(withReviews, noReviews)))
                .thenReturn(List.of(placeWithReviews, placeNoReviews));
        when(placeTaggingRunner.run(any(), anyLong()))
                .thenReturn(List.of(new PlaceTag(0, "TRENDY", "INDOOR")));
        when(userPreferenceRepository.findByUser(user)).thenReturn(List.of());

        List<Place> result = recommendationService.recommend(user, 37.5, 127.0, 1000);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("카페A");
        assertThat(result.get(0).getMood()).isEqualTo("TRENDY");
        assertThat(result.get(0).getSpace()).isEqualTo("INDOOR");

        ArgumentCaptor<List<PlaceTaggingRunner.TagCandidate>> captor = ArgumentCaptor.forClass(List.class);
        verify(placeTaggingRunner).run(captor.capture(), anyLong());
        assertThat(captor.getValue()).hasSize(1);
        verify(placeRepository).save(placeWithReviews);
    }

    @Test
    void 이미_태깅된_장소는_다시_태깅하지_않는다() {
        var raw = rawPlace("g1", "카페A", "cafe", 4.5, 100);
        when(googlePlacesApiClient.searchNearby(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(raw)));

        Place alreadyTagged = new Place("g1", "카페A", "cafe", 4.5, 100, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "주소");
        alreadyTagged.applyTags("CALM", "INDOOR");
        when(placeCatalogService.upsertAll(List.of(raw))).thenReturn(List.of(alreadyTagged));
        when(userPreferenceRepository.findByUser(user)).thenReturn(List.of());

        List<Place> result = recommendationService.recommend(user, 37.5, 127.0, 1000);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMood()).isEqualTo("CALM");
        verify(placeTaggingRunner, never()).run(any(), anyLong());
        verify(placeRepository, never()).save(any());
    }

    @Test
    void 검색_결과가_없으면_빈_리스트를_반환하고_아무것도_호출하지_않는다() {
        when(googlePlacesApiClient.searchNearby(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of()));

        List<Place> result = recommendationService.recommend(user, 37.5, 127.0, 1000);

        assertThat(result).isEmpty();
        verify(placeCatalogService, never()).upsertAll(any());
        verify(placeTaggingRunner, never()).run(any(), anyLong());
    }

    @Test
    void 선호_mood와_일치하면_점수가_낮아도_더_위로_올라간다() {
        var high = rawPlace("gA", "높은평점", "cafe", 5.0, 1000);
        var low = rawPlace("gB", "선호매칭", "cafe", 3.0, 10);
        when(googlePlacesApiClient.searchNearby(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(high, low)));

        Place placeHigh = new Place("gA", "높은평점", "cafe", 5.0, 1000, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "주소");
        Place placeLow = new Place("gB", "선호매칭", "cafe", 3.0, 10, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "주소");
        when(placeCatalogService.upsertAll(List.of(high, low))).thenReturn(List.of(placeHigh, placeLow));
        when(placeTaggingRunner.run(any(), anyLong())).thenAnswer(inv -> {
            List<PlaceTaggingRunner.TagCandidate> candidates = inv.getArgument(0);
            return candidates.stream()
                    .map(c -> new PlaceTag(c.index(), c.name().equals("선호매칭") ? "CALM" : "TRENDY", "INDOOR"))
                    .toList();
        });
        when(userPreferenceRepository.findByUser(user))
                .thenReturn(List.of(new UserPreference(user, "CALM", 100.0)));

        List<Place> result = recommendationService.recommend(user, 37.5, 127.0, 1000);

        assertThat(result.get(0).getName()).isEqualTo("선호매칭");
    }
}
```

- [ ] **Step 6: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.recommendation.RecommendationServiceTest"`
Expected: PASS (4개 테스트 전부)

- [ ] **Step 7: PlaceSearchService 작성**

```java
package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import org.springframework.stereotype.Service;

import java.util.List;

/** 사용자가 이름으로 검색한 장소 후보를 반환한다(여행에 수동으로 장소를 추가할 때 사용). */
@Service
public class PlaceSearchService {

    private final GooglePlacesApiClient googlePlacesApiClient;
    private final PlaceCatalogService placeCatalogService;

    public PlaceSearchService(GooglePlacesApiClient googlePlacesApiClient, PlaceCatalogService placeCatalogService) {
        this.googlePlacesApiClient = googlePlacesApiClient;
        this.placeCatalogService = placeCatalogService;
    }

    public List<Place> search(String query) {
        List<GooglePlacesNearbySearchResponse.Place> raw = googlePlacesApiClient.searchText(query).places();
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return placeCatalogService.upsertAll(raw);
    }
}
```

- [ ] **Step 8: PlaceSearchServiceTest 작성**

```java
package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaceSearchServiceTest {

    @Mock
    private GooglePlacesApiClient googlePlacesApiClient;

    @Mock
    private PlaceCatalogService placeCatalogService;

    @InjectMocks
    private PlaceSearchService placeSearchService;

    @Test
    void 검색결과를_카탈로그에_upsert해서_반환한다() {
        var raw = new GooglePlacesNearbySearchResponse.Place(
                "g1", new GooglePlacesNearbySearchResponse.Place.DisplayName("경복궁"), List.of("tourist_attraction"),
                4.6, 5000, null, new GooglePlacesNearbySearchResponse.Place.Location(37.58, 126.97), "서울 종로구");
        when(googlePlacesApiClient.searchText("경복궁"))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(raw)));
        Place upserted = new Place("g1", "경복궁", "tourist_attraction", 4.6, 5000, null, 37.58, 126.97, "서울 종로구");
        when(placeCatalogService.upsertAll(List.of(raw))).thenReturn(List.of(upserted));

        List<Place> result = placeSearchService.search("경복궁");

        assertThat(result).containsExactly(upserted);
    }

    @Test
    void 검색결과가_없으면_빈리스트를_반환하고_upsert를_호출하지_않는다() {
        when(googlePlacesApiClient.searchText("없는장소"))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of()));

        List<Place> result = placeSearchService.search("없는장소");

        assertThat(result).isEmpty();
        verify(placeCatalogService, never()).upsertAll(any());
    }
}
```

- [ ] **Step 9: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.recommendation.PlaceSearchServiceTest"`
Expected: PASS

- [ ] **Step 10: 전체 빌드 확인 후 커밋**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

```bash
git add src/main/java/com/trova/backend/recommendation/PlaceCatalogService.java \
        src/main/java/com/trova/backend/recommendation/RecommendationService.java \
        src/main/java/com/trova/backend/recommendation/PlaceSearchService.java \
        src/test/java/com/trova/backend/recommendation/RecommendationServiceTest.java \
        src/test/java/com/trova/backend/recommendation/PlaceCatalogServiceTest.java \
        src/test/java/com/trova/backend/recommendation/PlaceSearchServiceTest.java
git commit -m "refactor: Place upsert 로직을 PlaceCatalogService로 추출하고 텍스트검색 서비스 추가"
```

---

## Task 6: PlaceReviewService

**Files:**
- Create: `src/main/java/com/trova/backend/recommendation/PlaceReviewService.java`
- Test: `src/test/java/com/trova/backend/recommendation/PlaceReviewServiceTest.java`

**Interfaces:**
- Consumes: `GooglePlacesApiClient.getDetails(String)` (Task 2), `ReviewSummaryRunner.run(List<String>, Long)` (Task 4), `Place.applyReviewSummary(String)`/`getReviewSummary()` (Task 1), `ApiCallLogService.record(...)` (기존)
- Produces: `PlaceReviewService.getOrGenerateSummary(Long placeId) -> Optional<String>` (빈 Optional = 장소 없음/404) — Task 7(엔드포인트)이 이 시그니처에 의존한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import com.trova.backend.pipeline.ReviewSummary;
import com.trova.backend.pipeline.ReviewSummaryRunner;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.service.ApiCallLogService;
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
class PlaceReviewServiceTest {

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private GooglePlacesApiClient googlePlacesApiClient;

    @Mock
    private ReviewSummaryRunner reviewSummaryRunner;

    @Mock
    private ApiCallLogService apiCallLogService;

    @InjectMocks
    private PlaceReviewService placeReviewService;

    private Place newPlace() {
        return new Place("g1", "카페A", "cafe", 4.5, 100, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "서울 어딘가");
    }

    @Test
    void 캐시된_요약이_있으면_API를_호출하지_않고_그대로_반환한다() {
        Place place = newPlace();
        place.applyReviewSummary("이미 있는 요약");
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));

        Optional<String> result = placeReviewService.getOrGenerateSummary(1L);

        assertThat(result).contains("이미 있는 요약");
        verify(googlePlacesApiClient, never()).getDetails(any());
        verify(reviewSummaryRunner, never()).run(any(), anyLong());
    }

    @Test
    void 캐시가_없고_리뷰가_있으면_Gemini로_요약해서_캐시에_저장한다() {
        Place place = newPlace();
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(googlePlacesApiClient.getDetails("g1")).thenReturn(new GooglePlacesDetailsResponse(
                "g1", List.of(
                        new GooglePlacesDetailsResponse.Review(new GooglePlacesDetailsResponse.ReviewText("좋아요")),
                        new GooglePlacesDetailsResponse.Review(new GooglePlacesDetailsResponse.ReviewText("친절해요"))
                )));
        when(reviewSummaryRunner.run(List.of("좋아요", "친절해요"), 1L))
                .thenReturn(new ReviewSummary("전반적으로 만족도가 높은 곳이에요."));

        Optional<String> result = placeReviewService.getOrGenerateSummary(1L);

        assertThat(result).contains("전반적으로 만족도가 높은 곳이에요.");
        assertThat(place.getReviewSummary()).isEqualTo("전반적으로 만족도가 높은 곳이에요.");
        verify(placeRepository).save(place);
    }

    @Test
    void 리뷰가_없으면_Gemini를_호출하지_않고_고정_문구를_반환한다() {
        Place place = newPlace();
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(googlePlacesApiClient.getDetails("g1")).thenReturn(new GooglePlacesDetailsResponse("g1", List.of()));

        Optional<String> result = placeReviewService.getOrGenerateSummary(1L);

        assertThat(result).contains("리뷰 정보 없음");
        verify(reviewSummaryRunner, never()).run(any(), anyLong());
        verify(placeRepository, never()).save(any());
    }

    @Test
    void Details_API_실패하면_캐시하지_않고_실패_문구를_반환한다() {
        Place place = newPlace();
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(googlePlacesApiClient.getDetails("g1")).thenThrow(new RuntimeException("timeout"));

        Optional<String> result = placeReviewService.getOrGenerateSummary(1L);

        assertThat(result).contains("리뷰를 불러오지 못했어요");
        assertThat(place.getReviewSummary()).isNull();
        verify(placeRepository, never()).save(any());
    }

    @Test
    void 존재하지_않는_장소면_빈_Optional을_반환한다() {
        when(placeRepository.findById(999L)).thenReturn(Optional.empty());

        Optional<String> result = placeReviewService.getOrGenerateSummary(999L);

        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.recommendation.PlaceReviewServiceTest"`
Expected: FAIL — `PlaceReviewService` class not found

- [ ] **Step 3: PlaceReviewService 작성**

```java
package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import com.trova.backend.pipeline.ReviewSummary;
import com.trova.backend.pipeline.ReviewSummaryRunner;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.service.ApiCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 장소 리뷰요약을 캐시-우선으로 제공한다. Place Details API는 유료 티어라, 캐시가
 * 있으면 절대 다시 부르지 않는다(영구 캐시 — 갱신 정책 없음, 스펙 참고).
 */
@Service
public class PlaceReviewService {

    private static final Logger log = LoggerFactory.getLogger(PlaceReviewService.class);
    private static final String NO_REVIEWS_MESSAGE = "리뷰 정보 없음";
    private static final String FETCH_FAILED_MESSAGE = "리뷰를 불러오지 못했어요";

    private final PlaceRepository placeRepository;
    private final GooglePlacesApiClient googlePlacesApiClient;
    private final ReviewSummaryRunner reviewSummaryRunner;
    private final ApiCallLogService apiCallLogService;

    public PlaceReviewService(
            PlaceRepository placeRepository,
            GooglePlacesApiClient googlePlacesApiClient,
            ReviewSummaryRunner reviewSummaryRunner,
            ApiCallLogService apiCallLogService
    ) {
        this.placeRepository = placeRepository;
        this.googlePlacesApiClient = googlePlacesApiClient;
        this.reviewSummaryRunner = reviewSummaryRunner;
        this.apiCallLogService = apiCallLogService;
    }

    public Optional<String> getOrGenerateSummary(Long placeId) {
        Optional<Place> maybePlace = placeRepository.findById(placeId);
        if (maybePlace.isEmpty()) {
            return Optional.empty();
        }
        Place place = maybePlace.get();
        if (place.getReviewSummary() != null) {
            return Optional.of(place.getReviewSummary());
        }

        long start = System.currentTimeMillis();
        GooglePlacesDetailsResponse details;
        try {
            details = googlePlacesApiClient.getDetails(place.getGooglePlaceId());
            apiCallLogService.record(
                    "google-places", "place-details", null, System.currentTimeMillis() - start,
                    true, null, null, null, null);
        } catch (Exception e) {
            apiCallLogService.record(
                    "google-places", "place-details", null, System.currentTimeMillis() - start,
                    false, e.getMessage(), null, null, null);
            log.warn("Place Details 조회 실패(placeId={}) — 리뷰요약 없이 반환합니다", placeId, e);
            return Optional.of(FETCH_FAILED_MESSAGE);
        }

        List<String> reviewTexts = extractReviewTexts(details);
        if (reviewTexts.isEmpty()) {
            return Optional.of(NO_REVIEWS_MESSAGE);
        }

        ReviewSummary summary = reviewSummaryRunner.run(reviewTexts, place.getId());
        place.applyReviewSummary(summary.summary());
        placeRepository.save(place);
        return Optional.of(summary.summary());
    }

    private List<String> extractReviewTexts(GooglePlacesDetailsResponse details) {
        if (details.reviews() == null) {
            return List.of();
        }
        return details.reviews().stream()
                .map(GooglePlacesDetailsResponse.Review::text)
                .filter(text -> text != null && text.text() != null && !text.text().isBlank())
                .map(GooglePlacesDetailsResponse.ReviewText::text)
                .toList();
    }
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.recommendation.PlaceReviewServiceTest"`
Expected: PASS (5개 테스트 전부)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/trova/backend/recommendation/PlaceReviewService.java \
        src/test/java/com/trova/backend/recommendation/PlaceReviewServiceTest.java
git commit -m "feat: 캐시 우선 리뷰요약 서비스 추가"
```

---

## Task 7: 검색/상세 엔드포인트 (RecommendationController 확장)

**Files:**
- Modify: `src/main/java/com/trova/backend/controller/RecommendationController.java`

**Interfaces:**
- Consumes: `PlaceSearchService.search(String)` (Task 5), `PlaceReviewService.getOrGenerateSummary(Long)` (Task 6), `PlaceRepository.findById(Long)` (기존)
- Produces: `GET /api/places/search?query=...`, `GET /api/places/{id}/details` — Task 11(프론트 API 클라이언트)가 이 응답 JSON 형태에 의존한다.

`/api/places/{id}`(SavedPlace 단건조회, `PlacesController`)와 `/api/places/{id}/details`(Place 카탈로그 상세, 이 컨트롤러)는 서로 다른 URL 템플릿이라 Spring이 충돌 없이 구분한다 — 확인하되 별도 조치 불필요.

- [ ] **Step 1: RecommendationController 전체 교체**

```java
package com.trova.backend.controller;

import com.trova.backend.entity.Place;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.PlaceReviewService;
import com.trova.backend.recommendation.PlaceSearchService;
import com.trova.backend.recommendation.RecommendationService;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class RecommendationController {

    private static final double DEFAULT_RADIUS_METERS = 1000.0;
    private static final double MAX_RADIUS_METERS = 50_000.0;

    public record RecommendRequest(Double latitude, Double longitude, Double radiusMeters) {
    }

    public record PlaceRecommendationResponse(
            Long id, String googlePlaceId, String name, String category, String mood, String space,
            Double rating, Integer userRatingCount, String priceLevel,
            Double latitude, Double longitude, String address
    ) {
        static PlaceRecommendationResponse from(Place place) {
            return new PlaceRecommendationResponse(
                    place.getId(), place.getGooglePlaceId(), place.getName(), place.getCategory(),
                    place.getMood(), place.getSpace(), place.getRating(), place.getUserRatingCount(),
                    place.getPriceLevel(), place.getLatitude(), place.getLongitude(), place.getAddress());
        }
    }

    public record PlaceDetailResponse(
            Long id, String googlePlaceId, String name, String category,
            Double rating, Integer userRatingCount, String priceLevel,
            Double latitude, Double longitude, String address, String reviewSummary
    ) {
        static PlaceDetailResponse from(Place place, String reviewSummary) {
            return new PlaceDetailResponse(
                    place.getId(), place.getGooglePlaceId(), place.getName(), place.getCategory(),
                    place.getRating(), place.getUserRatingCount(), place.getPriceLevel(),
                    place.getLatitude(), place.getLongitude(), place.getAddress(), reviewSummary);
        }
    }

    private final RecommendationService recommendationService;
    private final CurrentUserService currentUserService;
    private final PlaceSearchService placeSearchService;
    private final PlaceReviewService placeReviewService;
    private final PlaceRepository placeRepository;

    public RecommendationController(
            RecommendationService recommendationService,
            CurrentUserService currentUserService,
            PlaceSearchService placeSearchService,
            PlaceReviewService placeReviewService,
            PlaceRepository placeRepository
    ) {
        this.recommendationService = recommendationService;
        this.currentUserService = currentUserService;
        this.placeSearchService = placeSearchService;
        this.placeReviewService = placeReviewService;
        this.placeRepository = placeRepository;
    }

    @PostMapping("/api/recommendations")
    public ResponseEntity<?> recommend(
            OAuth2AuthenticationToken authentication, @RequestBody RecommendRequest request
    ) {
        if (request == null || request.latitude() == null || request.longitude() == null) {
            return ResponseEntity.badRequest().build();
        }
        double radius = request.radiusMeters() != null ? request.radiusMeters() : DEFAULT_RADIUS_METERS;
        if (radius <= 0 || radius > MAX_RADIUS_METERS) {
            return ResponseEntity.badRequest().build();
        }

        User user = currentUserService.resolve(authentication);
        List<Place> places = recommendationService.recommend(user, request.latitude(), request.longitude(), radius);
        return ResponseEntity.ok(places.stream().map(PlaceRecommendationResponse::from).toList());
    }

    @GetMapping("/api/places/search")
    public ResponseEntity<List<PlaceRecommendationResponse>> search(@RequestParam String query) {
        if (query.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        List<Place> places = placeSearchService.search(query);
        return ResponseEntity.ok(places.stream().map(PlaceRecommendationResponse::from).toList());
    }

    @GetMapping("/api/places/{id}/details")
    public ResponseEntity<PlaceDetailResponse> details(@PathVariable Long id) {
        return placeRepository.findById(id)
                .flatMap(place -> placeReviewService.getOrGenerateSummary(id)
                        .map(summary -> PlaceDetailResponse.from(place, summary)))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 2: 빌드 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 로컬 백엔드 재시작 후 실API 수동 검증**

Run: `lsof -ti :8080 | xargs kill 2>/dev/null; set -a; source .env; set +a; nohup ./gradlew bootRun > /tmp/trova-backend-bootrun.log 2>&1 & disown`

브라우저에서 로그인 후:
- `curl -b <세션쿠키> "http://localhost:8080/api/places/search?query=경복궁"` → 실제 후보 리스트(200) 확인
- 위 응답의 `id` 값으로 `curl -b <세션쿠키> "http://localhost:8080/api/places/1/details"` → `reviewSummary` 필드에 실제 Gemini 요약 텍스트 확인, 같은 요청을 한 번 더 보내서 두 번째 호출은 즉시 응답(캐시 히트) 확인

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/com/trova/backend/controller/RecommendationController.java
git commit -m "feat: 장소 텍스트검색/상세조회 엔드포인트 추가"
```

---

## Task 8: TripService.addPlaceToDay를 googlePlaceId 기반으로 전환

**Files:**
- Modify: `src/main/java/com/trova/backend/service/TripService.java`
- Modify: `src/main/java/com/trova/backend/controller/TripController.java`
- Modify: `src/test/java/com/trova/backend/service/TripServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `PlaceRepository.findByGooglePlaceId(String)` (기존)
- Produces: `TripService.addPlaceToDay(User, Long, int, String googlePlaceId) -> Optional<TripPlace>`(시그니처의 마지막 인자 의미가 query→googlePlaceId로 바뀜), `POST /api/trips/{tripId}/days/{day}/places` 요청바디가 `{googlePlaceId}`로 바뀜 — Task 12(프론트 트립 API)가 이 계약에 의존한다.

**중요 정정:** 스펙 문서는 "TripService가 다른 메서드에서도 카카오를 쓰므로 의존성을 유지한다"고 적었지만, 실제 코드를 다시 확인해보니 `KakaoLocalApiClient`는 `addPlaceToDay`의 `searchFirst` 헬퍼에서만 쓰이고 있었다. 이 태스크에서 `searchFirst`를 삭제하면 `TripService`에 카카오 의존이 전혀 남지 않으므로, 생성자에서 `KakaoLocalApiClient`를 완전히 제거하고 `PlaceRepository`로 교체한다.

- [ ] **Step 1: TripService 수정 (생성자 교체, addPlaceToDay 재작성, searchFirst 삭제)**

`import` 블록에서 `com.trova.backend.geocoding.KakaoKeywordSearchResponse;`와 `com.trova.backend.geocoding.KakaoLocalApiClient;`를 삭제하고 `com.trova.backend.repository.PlaceRepository;`를 추가한다.

필드/생성자를 아래로 교체:

```java
    private final TripRepository tripRepository;
    private final ItineraryRepository itineraryRepository;
    private final TripPlaceRepository tripPlaceRepository;
    private final PlaceRepository placeRepository;
    private final NotificationRepository notificationRepository;

    public TripService(
            TripRepository tripRepository,
            ItineraryRepository itineraryRepository,
            TripPlaceRepository tripPlaceRepository,
            PlaceRepository placeRepository,
            NotificationRepository notificationRepository
    ) {
        this.tripRepository = tripRepository;
        this.itineraryRepository = itineraryRepository;
        this.tripPlaceRepository = tripPlaceRepository;
        this.placeRepository = placeRepository;
        this.notificationRepository = notificationRepository;
    }
```

`addPlaceToDay`와 `searchFirst`를 아래로 교체(전체 삭제 후 새로 작성):

```java
    /**
     * Place 카탈로그(구글 플레이스 검색에서 이미 upsert된 장소)에서 googlePlaceId로 찾아
     * 해당 일차 맨 끝에 추가한다. 카탈로그에 없으면(검색 단계를 안 거친 잘못된 요청)
     * 아무것도 만들지 않는다.
     */
    public Optional<TripPlace> addPlaceToDay(User user, Long tripId, int day, String googlePlaceId) {
        return tripRepository.findById(tripId)
                .filter(trip -> trip.getUser().getId().equals(user.getId()))
                .flatMap(trip -> itineraryRepository.findByTripAndDay(trip, day))
                .flatMap(itinerary -> placeRepository.findByGooglePlaceId(googlePlaceId).map(place -> {
                    List<TripPlace> siblings = tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary);
                    int nextOrder = siblings.size() + 1;
                    TripPlace tripPlace = new TripPlace(
                            itinerary, place.getName(), null, place.getCategory(),
                            place.getLatitude(), place.getLongitude(), null, place.getAddress(),
                            nextOrder, PlaceSource.NORMAL, null);
                    tripPlace.applyGooglePlaceId(place.getGooglePlaceId());
                    return tripPlaceRepository.save(tripPlace);
                }));
    }
```

- [ ] **Step 2: PATCH 태스크(9) 진행 전 컴파일 확인용 임시 updateDetails는 아직 추가하지 않는다 — 이 스텝은 건너뛴다**

(플레이스홀더 아님 — Task 9에서 별도로 추가할 예정이라는 설명. 이 태스크는 addPlaceToDay 전환만 다룬다.)

- [ ] **Step 3: TripController.AddPlaceRequest/addPlace 수정**

```java
    public record AddPlaceRequest(String googlePlaceId) {
    }
```

`addPlace` 메서드 본문 교체:

```java
    @PostMapping("/api/trips/{tripId}/days/{day}/places")
    public ResponseEntity<TripPlaceResponse> addPlace(
            OAuth2AuthenticationToken authentication, @PathVariable Long tripId, @PathVariable int day,
            @RequestBody AddPlaceRequest request
    ) {
        if (request == null || request.googlePlaceId() == null || request.googlePlaceId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        return tripService.addPlaceToDay(user, tripId, day, request.googlePlaceId())
                .map(place -> ResponseEntity.ok(TripPlaceResponse.from(place)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
```

- [ ] **Step 4: TripServiceIntegrationTest 마이그레이션 (카카오 mock → Place 픽스처)**

`import` 블록에서 `com.trova.backend.geocoding.KakaoKeywordSearchResponse;`, `com.trova.backend.geocoding.KakaoLocalApiClient;`, `org.springframework.test.context.bean.override.mockito.MockitoBean;`, `static org.mockito.Mockito.when;`를 삭제하고 `com.trova.backend.repository.PlaceRepository`는 이미 `import com.trova.backend.repository.*;`에 포함되어 있으므로 추가 import 불필요.

`@MockitoBean private KakaoLocalApiClient kakaoLocalApiClient;` 필드를 삭제하고 아래로 교체:

```java
    @Autowired
    private PlaceRepository placeRepository;
```

아래 5개 테스트를 전부 교체한다(각 테스트마다 서로 다른 `googlePlaceId`를 써서 공유 `Place` 카탈로그에서 유니크 제약 충돌이 안 나게 한다):

```java
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
```

- [ ] **Step 5: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.service.TripServiceIntegrationTest"`
Expected: PASS (전체)

- [ ] **Step 6: 전체 빌드 확인 후 커밋**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

```bash
git add src/main/java/com/trova/backend/service/TripService.java \
        src/main/java/com/trova/backend/controller/TripController.java \
        src/test/java/com/trova/backend/service/TripServiceIntegrationTest.java
git commit -m "feat: 여행 장소추가를 카카오 검색 대신 googlePlaceId 기반으로 전환"
```

---

## Task 9: PATCH /api/trip-places/{id}/details (방문시간/이동수단/메모)

**Files:**
- Modify: `src/main/java/com/trova/backend/service/TripService.java`
- Modify: `src/main/java/com/trova/backend/controller/TripController.java`
- Modify: `src/test/java/com/trova/backend/service/TripServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `TripPlace.applyDetails(...)` (Task 1)
- Produces: `TripService.updateDetails(User, Long, LocalTime, LocalTime, TransportMode, String) -> Optional<TripPlace>`, `PATCH /api/trip-places/{id}/details`, `TripPlaceResponse`에 4개 필드(googlePlaceId, visitStartTime, visitEndTime, arrivalTransportMode, memo) 추가 — Task 13(프론트 TripDetailView)이 이 응답 필드에 의존한다.

- [ ] **Step 1: TripService에 updateDetails 추가**

`removePlace` 메서드 바로 위에 추가(`import java.time.LocalTime;`를 상단에 추가):

```java
    /** 보낸 필드만 부분적으로 갱신한다(null인 필드는 기존 값 유지 — TripPlace.applyDetails 참고). */
    public Optional<TripPlace> updateDetails(
            User user, Long tripPlaceId, LocalTime visitStartTime, LocalTime visitEndTime,
            TransportMode arrivalTransportMode, String memo
    ) {
        return tripPlaceRepository.findById(tripPlaceId)
                .filter(p -> p.getItinerary().getTrip().getUser().getId().equals(user.getId()))
                .map(place -> {
                    place.applyDetails(visitStartTime, visitEndTime, arrivalTransportMode, memo);
                    return tripPlaceRepository.save(place);
                });
    }
```

- [ ] **Step 2: 실패하는 통합 테스트 작성**

`TripServiceIntegrationTest`에 `import java.time.LocalTime;` 추가 후, `deleteTrip은_딸린_Itinerary와_TripPlace까지_전부_지운다` 테스트 바로 아래에 추가:

```java
    @Test
    void updateDetails는_보낸_필드만_부분적으로_갱신한다() {
        User user = newUser();
        Trip trip = tripService.createTrip(user, "제주 여행", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1));
        placeRepository.save(new Place("trip-place-test-details", "돈사돈", null, null, null, null, 33.4, 126.5, null));
        TripPlace place = tripService.addPlaceToDay(user, trip.getId(), 1, "trip-place-test-details").orElseThrow();

        tripService.updateDetails(
                user, place.getId(), LocalTime.of(11, 0), LocalTime.of(12, 30), TransportMode.WALK, "고기 맛집");
        TripPlace afterFirstUpdate = tripPlaceRepository.findById(place.getId()).orElseThrow();
        assertThat(afterFirstUpdate.getVisitStartTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(afterFirstUpdate.getArrivalTransportMode()).isEqualTo(TransportMode.WALK);
        assertThat(afterFirstUpdate.getMemo()).isEqualTo("고기 맛집");

        tripService.updateDetails(user, place.getId(), null, null, TransportMode.CAR, null);
        TripPlace afterSecondUpdate = tripPlaceRepository.findById(place.getId()).orElseThrow();
        assertThat(afterSecondUpdate.getVisitStartTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(afterSecondUpdate.getArrivalTransportMode()).isEqualTo(TransportMode.CAR);
        assertThat(afterSecondUpdate.getMemo()).isEqualTo("고기 맛집");
    }

    @Test
    void updateDetails는_다른_사용자_소유_장소는_거부한다() {
        User owner = newUser();
        Trip trip = tripService.createTrip(owner, "제주 여행", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1));
        placeRepository.save(new Place("trip-place-test-owner-check", "돈사돈", null, null, null, null, 33.4, 126.5, null));
        TripPlace place = tripService.addPlaceToDay(owner, trip.getId(), 1, "trip-place-test-owner-check").orElseThrow();
        User stranger = userRepository.save(new User("google", "trip-service-integration-stranger", "다른유저", null));

        try {
            Optional<TripPlace> result =
                    tripService.updateDetails(stranger, place.getId(), null, null, TransportMode.WALK, null);
            assertThat(result).isEmpty();
        } finally {
            userRepository.delete(stranger);
        }
    }
```

- [ ] **Step 3: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.service.TripServiceIntegrationTest"`
Expected: PASS (`TripService`에 `updateDetails`가 이미 있으므로 바로 통과 — Step 1과 순서가 바뀌어도 무방하나, TDD 원칙상 Step 1 이전에 이 테스트를 먼저 작성해 실패를 확인했다고 가정하고 진행)

- [ ] **Step 4: TripController에 PATCH 엔드포인트와 TripPlaceResponse 필드 추가**

`import java.time.LocalTime;`과 `com.trova.backend.entity.TransportMode`(이미 `com.trova.backend.entity.*;`에 포함)를 확인한다.

`TripPlaceResponse` record 교체:

```java
    public record TripPlaceResponse(
            Long id, String placeName, String region, String category,
            Double latitude, Double longitude, String phone, String address,
            int visitOrder, String source, String googlePlaceId,
            LocalTime visitStartTime, LocalTime visitEndTime, String arrivalTransportMode, String memo
    ) {
        static TripPlaceResponse from(TripPlace p) {
            return new TripPlaceResponse(
                    p.getId(), p.getPlaceName(), p.getRegion(), p.getCategory(),
                    p.getLatitude(), p.getLongitude(), p.getPhone(), p.getAddress(),
                    p.getVisitOrder(), p.getSource().name(), p.getGooglePlaceId(),
                    p.getVisitStartTime(), p.getVisitEndTime(),
                    p.getArrivalTransportMode() != null ? p.getArrivalTransportMode().name() : null,
                    p.getMemo());
        }
    }
```

`UpdateDetailsRequest` record와 엔드포인트 추가(`ReorderRequest` record 바로 아래):

```java
    public record UpdateDetailsRequest(
            LocalTime visitStartTime, LocalTime visitEndTime, String arrivalTransportMode, String memo
    ) {
    }
```

```java
    @PatchMapping("/api/trip-places/{id}/details")
    public ResponseEntity<TripPlaceResponse> updateDetails(
            OAuth2AuthenticationToken authentication, @PathVariable Long id, @RequestBody UpdateDetailsRequest request
    ) {
        if (request == null) {
            return ResponseEntity.badRequest().build();
        }
        TransportMode transportMode = null;
        if (request.arrivalTransportMode() != null) {
            try {
                transportMode = TransportMode.valueOf(request.arrivalTransportMode());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }
        User user = currentUserService.resolve(authentication);
        return tripService.updateDetails(
                        user, id, request.visitStartTime(), request.visitEndTime(), transportMode, request.memo())
                .map(place -> ResponseEntity.ok(TripPlaceResponse.from(place)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
```

- [ ] **Step 5: 전체 빌드 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 로컬 백엔드 재시작 후 실API 수동 검증**

Run: `lsof -ti :8080 | xargs kill 2>/dev/null; set -a; source .env; set +a; nohup ./gradlew bootRun > /tmp/trova-backend-bootrun.log 2>&1 & disown`

브라우저 로그인 세션 쿠키로 `curl -X PATCH -H "Content-Type: application/json" -d '{"visitStartTime":"11:00","arrivalTransportMode":"WALK"}' -b <세션쿠키> "http://localhost:8080/api/trip-places/{id}/details"` → 200과 함께 갱신된 필드만 반영된 `TripPlaceResponse` 확인

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/trova/backend/service/TripService.java \
        src/main/java/com/trova/backend/controller/TripController.java \
        src/test/java/com/trova/backend/service/TripServiceIntegrationTest.java
git commit -m "feat: 여행 장소에 방문시간/이동수단/메모 부분수정 엔드포인트 추가"
```

---

## Task 10: BookmarkResponse에 googlePlaceId 추가

**Files:**
- Modify: `src/main/java/com/trova/backend/controller/BookmarkController.java`

**Interfaces:**
- Produces: `BookmarkResponse.googlePlaceId` 필드 — Task 12(프론트 bookmarks.ts)가 이 필드에 의존한다.

- [ ] **Step 1: BookmarkResponse record 수정**

```java
    public record BookmarkResponse(
            Long id, Long placeId, String placeName, String googlePlaceId, String mood, String space, String createdAt
    ) {
        static BookmarkResponse from(Bookmark b) {
            return new BookmarkResponse(
                    b.getId(), b.getPlace().getId(), b.getPlace().getName(), b.getPlace().getGooglePlaceId(),
                    b.getPlace().getMood(), b.getPlace().getSpace(), b.getCreatedAt().toString());
        }
    }
```

- [ ] **Step 2: 빌드 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add src/main/java/com/trova/backend/controller/BookmarkController.java
git commit -m "feat: 북마크 응답에 googlePlaceId 추가"
```

---

## Task 11: 프론트 — recommendations.ts에 searchPlaces/getPlaceDetails 추가

**Files:**
- Modify: `/Users/gimtaehyeong/Desktop/trova-frontend/src/lib/api/recommendations.ts`

**Interfaces:**
- Consumes: `GET /api/places/search`, `GET /api/places/{id}/details` (Task 7)
- Produces: `searchPlaces(query: string) -> Promise<RecommendedPlace[]>`, `getPlaceDetails(id: number) -> Promise<PlaceDetail>`, `type PlaceDetail` — Task 13(TripDetailView)이 이 함수/타입에 의존한다.

기존 `RecommendedPlace` 타입이 이미 `PlaceRecommendationResponse`와 정확히 같은 필드를 갖고 있으므로 그대로 재사용한다(새 타입 중복 정의 안 함).

- [ ] **Step 1: recommendations.ts 끝에 추가**

```typescript
export async function searchPlaces(query: string): Promise<RecommendedPlace[]> {
  const res = await fetch(`${API_BASE_URL}/api/places/search?query=${encodeURIComponent(query)}`, {
    credentials: "include",
  });
  if (!res.ok) {
    throw new Error(`GET /api/places/search failed: ${res.status}`);
  }
  return res.json();
}

export type PlaceDetail = RecommendedPlace & { reviewSummary: string };

export async function getPlaceDetails(id: number): Promise<PlaceDetail> {
  const res = await fetch(`${API_BASE_URL}/api/places/${id}/details`, { credentials: "include" });
  if (!res.ok) {
    throw new Error(`GET /api/places/${id}/details failed: ${res.status}`);
  }
  return res.json();
}
```

- [ ] **Step 2: 타입체크 확인**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add src/lib/api/recommendations.ts
git commit -m "feat: 장소 검색/상세 API 클라이언트 함수 추가"
```

---

## Task 12: 프론트 — trips.ts/bookmarks.ts 타입·함수 갱신

**Files:**
- Modify: `/Users/gimtaehyeong/Desktop/trova-frontend/src/lib/api/trips.ts`
- Modify: `/Users/gimtaehyeong/Desktop/trova-frontend/src/lib/api/bookmarks.ts`

**Interfaces:**
- Consumes: `POST /api/trips/{tripId}/days/{day}/places`(바디 변경, Task 8), `PATCH /api/trip-places/{id}/details`(Task 9), `TripPlaceResponse` 필드 확장(Task 9), `BookmarkResponse.googlePlaceId`(Task 10)
- Produces: `addTripPlace(tripId, day, googlePlaceId)`, `updateTripPlaceDetails(id, patch)`, `TripPlace` 타입에 5개 필드 추가, `Bookmark.googlePlaceId` — Task 13이 전부 사용한다.

**주의(백엔드 `LocalTime` 직렬화):** 백엔드는 `visitStartTime`/`visitEndTime`을 `"HH:mm:ss"` 형태의 문자열로 내려준다(Jackson JavaTimeModule 기본 ISO 포맷). HTML `<input type="time">`은 `"HH:mm"` 형식을 기대하므로, Task 13에서 화면에 표시할 때는 `.slice(0, 5)`로 잘라서 써야 한다. 요청을 보낼 때는 `"HH:mm"`만 보내도 백엔드의 `LocalTime` 파서가 정상적으로 받아들인다.

- [ ] **Step 1: trips.ts의 TripPlace 타입 교체**

```typescript
export type TripPlace = {
  id: number;
  placeName: string;
  region: string | null;
  category: string | null;
  latitude: number | null;
  longitude: number | null;
  phone: string | null;
  address: string | null;
  visitOrder: number;
  source: "VIDEO" | "NORMAL";
  googlePlaceId: string | null;
  visitStartTime: string | null;
  visitEndTime: string | null;
  arrivalTransportMode: "WALK" | "TRANSIT" | "CAR" | null;
  memo: string | null;
};
```

- [ ] **Step 2: addTripPlace 시그니처 변경**

```typescript
export async function addTripPlace(tripId: number, day: number, googlePlaceId: string): Promise<TripPlace> {
  const res = await fetch(`${API_BASE_URL}/api/trips/${tripId}/days/${day}/places`, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ googlePlaceId }),
  });
  return parseOrThrow(res, `POST /api/trips/${tripId}/days/${day}/places`);
}
```

- [ ] **Step 3: updateTripPlaceDetails 추가 (파일 끝)**

```typescript
export async function updateTripPlaceDetails(
  id: number,
  patch: {
    visitStartTime?: string;
    visitEndTime?: string;
    arrivalTransportMode?: "WALK" | "TRANSIT" | "CAR";
    memo?: string;
  }
): Promise<TripPlace> {
  const res = await fetch(`${API_BASE_URL}/api/trip-places/${id}/details`, {
    method: "PATCH",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(patch),
  });
  return parseOrThrow(res, `PATCH /api/trip-places/${id}/details`);
}
```

- [ ] **Step 4: bookmarks.ts의 Bookmark 타입에 필드 추가**

```typescript
export type Bookmark = {
  id: number;
  placeId: number;
  placeName: string;
  googlePlaceId: string;
  mood: string | null;
  space: string | null;
  createdAt: string;
};
```

- [ ] **Step 5: 타입체크 확인**

Run: `npx tsc --noEmit`
Expected: 에러 없음(이 시점에는 `TripDetailView.tsx`가 옛 `addTripPlace(tripId, day, query)` 3-인자 호출을 그대로 쓰고 있어도, 타입이 둘 다 `string`이라 타입 에러는 안 남 — Task 13에서 실제 동작을 맞춘다)

- [ ] **Step 6: 커밋**

```bash
git add src/lib/api/trips.ts src/lib/api/bookmarks.ts
git commit -m "feat: TripPlace 방문정보 필드와 googlePlaceId 기반 장소추가 반영"
```

---

## Task 13: 프론트 — TripDetailView 전면 개편 (검색/찜한장소 탭 + 상세보기 + 인라인 편집)

**Files:**
- Modify: `/Users/gimtaehyeong/Desktop/trova-frontend/src/components/TripDetailView.tsx`

**Interfaces:**
- Consumes: `searchPlaces`, `getPlaceDetails`, `type PlaceDetail`(Task 11), `addTripPlace(tripId, day, googlePlaceId)`, `updateTripPlaceDetails`(Task 12), `listBookmarks`, `addBookmark`(기존)

이 태스크는 검색/찜한장소 탭 UI와 방문정보 인라인 편집을 하나의 파일 전면 교체로 처리한다(두 부분이 같은 컴포넌트의 같은 렌더 트리 안에서 서로 얽혀 있어 분리 리뷰의 실익이 없음 — Task Right-Sizing 원칙).

- [ ] **Step 1: TripDetailView.tsx 전체 교체**

```tsx
"use client";

import { useEffect, useState } from "react";
import { KakaoMap, type MapPin } from "@/components/KakaoMap";
import { getDayColor } from "@/lib/itinerary";
import { addBookmark, listBookmarks, type Bookmark } from "@/lib/api/bookmarks";
import { getPlaceDetails, searchPlaces, type RecommendedPlace } from "@/lib/api/recommendations";
import {
  addTripPlace,
  checkWeather,
  getTrip,
  removeTripPlace,
  reorderTripPlace,
  updateTripPlaceDetails,
  type TripDetail,
} from "@/lib/api/trips";

const TRANSPORT_LABEL: Record<string, string> = {
  WALK: "도보",
  TRANSIT: "대중교통",
  CAR: "차량",
};

export function TripDetailView({ trip: initialTrip }: { trip: TripDetail }) {
  const [trip, setTrip] = useState(initialTrip);
  const [activeDay, setActiveDay] = useState(trip.days[0]?.day ?? 1);
  const [activeTab, setActiveTab] = useState<"search" | "bookmarks">("search");
  const [query, setQuery] = useState("");
  const [searchResults, setSearchResults] = useState<RecommendedPlace[]>([]);
  const [searching, setSearching] = useState(false);
  const [bookmarks, setBookmarks] = useState<Bookmark[]>([]);
  const [bookmarkedPlaceIds, setBookmarkedPlaceIds] = useState<Set<number>>(new Set());
  const [expandedPlaceId, setExpandedPlaceId] = useState<number | null>(null);
  const [reviewSummaries, setReviewSummaries] = useState<Map<number, string>>(new Map());
  const [detailsLoadingId, setDetailsLoadingId] = useState<number | null>(null);
  const [editingField, setEditingField] = useState<{ placeId: number; field: "time" | "transport" | "memo" } | null>(
    null
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [weatherMessage, setWeatherMessage] = useState<string | null>(null);

  useEffect(() => {
    listBookmarks().then((result) => {
      setBookmarks(result);
      setBookmarkedPlaceIds(new Set(result.map((b) => b.placeId)));
    });
  }, []);

  const dayColor = getDayColor(activeDay);
  const activeDayData = trip.days.find((d) => d.day === activeDay);
  const places = activeDayData?.places ?? [];
  const pins: MapPin[] = places
    .filter((p) => p.latitude !== null && p.longitude !== null)
    .map((p) => ({ id: String(p.id), latitude: p.latitude as number, longitude: p.longitude as number }));

  async function reload() {
    const fresh = await getTrip(trip.id);
    setTrip(fresh);
  }

  async function handleSearch(e: React.FormEvent) {
    e.preventDefault();
    if (!query.trim() || searching) return;
    setSearching(true);
    setError(null);
    try {
      const results = await searchPlaces(query.trim());
      setSearchResults(results);
    } catch {
      setError("장소를 찾지 못했어요. 다른 검색어로 시도해보세요.");
    } finally {
      setSearching(false);
    }
  }

  async function handleAddPlace(googlePlaceId: string) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await addTripPlace(trip.id, activeDay, googlePlaceId);
      await reload();
    } catch {
      setError("장소를 추가하지 못했어요.");
    } finally {
      setBusy(false);
    }
  }

  async function handleToggleDetails(placeId: number) {
    if (expandedPlaceId === placeId) {
      setExpandedPlaceId(null);
      return;
    }
    setExpandedPlaceId(placeId);
    if (reviewSummaries.has(placeId)) return;
    setDetailsLoadingId(placeId);
    try {
      const detail = await getPlaceDetails(placeId);
      setReviewSummaries((current) => new Map(current).set(placeId, detail.reviewSummary));
    } catch {
      setReviewSummaries((current) => new Map(current).set(placeId, "리뷰를 불러오지 못했어요."));
    } finally {
      setDetailsLoadingId(null);
    }
  }

  async function handleToggleBookmark(placeId: number) {
    if (bookmarkedPlaceIds.has(placeId)) return;
    try {
      await addBookmark(placeId);
      setBookmarkedPlaceIds((current) => new Set(current).add(placeId));
    } catch {
      setError("찜하기에 실패했어요.");
    }
  }

  async function handleRemove(placeId: number) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await removeTripPlace(placeId);
      await reload();
    } catch {
      setError("장소를 삭제하지 못했어요.");
    } finally {
      setBusy(false);
    }
  }

  async function handleReorder(placeId: number, direction: "UP" | "DOWN") {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await reorderTripPlace(placeId, direction);
      await reload();
    } catch {
      setError("순서를 바꾸지 못했어요.");
    } finally {
      setBusy(false);
    }
  }

  async function handleUpdateDetails(
    placeId: number,
    patch: Parameters<typeof updateTripPlaceDetails>[1]
  ) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await updateTripPlaceDetails(placeId, patch);
      await reload();
    } catch {
      setError("저장하지 못했어요.");
    } finally {
      setBusy(false);
      setEditingField(null);
    }
  }

  async function handleCheckWeather() {
    if (busy) return;
    setBusy(true);
    setWeatherMessage(null);
    try {
      const result = await checkWeather(trip.id, activeDay);
      setWeatherMessage(result.message);
    } catch {
      setWeatherMessage("날씨 확인에 실패했어요.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-4 rounded-xl border border-border-subtle p-4">
      <div className="flex items-center justify-between">
        <div className="flex flex-wrap gap-2">
          {trip.days.map((d) => (
            <button
              key={d.day}
              type="button"
              onClick={() => setActiveDay(d.day)}
              className={`rounded-full px-4 py-1.5 text-sm font-medium transition-colors ${
                d.day === activeDay ? "bg-accent text-white" : "bg-bg-muted text-ink-muted hover:text-ink"
              }`}
            >
              {d.day}일차{d.date ? ` (${d.date.slice(5)})` : ""}
            </button>
          ))}
        </div>
        <button
          type="button"
          onClick={handleCheckWeather}
          disabled={busy || !activeDayData?.date}
          className="text-sm font-medium text-accent hover:underline disabled:opacity-40 disabled:hover:no-underline"
        >
          날씨 확인
        </button>
      </div>

      {weatherMessage && (
        <p className="rounded-lg bg-accent-bg px-3 py-2 text-sm text-ink">{weatherMessage}</p>
      )}
      {error && <p className="text-sm text-accent">{error}</p>}

      {pins.length > 0 && <KakaoMap pins={pins} color={dayColor} />}

      <ul className="flex flex-col gap-2">
        {places.map((place, index) => (
          <li
            key={place.id}
            className="flex flex-col gap-2 rounded-lg border border-border-subtle p-3"
          >
            <div className="flex items-center justify-between gap-3">
              <div className="min-w-0">
                <p className="truncate text-sm font-medium text-ink">
                  {place.visitOrder}. {place.placeName}
                </p>
                {place.address && <p className="truncate text-xs text-ink-muted">{place.address}</p>}
              </div>
              <div className="flex shrink-0 items-center gap-2">
                <button
                  type="button"
                  onClick={() => handleReorder(place.id, "UP")}
                  disabled={busy || index === 0}
                  className="text-xs text-ink-muted hover:text-ink disabled:opacity-30"
                  aria-label="위로 이동"
                >
                  ↑
                </button>
                <button
                  type="button"
                  onClick={() => handleReorder(place.id, "DOWN")}
                  disabled={busy || index === places.length - 1}
                  className="text-xs text-ink-muted hover:text-ink disabled:opacity-30"
                  aria-label="아래로 이동"
                >
                  ↓
                </button>
                <button
                  type="button"
                  onClick={() => handleRemove(place.id)}
                  disabled={busy}
                  className="text-xs text-ink-muted hover:text-accent"
                  aria-label="삭제"
                >
                  ✕
                </button>
              </div>
            </div>

            <div className="flex flex-wrap items-center gap-3 text-xs text-ink-muted">
              {editingField?.placeId === place.id && editingField.field === "time" ? (
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    const form = e.currentTarget;
                    const start = (form.elements.namedItem("start") as HTMLInputElement).value;
                    const end = (form.elements.namedItem("end") as HTMLInputElement).value;
                    handleUpdateDetails(place.id, {
                      visitStartTime: start || undefined,
                      visitEndTime: end || undefined,
                    });
                  }}
                  className="flex items-center gap-1"
                >
                  <input
                    name="start"
                    type="time"
                    defaultValue={place.visitStartTime?.slice(0, 5) ?? ""}
                    className="rounded border border-border px-1 py-0.5 text-xs"
                  />
                  <span>~</span>
                  <input
                    name="end"
                    type="time"
                    defaultValue={place.visitEndTime?.slice(0, 5) ?? ""}
                    className="rounded border border-border px-1 py-0.5 text-xs"
                  />
                  <button type="submit" className="text-accent">저장</button>
                </form>
              ) : (
                <button
                  type="button"
                  onClick={() => setEditingField({ placeId: place.id, field: "time" })}
                  className="hover:text-ink"
                >
                  {place.visitStartTime && place.visitEndTime
                    ? `${place.visitStartTime.slice(0, 5)}~${place.visitEndTime.slice(0, 5)}`
                    : "시간 추가"}
                </button>
              )}

              {editingField?.placeId === place.id && editingField.field === "transport" ? (
                <select
                  autoFocus
                  defaultValue={place.arrivalTransportMode ?? ""}
                  onChange={(e) =>
                    handleUpdateDetails(place.id, {
                      arrivalTransportMode: e.target.value as "WALK" | "TRANSIT" | "CAR",
                    })
                  }
                  onBlur={() => setEditingField(null)}
                  className="rounded border border-border px-1 py-0.5 text-xs"
                >
                  <option value="" disabled>
                    선택
                  </option>
                  <option value="WALK">도보</option>
                  <option value="TRANSIT">대중교통</option>
                  <option value="CAR">차량</option>
                </select>
              ) : (
                <button
                  type="button"
                  onClick={() => setEditingField({ placeId: place.id, field: "transport" })}
                  className="hover:text-ink"
                >
                  {place.arrivalTransportMode ? TRANSPORT_LABEL[place.arrivalTransportMode] : "이동수단 추가"}
                </button>
              )}

              {editingField?.placeId === place.id && editingField.field === "memo" ? (
                <input
                  autoFocus
                  type="text"
                  defaultValue={place.memo ?? ""}
                  onBlur={(e) => handleUpdateDetails(place.id, { memo: e.target.value })}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") (e.target as HTMLInputElement).blur();
                  }}
                  className="rounded border border-border px-1 py-0.5 text-xs"
                />
              ) : (
                <button
                  type="button"
                  onClick={() => setEditingField({ placeId: place.id, field: "memo" })}
                  className="max-w-[10rem] truncate hover:text-ink"
                >
                  {place.memo || "메모 추가"}
                </button>
              )}
            </div>
          </li>
        ))}
        {places.length === 0 && (
          <li className="rounded-lg border border-dashed border-border-subtle p-4 text-center text-sm text-ink-muted">
            아직 장소가 없어요. 아래에서 검색해서 추가해보세요.
          </li>
        )}
      </ul>

      <div className="flex flex-col gap-3 border-t border-border-subtle pt-4">
        <div className="flex gap-4 text-sm font-medium">
          <button
            type="button"
            onClick={() => setActiveTab("search")}
            className={activeTab === "search" ? "text-accent" : "text-ink-muted hover:text-ink"}
          >
            검색
          </button>
          <button
            type="button"
            onClick={() => setActiveTab("bookmarks")}
            className={activeTab === "bookmarks" ? "text-accent" : "text-ink-muted hover:text-ink"}
          >
            찜한 장소
          </button>
        </div>

        {activeTab === "search" ? (
          <>
            <form onSubmit={handleSearch} className="flex gap-2">
              <input
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="장소 이름으로 검색 (예: 경복궁)"
                className="flex-1 rounded-lg border border-border px-3 py-2 text-sm outline-none focus:border-accent"
              />
              <button
                type="submit"
                disabled={searching || !query.trim()}
                className="shrink-0 rounded-lg bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-60"
              >
                {searching ? "검색 중..." : "검색"}
              </button>
            </form>
            <ul className="flex flex-col gap-2">
              {searchResults.map((place) => (
                <li key={place.id} className="rounded-lg border border-border-subtle p-3">
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <p className="truncate text-sm font-medium text-ink">{place.name}</p>
                      {place.address && <p className="truncate text-xs text-ink-muted">{place.address}</p>}
                      {(place.rating !== null || place.userRatingCount !== null) && (
                        <p className="mt-0.5 text-xs text-ink-muted">
                          {place.rating !== null && `⭐ ${place.rating.toFixed(1)}`}
                          {place.userRatingCount !== null && ` (리뷰 ${place.userRatingCount}개)`}
                        </p>
                      )}
                    </div>
                    <div className="flex shrink-0 items-center gap-2">
                      <button
                        type="button"
                        onClick={() => handleToggleBookmark(place.id)}
                        aria-label={bookmarkedPlaceIds.has(place.id) ? "찜한 장소" : "찜하기"}
                        className="text-lg leading-none"
                      >
                        {bookmarkedPlaceIds.has(place.id) ? "❤️" : "🤍"}
                      </button>
                      <button
                        type="button"
                        onClick={() => handleAddPlace(place.googlePlaceId)}
                        disabled={busy}
                        className="rounded-lg bg-accent px-3 py-1.5 text-xs font-medium text-white hover:opacity-90 disabled:opacity-60"
                      >
                        추가
                      </button>
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={() => handleToggleDetails(place.id)}
                    className="mt-2 text-xs text-accent hover:underline"
                  >
                    {expandedPlaceId === place.id ? "상세 접기" : "상세보기"}
                  </button>
                  {expandedPlaceId === place.id && (
                    <p className="mt-1 text-xs text-ink-muted">
                      {detailsLoadingId === place.id
                        ? "리뷰 요약을 불러오는 중..."
                        : reviewSummaries.get(place.id)}
                    </p>
                  )}
                </li>
              ))}
            </ul>
          </>
        ) : (
          <ul className="flex flex-col gap-2">
            {bookmarks.length === 0 && <li className="text-sm text-ink-muted">아직 찜한 장소가 없어요.</li>}
            {bookmarks.map((bookmark) => (
              <li
                key={bookmark.id}
                className="flex items-center justify-between gap-3 rounded-lg border border-border-subtle p-3"
              >
                <p className="truncate text-sm font-medium text-ink">{bookmark.placeName}</p>
                <button
                  type="button"
                  onClick={() => handleAddPlace(bookmark.googlePlaceId)}
                  disabled={busy}
                  className="shrink-0 rounded-lg bg-accent px-3 py-1.5 text-xs font-medium text-white hover:opacity-90 disabled:opacity-60"
                >
                  추가
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: 빌드 및 타입체크**

Run: `npx next build && npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add src/components/TripDetailView.tsx
git commit -m "feat: 여행 장소추가를 검색/찜한장소 탭과 상세보기, 방문정보 인라인편집으로 개편"
```

---

## Task 14: 전체 통합 검증

**Files:** 없음(검증 전용 태스크)

**Interfaces:** 없음

- [ ] **Step 1: 백엔드 전체 테스트 + 빌드**

Run: `cd /Users/gimtaehyeong/Downloads/buildlog/trova-backend/.worktrees/feat-saved-places-pipeline && ./gradlew build`
Expected: BUILD SUCCESSFUL, 모든 테스트 통과

- [ ] **Step 2: 백엔드 재시작**

Run: `lsof -ti :8080 | xargs kill 2>/dev/null; cd /Users/gimtaehyeong/Downloads/buildlog/trova-backend/.worktrees/feat-saved-places-pipeline && set -a; source .env; set +a; nohup ./gradlew bootRun > /tmp/trova-backend-bootrun.log 2>&1 & disown`

Expected: `tail -f /tmp/trova-backend-bootrun.log`에서 "Started TrovaBackendApplication" 로그 확인(재시작 때마다 로그인 세션이 끊기므로 이후 브라우저에서 재로그인 필요)

- [ ] **Step 3: 프론트 빌드**

Run: `cd /Users/gimtaehyeong/Desktop/trova-frontend && npx next build && npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 4: 실브라우저 수동 검증 (Chrome MCP 도구 사용)**

기존 여행 상세 페이지(`/trips/{id}`)에서 아래 전체 플로우를 실제 클릭으로 확인한다:
1. "검색" 탭에서 실제 장소 이름(예: "경복궁") 검색 → 후보 리스트에 평점/리뷰수 표시 확인
2. 후보 카드의 "상세보기" 클릭 → 실제 Gemini 리뷰요약 텍스트가 나타나는지 확인(첫 클릭은 API 호출로 지연 있음), 다시 클릭해서 접혔다 펴질 때 재호출 없이 즉시 표시되는지 확인
3. 하트 버튼 클릭 → 찜 상태(❤️)로 바뀌는지 확인, `/bookmarks` 페이지에서도 반영 확인
4. "추가" 클릭 → 여행 일정에 장소가 실제로 추가되는지 확인
5. "찜한 장소" 탭으로 전환 → 방금 찜한 장소가 목록에 보이는지, "추가" 버튼으로 검색 없이 바로 등록되는지 확인
6. 추가된 장소 카드에서 "시간 추가" 클릭 → 시간 입력 → 저장 → 카드에 시간 범위 표시 확인
7. "이동수단 추가" 클릭 → 선택 → 저장 없이 즉시 반영(select onChange) 확인
8. "메모 추가" 클릭 → 텍스트 입력 → 포커스 아웃 → 저장 확인
9. `network_requests` 도구로 각 단계의 실제 HTTP 요청/응답 코드 확인(200대 응답인지)

Expected: 전 과정이 에러 없이 동작하고, 각 단계에서 실제 백엔드 응답이 화면에 반영됨

- [ ] **Step 5: 관측성 확인**

Run: `curl -s http://localhost:8080/actuator/prometheus | grep 'trova_api_call.*google-places\|trova_api_call.*gemini'`
Expected: `provider="google-places",operation="place-details"`와 `provider="gemini",operation="summarize_reviews"`(또는 리뷰요약 스크립트의 `TROVA_API_LOG` operation 값) 태그가 붙은 지표가 최소 1건 이상 보임 — Step 4에서 실제 호출한 흔적

- [ ] **Step 6: 비용 근거 문서 기록**

Step 4~5에서 관측한 실제 호출 횟수(검색 1회, 상세보기 클릭 횟수 = Details API 호출 횟수, 캐시 히트 여부)를 `docs/benchmarks/2026-09-06-place-search-review-summary.md`로 기록한다(포트폴리오 원칙 — 실측치만, 감으로 정한 값 아님을 명시):

```markdown
# 장소 검색/리뷰요약 실측 (2026-09-06)

수동 브라우저 검증 중 실측한 수치(감으로 정한 값 아님):

- 텍스트 검색(`GET /api/places/search`) 호출: N회, 전부 무료 티어(Basic+Pro Data)
- Place Details 호출(유료 티어): N회 — 최초 상세보기 클릭당 1회, 재클릭 시 캐시 히트로 0회
- Gemini 리뷰요약 호출: N회 (Details 호출 횟수와 1:1 대응, 캐시 미스일 때만)
- 캐시 히트율: 같은 장소 상세보기를 2번째 이상 클릭했을 때 API 미호출 확인(N/N)

(N은 실제 검증 시 관찰한 숫자로 채운다.)
```

Run 후:

```bash
cd /Users/gimtaehyeong/Downloads/buildlog/trova-backend/.worktrees/feat-saved-places-pipeline
git add docs/benchmarks/2026-09-06-place-search-review-summary.md
git commit -m "docs: 장소 검색/리뷰요약 실측 비용 근거 기록"
```

---

## Self-Review 메모 (계획 작성자 기록)

- **스펙 커버리지:** 검색 제공자 전환(Task 2,5,7,8), Details 유료 티어 캐시(Task 1,6), 선택 입력 방문정보(Task 1,9,13), 찜한 장소 빠른추가(Task 13) — 스펙의 모든 "결정 사항" 항목에 대응하는 태스크가 있다.
- **스펙 정정:** Task 8에서 명시했듯, 스펙 문서의 "TripService가 다른 메서드에서도 카카오를 쓴다"는 서술은 실제 코드 재확인 결과 틀렸다 — `addPlaceToDay`의 `searchFirst`가 유일한 사용처였다. 플랜은 이 정정된 사실을 따른다(카카오 의존 완전 제거).
- **타입 일관성:** `TripPlace.googlePlaceId`(Task 1) → `TripService.addPlaceToDay`/`updateDetails`(Task 8,9) → `TripPlaceResponse`(Task 9) → 프론트 `TripPlace` 타입(Task 12) → `TripDetailView`(Task 13)까지 필드명이 전부 일치한다. `PlaceCatalogService.upsertAll`(Task 5)의 시그니처가 `RecommendationService`와 `PlaceSearchService` 양쪽에서 동일하게 쓰인다.
