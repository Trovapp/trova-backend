# 빈 시간 추천 SQL 20회 분석과 P7 개선 (#482)

작성: 2026-11-19, T20. 기준 `origin/main` `8dc5db5`. 운영 DB·서버·외부 API 키·루트 `.env`는 쓰지 않았다. 수치는 모두 **로컬 Mac, H2(PostgreSQL 모드)** 실측이고, 운영 효과는 **예측**이다.

## 결론

- 운영 P4 표본(2026-11-19 15:28~15:48, 35건)의 요청당 SQL **20.15회**는 대부분 **후보 장소마다 반복되는 개인화 조회(N+1)** 다. 빈 시간 하나마다 Google 근처 검색 결과 후보(최대 20개) 각각에 대해 `findEmbeddingText` + `findTopSimilarSignals` 2번씩 DB를 오갔다.
- 그 밖에 여행·일차·장소를 엔티티로 읽으면서 기본 EAGER 연관(일차→여행, 장소→일차)을 다시 읽어 데이터 읽기에 **6회**가 들었다.
- 고친 뒤(로컬 H2, 예열 JWT): 빈 시간 없음 **5~6→1회**, 빈 시간 1개는 후보 수와 상관없이 **5회 고정**(이전 후보 1/5/20개에 10/14/29회), 빈 시간 1/2/3개(후보 20개)는 **29/52/75→5/9/13회**. 응답 JSON·추천 순서·필터는 그대로이고 외부 API 호출 수는 바뀌지 않았다.

## 요청 경로 (변경 전)

`TripController.gapRecommendations`(`controller/TripController.java:581`) → `CurrentUserService.resolve` → `DailyQuotaService.consumePlaceCall`(메모리) → `GapRecommendationService.findGaps`.

1. 데이터 읽기: `tripRepository.findById` → 소유자 비교 → `itineraryRepository.findByTripAndDay` → `tripPlaceRepository.findByItineraryOrderByVisitOrder`.
2. 장소 사이(30분 초과, 시간·좌표 있음)와 마지막 장소 뒤~21:00마다 `recommend` 한 번. `recommend` 안에서:
   - Google Nearby Search(외부, 점심·저녁이 걸치면 식당만) → 호출 기록 INSERT.
   - `PlaceCatalogService.upsertAll`: googlePlaceId로 카탈로그 묶음 조회(없는 것만 INSERT … ON CONFLICT 후 재조회).
   - `PlaceEmbeddingService.ensureEmbeddings`: 임베딩 있는 id 묶음 조회 → 없는 것만 Gemini 묶음 임베딩(외부) → 묶음 UPDATE.
   - 자기 자신(같은 googlePlaceId)·숙소·역 등(`NOT_A_STOP`) 제외.
   - **후보마다** `PersonalizationService.personalizationScore` → `findEmbeddingText`(후보 임베딩을 text로) → 있으면 `findTopSimilarSignals`(이 사용자 신호 중 가까운 5개, pgvector).
   - `max(baseScore, 1.0) × (1 + 개인화 × 0.6)` 내림차순 정렬(안정 정렬).
3. 캐시: 요청 단위 캐시는 없다. 장소 카탈로그(googlePlaceId)와 임베딩은 DB에 남아 다음 요청부터 INSERT·Gemini 호출이 생략된다. 개인화 조회는 매번 한다.

### 실제 SQL (H2, StatementInspector, 예열 JWT, 장소 2개·빈 시간 1개·후보 3개)

| # | SQL 개요 | 원인 |
|---|---|---|
| 1 | `select … from users where id=?` | `CurrentUserService.resolve`. 사용자 ID만 쓰는데 매번 사용자 행을 읽음 |
| 2 | `select … from trips join users … where t.id=?` | `findById`, `Trip.user` 기본 EAGER |
| 3 | `select … from itineraries where trip_id=? and day_number=?` | `findByTripAndDay` |
| 4 | `select … from trips join users … where t.id=?` | 3의 `Itinerary.trip` EAGER. 트랜잭션이 없어 2를 재사용하지 못함 |
| 5 | `select … from trip_places where itinerary_id=? order by visit_order` | 장소 목록 |
| 6 | `select … from itineraries join trips join users … where i.id=?` | 5의 `TripPlace.itinerary` EAGER(장소가 0개면 생략 → 5회) |
| 빈 시간마다 +1 | `insert into api_call_logs …` | Google 호출 기록 |
| 빈 시간마다 +1 | `select … from places where google_place_id in (…)` | 카탈로그 조회(처음 보는 후보면 INSERT·재조회 추가) |
| 빈 시간마다 +1 | `SELECT id FROM places WHERE id IN (…) AND embedding IS NOT NULL` | 임베딩 존재 확인(없는 장소가 있으면 Gemini 묶음 + 묶음 UPDATE) |
| **후보마다 +1~2** | `SELECT embedding::text FROM places WHERE id=?` → `SELECT … FROM user_preference_signals … ORDER BY embedding <=> … LIMIT 5` | 개인화 N+1. 운영 Postgres는 임베딩 있는 후보마다 2회, H2는 embedding 칸이 없어 첫 SQL이 실패하고 0점 폴백이라 1회 |

정리하면 (예열 JWT) **6 + 빈 시간 수 × (3 + 걸러진 후보 수 × 1~2)**. 운영 평균 20.15는 예를 들어 빈 시간 1개 + 임베딩 있는 후보 5~6개(6+3+10~12 = 19~21)와 맞는 크기다. 운영 표본에서 빈 시간·후보 수 분포를 직접 재지는 않았으므로 이 분해는 추정이다.

### 로컬에서 늘려 본 결과 (변경 전, H2)

| 상황 | SQL/요청 |
|---|---|
| 빈 시간 없음, 장소 0/1/5/20개 | 5 / 6 / 6 / 6 |
| 빈 시간 1개, 후보 0/1/5/20개 | 7 / 10 / 14 / 29 |
| 후보 20개, 빈 시간 1/2/3개 | 29 / 52 / 75 |

장소 수로는 늘지 않고, **후보 수 × 빈 시간 수**로 는다(H2 기준 후보당 1회, 운영은 최대 2회).

## 개선안 비교

| 안 | 효과(예열 JWT) | 위험·보존할 것 | 작업량 |
|---|---|---|---|
| A. 개인화 묶음 조회 | 빈 시간마다 후보 수 × 1~2 → **1**. 20회 중 가장 큰 몫 | 후보별 top-5와 평균을 똑같이 내야 함(거리 순서·동점·임베딩 없음·신호 없음·조회 실패 폴백). pgvector `LATERAL` 네이티브 SQL이라 H2에서 직접 실행 검증 불가 | 중: 반나절 |
| B. 일차·장소 한 번 + `resolveUserId` | 데이터 읽기 6 → **1**(예열 뒤 사용자 확인 0회, ADR 0036) | 남의 여행·없는 여행·없는 일차 404, 탈퇴 401, 빈 일차 `[]`, 방문 순서. 엔티티 매핑은 그대로 두고 scalar DTO로 | 소: P5와 같은 방식 |
| C. 빈 시간 사이 묶기 | Google을 모두 부른 뒤 카탈로그·임베딩 확인·개인화를 요청 전체 1회로: 빈 시간 G개에 4G → G+3 정도 | Gemini 임베딩 묶음 구성과 호출 기록 순서가 달라짐, 실패 범위가 넓어짐. 일반적인 빈 시간 수(1~2개)에서는 추가 이득이 1~3회 | 중~대 |

**A+B를 골랐다.** 둘 다 응답·외부 호출에 영향이 없고 운영 SQL의 대부분(후보 N+1과 EAGER 재조회)을 없앤다. C는 남는 이득이 빈 시간 수에만 비례하고 외부 호출 묶음 구성을 바꾸므로 별도 후보로 남긴다.

## 구현 (P7)

- `TripPlaceRepository.findGapPlaceRows`: `Itinerary left join TripPlace`를 `trip.id`·`trip.user.id`·`day` 조건으로 한 번에 읽어 `GapPlaceRow`(일차 id, 장소 id, 시작·끝 시간, 좌표, googlePlaceId)만 돌려준다. 결과 없음 = 남의/없는 여행·없는 일차(404), 장소 없는 일차는 placeId가 null인 한 줄(`[]`). 정렬은 이전과 같은 `visitOrder`에 동순위만 `id`로 고정했다.
- 같은 여행·일차가 두 줄이면 이전 `findByTripAndDay`(Optional)처럼 하나를 고르지 않고 `IncorrectResultSizeDataAccessException`으로 실패한다(이전과 같은 500).
- `UserPreferenceSignalRepository.findCandidateSimilarities`: 후보 id 목록에 대해 `CROSS JOIN LATERAL (… ORDER BY 거리 LIMIT 5)`로 후보별 top-5 유사도를 후보 id·거리 순으로 돌려준다. 후보 임베딩은 이전과 같이 text로 바꿨다 vector로 되돌려 비교한다(`findEmbeddingText` → `CAST(… AS vector)`와 같은 값).
- `PersonalizationService.personalizationScores(userId, 후보들)`: 줄을 후보별로 모아 이전과 같은 `DoubleStream.average()`(같은 순서)로 평균한다. 맵에 없는 후보는 0점, 조회 실패는 모두 0점(이전은 후보별로 실패해도 0점 — pgvector가 없는 환경처럼 실패가 전체적인 경우 결과는 같다).
- `GapRecommendationService.findGaps(Long userId, …)`를 추가하고 `findGaps(User, …)`는 위임한다(대화 도구 `ConversationToolExecutor`는 그대로). `TripRepository`·`ItineraryRepository` 의존은 뺐다.
- 컨트롤러: `resolveUserId` → `DailyQuotaService.consumePlaceCall(Long)`(같은 카운터, 한도 차감 순서 그대로) → `findGaps(userId, …)`.
- 엔티티 매핑(EAGER/LAZY)은 바꾸지 않았다. 외부 API 호출(Google·Gemini) 횟수와 순서도 그대로다.

## 테스트

- `GapRecommendationQueryCountTest`(새, 테스트 트랜잭션 없이 실제 HTTP, SQL은 운영 P4와 같은 `trova.db.queries.per.request` 계측기로 셈):
  - 응답 동일성: 변경 전 `GapRecommendationService` 경로를 테스트에 옮긴 기준 JSON과 비교 — 장소 0개, 장소 1개(시간 없음), 장소 1개+하루 끝 빈 시간(식당 검색·자기 자신·숙소·역 제외·동점 후보), 장소 7개(60/20/120/60분·끝 시간 없음·좌표 없음·하루 끝 50분), 빈 시간 있고 후보 0개, 빈 시간 없음(20:45 끝), Google 실패.
  - 권한: 남의 여행·없는 여행·없는 일차 404, 인증 없음 401, 예열 뒤 탈퇴 401(기존 여행·없는 여행 모두).
  - SQL 고정: 빈 시간 없음 장소 0/1/5/20개 → 1회, 빈 시간 1개 후보 0/1/5/20개 → 2/5/5/5회, 빈 시간 1/2/3개 → 5/9/13회.
  - Gemini 클라이언트는 목으로 바꿔 끼우고 한 번도 불리지 않았음을 매 테스트 뒤 확인한다. Google은 목.
- 단위: `GapRecommendationServiceTest`(새 조회에 맞춰 다시 씀 + 개인화 점수 곱·정렬, 맵에 없는 후보 0점, 빈 일차, 일차 중복 실패, User 위임), `PersonalizationServiceTest`(묶음 점수가 후보별 `personalizationScore`와 **비트까지 같음**, 실패 폴백, 빈 후보는 DB 안 감).
- 구현 전: 변경 전 코드로 새 통합 테스트를 돌려 SQL 테스트 3건 실패·동일성/권한/탈퇴 3건 통과를 확인했다.
- 변형 검사(모두 원복 후 통과): 소유자 조건 무력화 → 1건 실패(권한), left join → inner join → 3건 실패, 방문 순서 역순 → 3건 실패, 개인화 기본값 0→1 → 단위 1건 실패.

### 테스트 컨텍스트 주의

처음에는 `@MockitoBean`으로 새 스프링 컨텍스트를 만들었는데, 전체 실행에서 `ApiSessionCreationIntegrationTest` 2건이 `Table "USERS" not found`로 실패했다. 기본 `testdb`를 쓰는 컨텍스트가 30개 가까이 있고, 컨텍스트 캐시(32개)에서 밀려 닫히는 컨텍스트가 create-drop으로 공유 testdb 표를 지운다(이번 실행에서는 `ProcessingJobRepositoryTest` 시작 때 HikariPool-3(testdb) 컨텍스트가 밀려 닫힘). 그래서 새 테스트는 P2/P3/P6 성능 테스트의 격리 컨텍스트(`p2-query-count`)를 그대로 공유하고, 외부 클라이언트는 테스트 동안만 서비스 필드에 목을 끼웠다 되돌린다. 컨텍스트는 늘지 않았지만 공유 컨텍스트의 접근 순서가 바뀌어 같은 2건이 여전히 난다(아래).

## 전체 테스트 (로컬, `./gradlew test --offline`, 단독 실행)

| 대상 | 결과 |
|---|---|
| 이 브랜치(최종) | 1,272개 중 실패 2·건너뜀 6. 실패는 `ApiSessionCreationIntegrationTest` 2건(`Table "USERS" not found`), 단독 실행은 통과 |
| origin/main `8dc5db5` 그대로 | 이번 1회 실행에서는 전체 통과 |
| 변경 전 코드 + 새 통합 테스트 | 새 SQL 테스트 3건만 실패(기대), ApiSession은 통과 |

ApiSession 2건은 P4·P5·P6 기록(origin/main `1d50d9c`·`36ae8d8`에서도 실패)과 같은 공유 testdb 수명 간섭이고, 이 변경의 코드 경로와 관계가 없다(단독 통과, 표가 통째로 없음). 다만 이번 브랜치에서는 실행마다 재현됐고 같은 날 origin/main에서는 통과했으므로 "기존 실패"로 넘기지 말고 테스트 구성 문제로 따로 다뤄야 한다(아래 남은 것).

## 로컬 전후 (H2, MockMvc, 10회 예열 뒤 30회 중앙값, 전후 각 1회 실행)

| 빈 시간 1개 | 후보 0 | 후보 1 | 후보 5 | 후보 20 |
|---|---:|---:|---:|---:|
| SQL 전 → 후 | 7 → 2 | 10 → 5 | 14 → 5 | 29 → 5 |
| 중앙값 ms 전 → 후 | 2.448 → 2.471 | 6.802 → 5.927 | 11.694 → 5.588 | 28.468 → 7.694 |

H2는 네트워크가 없고 개인화 SQL이 즉시 실패하므로 운영 시간을 대신하지 않는다. 후보 20개에서 줄어든 시간에는 실패한 SQL 20번의 예외·경고 로그 비용이 크게 섞여 있을 것으로 보지만 나눠 재지는 않았다.

## 운영 예측 (예측, 측정 아님)

- 운영(Postgres)은 임베딩 있는 후보마다 2회였으므로, 빈 시간 G개·걸러진 후보 C개일 때 **6 + G(3 + 2C) → 1 + 4G**(카탈로그에 처음 보는 후보가 있으면 INSERT·재조회가 양쪽에 같이 더해짐).
- 평균 20.15회가 빈 시간 1개(약 5회)로 줄면 약 **15회 왕복 감소 × 30~54ms ≈ 450~810ms** 단축을 예측한다. P5·P6 실측(SQL 1회당 약 54ms 감소)에 가까우면 위쪽 값이다.
- 개인화 묶음 SQL 하나는 후보 수만큼 LATERAL 검색을 하므로 이전 한 번의 유사 신호 조회보다 무겁다. DB 안 실행 시간(사용자 신호 수에 비례)은 재지 않았다. 확인은 배포 뒤 같은 URI의 SQL/요청(1+4G 근처)과 평균·p95로 한다.

## 찾았지만 고치지 않은 것

- Google 후보에 `types`가 없으면 카탈로그 `category`가 null이 되고, `NOT_A_STOP`(`Set.of`)의 `contains(null)`이 NPE를 던져 이 API가 **500**이 된다(로컬 재현). 필터 조건을 바꾸지 않는다는 조건이라 그대로 두었다 — 별도 이슈 후보.
- 빈 시간 사이 묶기(C안), 대안 찾기(`AlternativeFinderService`)의 같은 후보별 `retrieveAndScore` N+1도 같은 묶음 조회로 줄일 수 있다(다만 그쪽은 이유 생성에 신호 목록까지 필요).

## 남은 것

- 운영 배포 뒤 표본으로 SQL/요청과 시간 확인.
- `findCandidateSimilarities`는 H2에서 실행할 수 없어 SQL 자체는 운영 Postgres 문법으로만 작성·검토했다. 배포 전에 개발 DB(Postgres+pgvector)에서 결과 동일성(후보별 이전 두 쿼리와 비교)을 한 번 확인하는 것을 권한다.
- 공유 testdb create-drop + 컨텍스트 캐시 32개 문제(ApiSession 2건) 정리: 기본 컨텍스트 수를 줄이거나 testdb를 `create`로 바꾸거나 캐시 크기를 늘리는 안을 별도 작업으로.
