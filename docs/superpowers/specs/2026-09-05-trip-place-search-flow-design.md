# 여행 장소등록 플로우 개편 설계

날짜: 2026-09-05
상태: 승인됨 (브레인스토밍 완료)

## 배경

플랜비(Plan B, 여행 일정 추천/복구 서비스) 시연 영상을 참고 자료로
검토했다(`https://www.youtube.com/watch?v=LJRCXCwBTDk`, 자막+주요 프레임
추출로 전체 플로우 확인). 사용자 요청은 "디자인은 트루바 걸로 하되
전체적인 플로우는 플랜비와 같게 가져가고 싶다"였다.

플랜비 플로우는 다음 조각들로 구성된다: ①여행 생성, ②장소 검색·상세(AI
리뷰요약)·즐겨찾기, ③장소별 방문시간·이동수단·메모 등록, ④일정
상세(Day탭+지도+타임라인), ⑤일정 아이템별 "대안찾기"(필터+AI후보+미리보기
+교체), ⑥빈 시간 대안찾기, ⑦날씨 경보 자동 대응. 전체를 한 번에 스펙
짜기엔 범위가 커서 사용자와 함께 조각 단위로 쪼갰고, 이번 스펙은 그중
**②+③(장소등록 플로우)**만 다룬다. ④(Day탭+지도+타임라인)는 이미
`TripDetailView`로 구현돼 있어 큰 변경이 없고, ⑤~⑦은 별도 스펙으로
분리한다.

## 범위

이 스펙은 `TripService.addPlaceToDay`로 대표되는 "여행에 장소를 수동으로
추가하는" 경로만 다룬다. 영상에서 자동으로 장소가 추출되는
`confirmVideoPlacesIntoTrip`(VIDEO 출처) 경로는 건드리지 않는다 — 이미
좌표까지 확보된 상태로 들어오므로 검색이 필요 없다.

## 결정 사항

브레인스토밍 과정에서 확정한 것:

- **검색 제공자: 카카오 → 구글 플레이스로 전환.** 카카오 로컬 API에는
  평점·리뷰·영업시간을 주는 엔드포인트가 없어서, "AI가 요약한 리뷰"를
  보여주는 플랜비 플로우를 재현하려면 구글 플레이스(Details API 포함)가
  사실상 유일한 선택지다. 카카오맵 웹의 리뷰는 스크래핑해야 하는데, 이미
  네이버 리뷰 스크래핑을 피하기로 한 것과 같은 이유(ToS 위반 가능성,
  차단 위험)로 배제한다.
- **Place Details API는 유료 티어** — 사용자가 "상세보기"를 실제로 누를
  때만 호출하고, 결과(리뷰 요약)는 `Place`에 영구 캐시해서 같은 장소를
  다시 조회해도 API를 또 부르지 않는다.
- **방문시간/이동수단/메모는 전부 선택 입력** — 플랜비처럼 모달로 강제
  입력받지 않는다. 장소만 고르면 바로 등록되고, 나머지는 타임라인
  카드에서 나중에 편하게 채운다. 트루바 기존 화면들(`ItineraryView`,
  `TripDetailView`)의 "모달 없이 바로바로 수정" 철학과 통일한다.
- **찜한 장소 빠른 추가 포함** — 검색 탭 옆에 "찜한 장소" 탭을 둬서,
  검색 없이 북마크한 장소를 바로 여행에 추가할 수 있게 한다.

## 아키텍처

### 데이터 모델

**`Place`** (기존 추천엔진 카탈로그 엔티티, 그대로 재사용) — 필드 2개 추가:

```
Place (기존 필드 생략)
+ reviewSummary          (TEXT, nullable)      -- Gemini가 리뷰를 요약한 결과, 한 번 생성하면 영구 캐시
+ reviewSummaryGeneratedAt (TIMESTAMP, nullable) -- 캐시 신선도 판단용. 지금은 갱신 정책 없이 "생성되면 끝"
```

**`TripPlace`** — 필드 4개 추가, 전부 nullable(VIDEO 출처 데이터는 계속 비워둠):

```
TripPlace (기존 필드 생략)
+ googlePlaceId       (VARCHAR, nullable)  -- NORMAL 출처로 새로 추가되는 장소만 채워짐.
                                              기존 savedPlaceId(VIDEO 역참조)와는 별개 필드로 둬서
                                              두 제공자의 ID 체계가 섞이지 않게 한다.
+ visitStartTime      (TIME, nullable)
+ visitEndTime        (TIME, nullable)
+ arrivalTransportMode (VARCHAR, enum: WALK|TRANSIT|CAR, nullable)
                                              -- "이 장소에 도착할 때 이전 장소에서 쓴 이동수단".
                                                하루의 첫 장소는 항상 null.
+ memo                (TEXT, nullable)
```

`ddl-auto: update`이므로 컬럼 추가는 앱 재시작 시 Hibernate가 자동
반영한다. 전부 nullable이라 기존 로우에 영향 없다.

**`BookmarkResponse`** (DTO) — `googlePlaceId` 필드 추가. 찜한 장소를
검색 없이 바로 여행에 추가하려면 프론트가 이 값을 알아야 한다.

### Google Places 연동 확장

`GooglePlacesApiClient`에 메서드 2개 추가:

- **`searchText(String query)`** — Text Search (New) API
  (`POST /v1/places:searchText`). 필드마스크는 기존 `searchNearby`와
  동일한 Basic+Pro 티어(`places.id, places.displayName, places.types,
  places.rating, places.userRatingCount, places.priceLevel,
  places.location, places.formattedAddress`) — 추가 비용 없음.
- **`getDetails(String googlePlaceId)`** — Place Details (New) API
  (`GET /v1/places/{id}`), 필드마스크에 `reviews`(텍스트+평점+작성자)
  추가. Atmosphere 데이터 포함이라 **유료 티어** — 호출 지점을
  `PlaceReviewService`로 한정해서 무분별한 호출을 막는다.

두 메서드 모두 기존 `searchNearby`와 동일한 재시도 정책
(3회, 지수 백오프, `HttpClientErrorException.TooManyRequests` /
`HttpServerErrorException` / `ResourceAccessException`에서만 재시도)을
따른다.

**`PlaceReviewService`(신규)**:

```
getOrGenerateSummary(Long placeId) -> String
  1. Place.reviewSummary가 있으면 그대로 반환 (API 호출 없음)
  2. 없으면 getDetails() 호출
     - 리뷰가 없으면 "리뷰 정보 없음" 고정 문구 반환 (Gemini 호출 생략)
     - 리뷰가 있으면 리뷰 텍스트를 모아 Gemini에 "2~3문장으로 요약해줘" 요청
       -> 결과를 Place.reviewSummary/reviewSummaryGeneratedAt에 저장 후 반환
```

Gemini 호출은 이 프로젝트의 기존 관례를 그대로 따른다 — 지금까지의 모든
Gemini 연동(`extract_places.py`, `verify_places.py`,
`select_place_match.py`, `generate_itinerary.py`, `tag_places.py`)이
전부 "Python 스크립트 + Java record + `OutputParser` + `*Runner`
(`ProcessBuilder`, stderr `TROVA_API_LOG:` 마커)" 패턴이고, 트루바
백엔드에는 Gemini를 직접 호출하는 Java HTTP 클라이언트가 없다(직접
클라이언트를 새로 만드는 건 이 패턴을 깨는 것이라 채택하지 않음). 리뷰
요약도 동일한 패턴으로 신설한다:

- `pipeline-test/summarize_reviews.py` (신규) — 리뷰 텍스트 배열을
  입력받아 Gemini에 "2~3문장으로 요약해줘" 요청, `{"summary": "..."}`
  형태로 stdout에 출력. `application.yml`에
  `app.pipeline.summarize-reviews-script-path` 키 추가(다른 스크립트
  경로들과 동일한 패턴).
- `ReviewSummary` (record) + `ReviewSummaryOutputParser`
  (Jackson, `FAIL_ON_UNKNOWN_PROPERTIES=false`)
- `ReviewSummaryRunner` (`PlaceTaggingRunner`와 동일 골격: 임시
  워크디렉터리에 입력 JSON 작성 → `ProcessBuilder`로 `python3` 실행 →
  stdout 파싱, stderr는 항상 `apiCallLogService.recordFromStderr(...)`
  로 기록 → 2분 타임아웃 → `finally`에서 임시 파일 정리)

### API 엔드포인트

| 메서드/경로 | 설명 |
|---|---|
| `GET /api/places/search?query=...` (신규) | `searchText` 결과를 `Place`에 upsert(기존 `RecommendationService.upsert` 패턴 재사용)하고 후보 리스트 반환 |
| `GET /api/places/{id}/details` (신규) | `PlaceReviewService.getOrGenerateSummary` 호출, 리뷰요약 포함 상세 반환 |
| `POST /api/trips/{tripId}/days/{day}/places` (기존, **요청 바디 변경**) | `{query: string}` → `{googlePlaceId: string}`. 카카오 기반 즉시등록 로직을 완전히 대체(다른 소비자 없음, 하위호환 유지 안 함) |
| `PATCH /api/trip-places/{id}/details` (신규) | `{visitStartTime?, visitEndTime?, arrivalTransportMode?, memo?}` 부분 업데이트. 전부 optional이라 하나만 보내도 그것만 반영 |
| `PATCH /api/trip-places/{id}/order`, `DELETE /api/trip-places/{id}` | 변경 없음 |

`TripService.addPlaceToDay`는 `query` 대신 `googlePlaceId`를 받는다.
검색 단계(`GET /api/places/search`)에서 이미 `Place`에 upsert가 끝난
상태이므로, 이 메서드는 Google API를 다시 호출하지 않고
`placeRepository.findByGooglePlaceId(googlePlaceId)`로 조회한
`Place` 로우의 이름/좌표/주소만 읽어 `TripPlace`를 생성한다(존재하지
않으면 404 — 에러 처리 절 참고). 기존 `KakaoLocalApiClient` 의존은 이
메서드에서는 제거되고(다른 메서드는 계속 카카오를 쓰므로 `TripService`
자체에서 의존성을 없애지는 않음), `searchFirst` 헬퍼는 삭제한다.

### 프론트엔드 (`TripDetailView`)

기존 "장소 이름으로 검색" 입력 한 줄을 아래로 교체:

1. **검색 / 찜한 장소** 탭
   - 검색 탭: 입력 시 `GET /api/places/search` 호출, 후보 리스트를
     `RecommendedPlaceCard`와 톤을 맞춘 카드로 표시(이름/주소/평점/리뷰수)
   - 찜한 장소 탭: `listBookmarks()` 결과를 그대로 리스트로 표시
2. 각 카드 공통: **상세보기**(눌렀을 때만 `GET /api/places/{id}/details`
   호출, 펼치면 리뷰요약 텍스트 표시, 한 번 펼치면 다시 안 부름) /
   **하트**(기존 `addBookmark(placeId)` 재사용, 검색 탭에서만 노출 —
   찜한 장소 탭은 이미 찜한 상태이므로 해제 버튼) / **추가**(검색
   탭이면 `addTripPlace(tripId, day, googlePlaceId)`, 찜한 장소 탭도
   동일 함수를 그 장소의 `googlePlaceId`로 호출)
3. 추가된 `TripPlace` 카드에 방문시간·이동수단·메모 표시 — 비어있으면
   "시간 추가" / "이동수단 추가" / "메모 추가" 같은 옅은 텍스트
   버튼으로 두고, 누르면 그 자리에서 인라인 입력으로 바뀌어
   `PATCH /api/trip-places/{id}/details` 호출(모달 없음, 기존
   낙관적 업데이트 패턴 재사용).

### 비용 / 관측성

- `Place Details` 호출은 `PlaceReviewService`에서
  `apiCallLogService.record(...)`로 직접 로깅(provider="google-places",
  operation="place-details") — `KakaoGeocodingService.search()`와 동일한
  방식.
- Gemini 리뷰요약 호출은 `ReviewSummaryRunner`가
  `apiCallLogService.recordFromStderr(...)`로 로깅(provider/operation은
  스크립트의 `TROVA_API_LOG:` 마커에 포함) — 다른 파이프라인 Runner들과
  동일한 방식.
- 두 경로 모두 `ApiCallLogService.record(...)`가 이미 Micrometer Timer를
  감싸고 있어 Prometheus에도 자동으로 잡힌다 — 기존 관측성 인프라
  그대로 재사용, 추가 설정 불필요.
- `docs/benchmarks/`에 실제 사용 후 Details API 호출 횟수·캐시 히트율을
  실측으로 기록한다(포트폴리오 원칙 — 감으로 정한 수치 금지, 실측/추정
  구분 명시).

## 에러 처리

- `searchText` 결과가 비어있으면 프론트에 빈 배열 반환(에러 아님) —
  기존 검색 실패 UX("장소를 찾지 못했어요")와 동일하게 처리.
- `getDetails` 실패(네트워크/429 등, 재시도 3회 소진) 시 리뷰요약
  생성을 건너뛰고 "리뷰를 불러오지 못했어요" 문구 반환 — `Place`에는
  아무것도 캐시하지 않아 다음 요청에서 다시 시도 가능.
- `addPlaceToDay`에 존재하지 않는 `googlePlaceId`가 오면 404(현재
  `Optional` 체이닝 패턴 유지).
- `PATCH .../details`는 모든 필드가 optional이라 빈 바디도 유효 요청
  (아무것도 안 바뀜, 200 반환) — 검증 로직 불필요.

## 테스트 전략

- 단위: `PlaceReviewService.getOrGenerateSummary` — 캐시 히트/미스,
  리뷰 없음, Details API 실패 각각의 분기.
- 단위: `GooglePlacesApiClientImpl.searchText`/`getDetails` — 재시도
  정책이 `searchNearby`와 동일하게 동작하는지(기존 테스트 패턴 재사용).
- 통합: `TripService.addPlaceToDay(googlePlaceId)` — 신규 googlePlaceId
  기반 등록, 다른 사용자 소유 여행에 추가 시도 시 거부.
- 통합: `PATCH /api/trip-places/{id}/details` — 부분 업데이트(필드
  일부만 보냈을 때 나머지 유지), 다른 사용자 소유 장소 수정 거부.
- 수동: 실제 브라우저로 검색→상세보기(리뷰요약)→찜하기→추가→시간/
  이동수단/메모 입력까지 전체 왕복 확인.

## 커밋 전 확인

CLAUDE.md 규칙대로 `./gradlew build` 실행 (백엔드), 프론트엔드는
`npx next build` + `npx tsc --noEmit`.
