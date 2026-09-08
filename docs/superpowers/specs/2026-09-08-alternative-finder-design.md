# 대안 찾기(플랜비 ⑤~⑦) 설계

날짜: 2026-09-08
상태: 승인됨 (브레인스토밍 완료)

## 배경

`docs/superpowers/specs/2026-09-05-trip-place-search-flow-design.md`가 플랜비
참고 플로우 ①~⑦ 중 ②+③(장소등록)만 다루고 "⑤~⑦은 범위가 커서 별도 스펙으로
분리한다"고 남겨뒀던 나머지 조각이다.

플랜비 시연 영상(`LJRCXCwBTDk`) 자막을 다시 확인해 정확한 흐름을 잡았다:

- **⑤ 일정 장소별 대안 찾기**: 일정의 장소 카드마다 "대안 찾기" 버튼 → 필터(이동
  수단/이동 시간/다음 장소와의 거리/카테고리/실내·실외) 설정 → 필터에 맞는 AI 후보
  추천 → 후보별 AI 리뷰 요약 확인 → "미리보기"(이 대안으로 바꾸면 일정이 어떻게
  바뀌는지) → 교체
- **⑥ 빈 시간 대안 추천**: 일정에 빈 시간이 있으면 이전/다음 장소 사이에 갈 만한
  곳을 추천
- **⑦ 날씨 경보**: 경보가 뜨면 대안 추천 결과가 뜨고 바로 "교체"로 이어진다 — 별도
  기능이 아니라 ⑤의 진입점 중 하나

Trova는 이미 ⑦의 일부(날씨 감지 + 알림 생성)를 `WeatherRecoveryService`/
`Notification`으로 구현해뒀지만, 대안을 카카오 키워드 검색으로 자체 계산해서
알림에 미리 박아두기만 하고 화면에서 보여주지도, 교체로 이어지지도 않는다. 이
스펙은 ⑤⑥⑦을 하나의 메커니즘으로 통합해서 완성한다.

플랜비 실제 백엔드(`~/IdeaProjects/plan-b-server`)는 `ScoringStrategy`,
`CongestionService`, `OpeningHoursService`, `OpenAiAnalysisService`, 구글+네이버+
인스타 3소스 조합 등 훨씬 정교하지만, 브레인스토밍에서 "플랜비와 거의 동일하게"로
확정하되 아래 절에서 각 조각의 구체적 구현 방식은 Trova의 기존 인프라(구글
Places, Gemini 태깅, 카카오)에 맞춰 재구성했다.

## 범위

포함:
- 장소별 대안 후보 검색(필터 5종) + 리뷰 요약 + 미리보기 + 교체
- 빈 시간(시간이 입력된 연속 장소 사이 공백) 대안 추천 + 중간 삽입
- 날씨 알림을 대안 찾기 화면으로 연결(기존 카카오 기반 사전 계산 제거)
- 서울 실시간 도시데이터 API로 혼잡도 배지(해당 장소만, best-effort)

제외(YAGNI):
- `ScoringStrategy` 같은 자체 랭킹 알고리즘 — 구글 Text/Nearby Search가 반환하는
  순서 + 우리가 계산한 거리/시간 필터만 쓴다
- 네이버/인스타그램 소스 조합 — 구글 Places 하나로 통일(0-1 원칙, provider 혼용
  방지)
- 실제 도보/대중교통 경로 API(예: 카카오 모빌리티, 구글 Directions) — 전부
  haversine 직선거리 + 평균 속도 환산 근사치. 정확도가 필요해지면 별도 스펙.
- 혼잡도 전국 커버 — 서울 116~121곳 밖은 그냥 배지 없음

## 결정 사항 (브레인스토밍에서 확정)

- **후보 검색: 구글 Places 재사용.** 이미 리뷰/평점/카테고리 데이터가 있어
  필터링 품질이 카카오보다 좋다. 호출량이 늘어날 수 있어(대안 찾기를 여러 번
  누를 수 있음) `docs/benchmarks/`에 실사용 후 호출 횟수를 실측 기록한다(포트폴리오
  성능 근거 원칙 — 추정치 아님을 명시).
- **필터 5개 전부 구현.** 이동 수단/이동 시간/다음 장소와의 거리는 haversine
  직선거리를 이동수단별 평균 속도(도보 4km/h, 대중교통 20km/h, 차량 30km/h —
  실측 아닌 통상적 추정치, 스펙에 명시)로 환산한 근사치다. 실제 경로 API가
  아니므로 도심 도로망을 반영 못 하는 한계를 문서화한다.
- **빈 시간은 "시간 입력된 장소만" 대상.** `visitStartTime`/`visitEndTime`이
  둘 다 있는 연속 장소 쌍만 gap 계산 대상. 하나라도 비어있으면 그 쌍은 건너뛴다
  — 임의 추정값을 넣지 않는다.
- **교체 시: 시간/이동수단 유지, 메모만 초기화.** 같은 시간대에 다른 장소를
  가는 거라 시간/이동수단은 여전히 유효하다고 보고, 메모는 원래 장소 기준으로
  쓰였을 가능성이 커서 비운다.
- **혼잡도: 서울 116~121곳 한정 무료 API를 한계 감안하고 채택.** 해당 안 되는
  장소는 배지를 아예 안 보여준다(빈 칸/에러 표시 없음).
- **미리보기는 새 API 없이 클라이언트 계산.** 후보 목록 응답에 이미 좌표·거리
  변화 계산이 다 들어있어서, 앱에서 기존 지도 컴포넌트로 바로 그린다.

## 아키텍처

### 데이터 모델 변경

**`Notification`** — `alternatives`(임베디드 리스트) 필드 제거, `tripPlaceId`
(nullable false, 경보를 유발한 실외 장소) 필드 추가:

```
Notification (기존 title/body/precipitationProb/isRead/createdAt 유지)
- alternatives  (제거)
+ tripPlaceId   (BIGINT, nullable false) — 이 장소의 대안 찾기 화면으로 딥링크
```

**`NotificationAlternative`** — 엔티티/임베디드 테이블(`notification_alternatives`)
전체 삭제. `WeatherRecoveryService.findIndoorAlternatives()`(카카오 키워드
검색 기반)도 함께 삭제 — 이제 대안 검색은 아래 "대안 후보 검색 API" 하나로
통일된다.

`ddl-auto: update`라 컬럼 추가/제거는 재시작 시 Hibernate가 반영한다.
`notification_alternatives` 테이블은 `update` 모드에서 자동으로 drop되지
않으므로, 마이그레이션 스크립트나 수동 `DROP TABLE`이 필요하다는 점을 구현
태스크에 명시한다(트루바는 별도 마이그레이션 도구가 없다 — 로컬/개발 DB라
수동 drop으로 충분).

### 대안 후보 검색

**`GooglePlacesApiClient` 확장** — 기존 `searchNearby(lat, lng, radiusMeters)`가
위치 기반이지만 카테고리 필터가 없다. Google Places API v1의 `searchNearby`가
지원하는 `includedType`을 추가해서 카테고리 필터를 얹는다:

```java
GooglePlacesNearbySearchResponse searchNearby(
    double latitude, double longitude, double radiusMeters, String includedType /* nullable */);
```

기존 `searchNearby(lat, lng, radius)` 3-인자 오버로드는 `includedType=null`로
위임하는 형태로 남겨서 기존 호출부(추천엔진)는 변경 없음.

**`AlternativeFinderService`(신규)**:

```
findAlternatives(User user, Long tripPlaceId, AlternativeFilter filter) -> List<AlternativeCandidate>

AlternativeFilter(
    String category,          // nullable, Place.category와 텍스트 매치
    Boolean indoorOnly,       // nullable, true면 Place.space == "INDOOR"만
    Double maxDistanceKm,     // nullable, 다음 장소까지 직선거리 상한
    Integer maxTravelMinutes, // nullable, transportMode 기준 환산 이동시간 상한
    TransportMode transportMode // nullable, maxTravelMinutes 계산에만 씀
)

AlternativeCandidate(
    Long placeId, String googlePlaceId, String name, String category,
    Double rating, Integer userRatingCount, Double latitude, Double longitude, String address,
    Double distanceToNextKm,      // nullable, 다음 장소가 있을 때만
    Integer estimatedTravelMinutes, // nullable, transportMode 지정 시만
    Boolean isCongestionAvailable, String congestionLevel // nullable, 서울 목록 매치 시만
)
```

**카테고리 → `includedType` 매핑**: Google Places API v1의 `includedType`은
고정된 타입 열거값(예: `cafe`, `restaurant`, `museum`, `tourist_attraction`)만
받고 자유 텍스트를 못 받는다. `AlternativeFilter.category`는 사용자가 자유
입력한 텍스트이므로, 정적 매핑 테이블(`CATEGORY_TO_GOOGLE_TYPE`, 예:
"카페"→"cafe", "맛집"/"음식점"→"restaurant", "박물관"→"museum", "쇼핑"→
"shopping_mall" 등 자주 쓰는 10~15개만 우선 매핑)에 있으면 `includedType`으로
넘기고, 매핑에 없으면 `includedType` 없이 반경 검색만 수행한 뒤 5번 단계처럼
`Place.category`/`Place.name` 부분 문자열 매치로 후처리 필터링한다 — 매핑
누락으로 검색 자체가 실패하는 일은 없게 한다.

처리 순서:
1. `tripPlaceId`로 교체 대상 `TripPlace` 조회(소유자 확인, 404 아니면 진행)
2. `searchNearby(대상 좌표, radius=2000m, includedType=매핑되면 그 값, 아니면 null)`
   호출 — 반환 결과를 `placeCatalogService.upsertAll(...)`로 `Place`에
   upsert(기존 패턴 재사용)
3. `indoorOnly`가 true면 각 후보의 `Place.space` 확인 — null이면(태깅 전)
   `PlaceTaggingRunner`로 온디맨드 태깅(기존 `WeatherRecoveryService.ensureTagged`
   패턴과 동일하게 이름+카테고리만 넘겨서 배치 태깅) 후 필터
4. 같은 `Itinerary`에서 이 장소 다음 순서(`visitOrder+1`)의 `TripPlace`가 있으면
   그 좌표까지 haversine 거리 계산 → `distanceToNextKm`. `transportMode`가
   있으면 평균 속도로 나눠 `estimatedTravelMinutes` 계산. `maxDistanceKm`/
   `maxTravelMinutes` 필터는 이 값 기준으로 후처리 필터링(다음 장소가 없으면
   두 필터 모두 무시하고 통과)
5. `includedType`으로 못 걸렀으면(매핑 없던 경우) `category` 텍스트로
   `Place.category`/`Place.name` 부분 문자열 매치 후처리 필터링
6. 서울 혼잡도 API(아래 절)에서 후보 이름이 매치되면 배지 필드 채움 — API
   실패/타임아웃 시 그냥 `isCongestionAvailable=false`로 무시(전체 요청은
   실패시키지 않음)

**엔드포인트**: `GET /api/trip-places/{id}/alternatives?category=&indoor=&maxDistanceKm=&maxTravelMinutes=&transportMode=`
— 전부 optional 쿼리 파라미터. 응답은 `List<AlternativeCandidateResponse>`
(위 레코드를 그대로 DTO화).

### 서울 혼잡도 연동

**`SeoulCongestionApiClient`(신규)** — 서울 열린데이터광장 "서울시 실시간
도시데이터"(`data.seoul.go.kr/SeoulRtd/`) API를 감싼다. 앱 시작 시 116~121개
장소명 목록을 한 번 캐싱(`@Scheduled` 6시간 주기 갱신 — 장소 목록 자체는
자주 안 바뀜)해두고, 후보 이름과 정확히/부분 일치하는 게 있으면 그 장소의
실시간 혼잡도(`AREA_CONGEST_LVL`: 여유/보통/약간 붐빔/붐빔)를 조회한다.

- API 키는 서울 열린데이터광장 회원가입 후 발급(무료) — `.env`에
  `SEOUL_OPENDATA_API_KEY` 추가
- 매치 안 되는 후보는 API를 아예 호출하지 않는다(불필요한 호출 방지)
- 실패(타임아웃/키 만료 등)는 `WeatherRecoveryService`의 기존 관례처럼
  로그만 남기고 조용히 무시(대안 목록 자체는 정상 반환)

### 교체 API

```
POST /api/trip-places/{id}/replace
Body: { googlePlaceId: string }
```

`TripService.replacePlace(User user, Long tripPlaceId, String googlePlaceId)`:
1. `tripPlaceId` 소유자 확인
2. `placeRepository.findByGooglePlaceId(googlePlaceId)` — 없으면 404(대안
   후보 검색 단계에서 이미 upsert됐어야 정상 경로이므로, 없으면 클라이언트가
   검증 안 된 id를 보낸 것으로 간주)
3. 기존 `TripPlace` 행을 그대로 두고 `placeName`/`region`/`category`/
   `latitude`/`longitude`/`address`/`googlePlaceId`만 새 `Place` 값으로 갱신,
   `memo`는 null로 초기화. `visitOrder`/`visitStartTime`/`visitEndTime`/
   `arrivalTransportMode`/`source`(NORMAL로 통일)/`savedPlaceId`(null로,
   더 이상 원본 영상 장소와 무관)는 유지
4. `TripPlace`에 `applyReplacement(Place)` 메서드 추가(기존 `applyDetails`
   패턴과 동일한 부분 갱신 스타일)

### 빈 시간 대안 추천

**`GapRecommendationService`(신규)**:

```
findGaps(User user, Long tripId, int day) -> List<Gap>
Gap(TripPlace before, TripPlace after, int gapMinutes, LatLng midpoint)
```

- 그 날의 `TripPlace`를 `visitOrder` 순으로 순회하며, 연속한 두 장소 모두
  `visitEndTime`(앞 장소)/`visitStartTime`(뒷 장소)이 있고 `visitStartTime -
  visitEndTime > 30분`(임계값, 실측 아닌 통상적 여유값 — 짧은 이동 텀까지
  "빈 시간"으로 잡지 않기 위함)이면 gap으로 판정
- 각 gap마다 두 장소 좌표의 중간지점으로 `searchNearby(midpoint, 1500m,
  includedType=null)` 호출 → 후보 목록(대안 후보 검색과 같은 upsert 패턴,
  거리/혼잡도 계산은 생략 — 필터 없이 순수 추천이라 단순하게)

**엔드포인트**: `GET /api/trips/{tripId}/days/{day}/gap-recommendations` →
`List<GapResponse>`(각 gap의 앞/뒤 장소 id, gap 분, 추천 후보 목록)

**중간 삽입**: 기존 `addPlaceToDay`는 항상 맨 끝(`siblings.size()+1`)에
추가한다. 빈 시간에 삽입하려면 특정 위치에 끼워넣어야 하므로 새 메서드
추가:

```
TripService.insertPlaceAfter(User user, Long afterTripPlaceId, String googlePlaceId) -> Optional<TripPlace>
```

`afterTripPlaceId`가 속한 `Itinerary`의 `visitOrder > afterTripPlace.visitOrder`
인 모든 `TripPlace`의 `visitOrder`를 1씩 밀고(`applyVisitOrder(o+1)`,
`saveAll`), 새 장소를 `afterTripPlace.visitOrder + 1`로 삽입한다.

### 날씨 알림 연동

`WeatherRecoveryService.checkAndNotify`에서 `findIndoorAlternatives(...)` 호출과
`NotificationAlternative` 생성 부분을 제거하고, 대신 감지된 실외 장소
(`reference`)의 `tripPlaceId`를 `Notification`에 저장:

```java
Notification notification = new Notification(
        itinerary.getTrip().getUser(), itinerary, "비 소식이 있어요",
        String.format("%d일차(%s)에 강수확률 %.0f%%예요. %s 근처 실내 대안을 확인해보세요.",
                itinerary.getDay(), itinerary.getDate(), maxPop * 100, reference.getPlaceName()),
        maxPop, reference.getId());
```

`NotificationController.NotificationResponse`에 `tripPlaceId` 필드 추가,
`alternatives` 필드 제거.

### API 엔드포인트 요약

| 메서드/경로 | 설명 |
|---|---|
| `GET /api/trip-places/{id}/alternatives` (신규) | 필터 기반 대안 후보 검색 |
| `POST /api/trip-places/{id}/replace` (신규) | 대안으로 교체 |
| `GET /api/trips/{tripId}/days/{day}/gap-recommendations` (신규) | 빈 시간 추천 |
| `POST /api/trip-places/insert` (신규, `{afterTripPlaceId, googlePlaceId}`) | 빈 시간 추천 결과를 중간에 삽입 |
| `GET /api/notifications` (기존, 응답 변경) | `alternatives` 제거, `tripPlaceId` 추가 |

### 프론트엔드 (앱)

- `TripDetailScreen`의 `PlaceRow`에 "대안 찾기" 진입점 추가(⋮ 메뉴 또는 아이콘
  버튼) — 저장 장소 화면에서 이미 쓴 ⋮ 더보기 메뉴 패턴 재사용
- 신규 `AlternativeFinderSheet` 컴포넌트: 필터 폼(카테고리 텍스트 입력, 실내/
  실외 토글, 이동수단 선택 + 거리/시간 슬라이더) → `PlaceRow`와 톤을 맞춘 후보
  카드 목록(이름/평점/거리/혼잡도 배지 있으면 표시, "리뷰 보기"는 기존
  `PlaceReviewContent` 재사용) → 후보 선택 시 미리보기(기존 `InlineMap`에 기존
  핀은 회색, 새 핀은 강조색으로 같이 그려서 비교, 총 이동거리 변화 텍스트) →
  "이 장소로 교체" 확정 버튼
- 빈 시간 카드: `TripDetailScreen`의 장소 리스트에서 연속 장소 사이 gap이 있는
  자리에 "이 사이 갈 곳 추천받기" 카드 삽입 — 탭하면 같은 스타일의 후보 목록
  시트(필터 없이 바로 추천 결과만)
- 날씨 알림 배너: 브레인스토밍 초반에 설계했던 `WeatherAlertBanner`를 그대로
  쓰되, "확인"이 아니라 탭하면 그 `tripPlaceId`의 `AlternativeFinderSheet`를
  실내 필터 체크 상태로 바로 연다(홈 탭 + 여행 상세 상단, 두 곳 공용 컴포넌트)

## 에러 처리

- 대안 후보 검색 결과 0개 — 에러 아님, 빈 배열 반환. 앱은 "조건에 맞는 대안을
  찾지 못했어요" 표시
- `searchNearby` 실패(재시도 3회 소진) — 500 대신 빈 배열 반환하고 로그만
  남김(대안 찾기는 부가 기능이라 검색 실패로 화면 전체가 죽으면 안 됨)
- `replace`에 존재하지 않거나 검증 안 된 `googlePlaceId` — 404
- `insertPlaceAfter`에 다른 사용자 소유 `afterTripPlaceId` — 404
- 서울 혼잡도 API 실패 — 항상 무시(위 서술)

## 테스트 전략

- 단위: `AlternativeFinderService` — 카테고리/실내외/거리/시간 필터 각각의
  분기, 다음 장소 없을 때 거리·시간 필터 무시, 태깅 안 된 후보 온디맨드 태깅
- 단위: `GapRecommendationService` — 30분 임계값 경계, 시간 없는 장소 쌍은
  건너뜀
- 단위: `TripService.replacePlace` — 시간/이동수단 유지·메모 초기화 검증
- 단위: `TripService.insertPlaceAfter` — 중간 삽입 시 뒤 장소들의 visitOrder가
  올바르게 밀리는지
- 통합(컨트롤러): 대안 검색/교체/빈 시간 추천/삽입 전부 다른 사용자 소유
  리소스 접근 시 404
- 수동: 실제 여행에서 대안 찾기 → 필터 조합 → 미리보기 → 교체 왕복, 빈 시간
  추천 → 삽입, 날씨 알림 → 대안 찾기 연결까지 전체 확인

## 커밋 전 확인

CLAUDE.md 규칙대로 `./gradlew build`. 앱은 `npx tsc --noEmit`.

## 관측성 / 비용

- 대안 후보 검색의 `searchNearby` 호출은 기존 `ApiCallLogService.record(...)`
  패턴으로 로깅(provider="google-places", operation="nearby-search-alternative")
- 서울 혼잡도 API 호출도 동일 패턴으로 로깅(provider="seoul-opendata")
- `docs/benchmarks/`에 대안 찾기 기능 배포 후 실사용 기준 구글 API 호출
  횟수·비용을 실측 기록(포트폴리오 원칙 — 지금 이 스펙 문서의 숫자들은 전부
  설계 시점 추정치임을 명시)
