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

### pgvector 실제 동일성 (로컬 Mac, Docker 컨테이너, Codex 교차 검증 뒤 추가)

H2에는 embedding 칸이 없어 위 테스트에서는 이전·새 경로가 모두 0점으로 폴백한다. 그래서 새 SQL이 틀려도 통과할 수 있었다. `PersonalizationPgvectorEquivalenceTest`는 Testcontainers로 `pgvector/pgvector:0.8.1-pg17`(운영 Supabase와 같은 PostgreSQL 17) 컨테이너를 띄운다. 그 위에 Hibernate가 표를 만들고, 운영처럼 `vector(768)` 칸을 붙인 뒤 두 경로를 같은 데이터로 비교한다. Docker가 없는 환경(CI 등)에서는 `@Testcontainers(disabledWithoutDocker = true)`로 건너뛴다. 운영 DB·개발 DB·`.env`는 쓰지 않았고, 컨테이너는 Testcontainers가 실행이 끝나면 지운다.

- 데이터:
  - 사용자 5명:
    - A: 신호 11개. 무작위 7곳, 같은 벡터 두 곳(거리 동점), 같은 장소에 신호 두 번(중복), embedding 없는 곳, 0 벡터.
    - B: 신호 6개. 후보 -W에서 5위와 6위가 동점이라 top-5 경계가 동점이다.
    - C: 0 벡터가 top-5 안에 들어간다.
    - D: 신호 없음.
    - E: embedding 없는 신호만.
  - 후보 14개: 무작위·신호 근처 10개, 신호와 같은 벡터, -W, 0 벡터, embedding 없음. 빈 후보 목록도 따로 넣었다.
- 줄 단위: 후보마다 이전 `findEmbeddingText`→`findTopSimilarSignals`의 top-5 유사도 목록과 새 `findCandidateSimilarities`를 후보별로 모은 목록이 **순서까지 비트 단위로 같다**. 대상은 70쌍(5명 × 14개)이다.
- 점수: `personalizationScore`(이전)와 `personalizationScores`(새)가 70쌍 모두 비트 단위로 같다.
  - 0이 아닌 점수 24쌍, NaN 15쌍, 나머지 31쌍은 0이다.
  - NaN은 0 벡터의 코사인 거리에서 나온다. 이전 경로도 같은 NaN이다.
- 최종 추천 순서: 사용자 5명마다 `findGaps`(새 경로, Google 목)의 추천 순서가, 이전 정렬(후보마다 `personalizationScore` × 가중치)과 같다.
  - 기본 점수를 모두 같게 해서 순서는 개인화로만 갈린다.
  - A·B는 Google 순서와 다르게 재정렬됐으므로, 0점끼리만 맞춘 비교가 아니다.
- 변형 검사(원복 후 통과): 묶음 SQL `LIMIT 5→6`은 2건 모두 실패했다. 바깥 정렬을 거리 역순으로 바꾸면 줄 단위 1건이 실패했다.

### 테스트 컨텍스트 주의

처음에는 `@MockitoBean`으로 새 스프링 컨텍스트를 만들었는데, 전체 실행에서 `ApiSessionCreationIntegrationTest` 2건이 `Table "USERS" not found`로 실패했다. 기본 `testdb`를 쓰는 컨텍스트가 30개 가까이 있고, 컨텍스트 캐시(32개)에서 밀려 닫히는 컨텍스트가 create-drop으로 공유 testdb 표를 지운다(이번 실행에서는 `ProcessingJobRepositoryTest` 시작 때 HikariPool-3(testdb) 컨텍스트가 밀려 닫힘). 그래서 새 테스트는 P2/P3/P6 성능 테스트의 격리 컨텍스트(`p2-query-count`)를 그대로 공유하고, 외부 클라이언트는 테스트 동안만 서비스 필드에 목을 끼웠다 되돌린다. 컨텍스트는 늘지 않았지만 공유 컨텍스트의 접근 순서가 바뀌어 같은 2건이 여전히 났다(아래).

**정리(Codex 교차 검증 뒤):**
- 테스트 설정(`src/test/resources/application.yml`)의 기본 H2 URL을 `jdbc:h2:mem:testdb-${random.uuid}`로 바꿨다.
  - 컨텍스트마다 다른 메모리 DB를 쓰므로, 밀려 닫히는 컨텍스트의 create-drop이 다른 컨텍스트의 표를 지우지 못한다.
  - URL은 DataSource를 만들 때 한 번만 읽힌다.
- 대신 캐시된 컨텍스트가 각자 데이터를 들고 있어 테스트 JVM 힙이 늘었다.
  - Gradle 기본 512MiB에서는 뒤쪽 내장 Kafka 테스트가 `OutOfMemoryError`로 시작하지 못했다. 실행 2회에서 각각 3건, 1건이다.
  - 그래서 `build.gradle`의 `test` 작업에 `maxHeapSize = '1g'`를 명시했다.
- pgvector 테스트 컨텍스트는 컨테이너와 함께 쓸모가 없어지므로 `@DirtiesContext`로 바로 닫는다.

## 전체 테스트 (로컬, `./gradlew test --offline`, 단독 실행)

| 대상 | 결과 |
|---|---|
| 이 브랜치(첫 PR) | 1,272개 중 실패 2·건너뜀 6. 실패는 `ApiSessionCreationIntegrationTest` 2건(`Table "USERS" not found`), 단독 실행은 통과 |
| 컨텍스트별 DB + pgvector 테스트, 힙 기본값 | 1,272개 완료·실패 3(내장 Kafka 3건 `OutOfMemoryError`) → `@DirtiesContext` 뒤 1,273개 완료·실패 1(내장 Kafka 1건 같은 원인) |
| **이 브랜치(최종, 힙 1GiB)** | **1,274개 중 실패 0·건너뜀 6**. ApiSession 3건 통과, pgvector 2건 실제 실행(건너뜀 아님) |
| origin/main `8dc5db5` 그대로 | 이번 1회 실행에서는 전체 통과(1,272개·건너뜀 6) |
| 변경 전 코드 + 새 통합 테스트 | 새 SQL 테스트 3건만 실패(기대), ApiSession은 통과 |

ApiSession 2건은 P4·P5·P6 기록(origin/main `1d50d9c`·`36ae8d8`에서도 실패)과 같은 공유 testdb 수명 간섭이었다. 위 정리로 테스트 구성에서 원인을 없앴다. 건너뜀 6건은 main과 같다(Docker가 없으면 pgvector 2건이 더해져 8건).

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

## 운영 배포

devflow `deploy.sh`(0.5.3)로 운영 서버(Oracle E2.1.Micro, systemd `trova-backend`)에 반영했다. 시각은 KST.

| 항목 | 값 |
|---|---|
| 시각 | 2026-11-19 18:32:12 시작 → 18:35:07 끝(전체 175초) |
| 커밋 | `fa02baf` perf: 빈 시간 추천 SQL을 후보 수와 무관하게 줄임 (#482) (#483) |
| 사전 확인 | 운영 진행 중 작업(영상 분석·일정 초안) 0건 |
| 전송 | JAR 104M, 해시 일치 `30514438f3b8e231` |
| UP 걸린 시간 | 재시작 후 134초 |
| 스크립트 확인 | 인증 없는 공유 요청 401, 세션 쿠키 0개, 자동 초안 목록 인증 없이 401 — 모두 통과 |
| 추가 확인(curl) | `/actuator/health` 200, `/api/trips/1` 인증 없이 401, `/api/trips/1/days/1/gap-recommendations` 인증 없이 401 |
| 백업 경로 | `/opt/trova-backend/app.jar.bak-20261119-183212` |

## 운영 전후 비교 (운영, 2026-11-19)

### 측정 방법

- 표본: Android 에뮬레이터(구글 계정)와 iOS 시뮬레이터(카카오 계정)를 동시에 약 20분, 사람 속도로 조회만 했다. 부하 시험이 아니다.
- 값: Grafana(Prometheus) API로 조회한 `increase[21m]`. 요청당 SQL은 P4 계측기(`trova.db.queries.per.request`) 값이다.
- 전: 15:28:10~15:48:38 KST, main `69b55fa`, 재시작 31분 뒤.
- 후: 19:06:10~19:26:37 KST, main `fa02baf`(이 작업), 재시작 31분 뒤.

### 빈 시간 추천 `GET …/gap-recommendations` (200만)

| 지표 | 전 | 후 | 변화 |
|---|---:|---:|---:|
| 평균 ms | 874 | 393 | -55% |
| p50 ms | 342 | 492 | 늘어남 |
| p95 ms | 2,104 | 873 | -59% |
| 건수 | 35 | 25 | |
| 요청당 SQL | 20.15 | 3.48 | -83% |

- 평균·p95와 SQL 수는 크게 줄었다. 5xx는 0건이다.
- 요청당 SQL 3.48은 예측한 1(빈 시간 없음)~5(빈 시간 1개) 사이에 있다. 두 종류 응답이 섞인 값으로 보이며, 종류별로 나눠 세지는 않았다.

### 예측과 실측

| | 예측(배포 전) | 실측 |
|---|---|---|
| 평균 단축 | 약 15회 왕복 감소 × 30~54ms ≈ 450~810ms | 874→393ms, **481ms** |
| 요청당 SQL | 빈 시간 1개 기준 약 5회(1+4G) | 3.48회 |

실측 단축 481ms는 예측 범위의 아래쪽에 들어간다. 다만 아래 한계 때문에 전후 표본의 응답 종류 구성이 같다고 볼 수 없어서, 회당 절감 시간을 이 값으로 따로 계산하지는 않는다.

### 한계: p50이 늘어난 이유

이 API는 응답이 두 종류로 섞인다.
- **빈 시간 없음**: Google 검색 없이 바로 끝나는 빠른 응답.
- **추천 목록 있음**: Google 근처 검색(외부)이 들어가는 느린 응답.

후 표본은 추천 목록이 실제로 열린 비율이 높았던 것으로 보인다(Android 14회 중 7회 목록, iOS 23회 목록 — 작업자 조작 기록 기준). 느린 응답 비율이 높으면 SQL이 줄어도 중앙값은 올라갈 수 있다. 전 표본의 응답 종류 비율은 기록하지 않아 직접 비교하지 못했다. 그래서 이번 결과는 평균·p95·SQL 수로 판단하고, p50 증가는 표본 구성 차이로 보되 확정하지 않는다. **응답 종류별(빈 시간 없음/목록 있음) 분리 측정이 다음 과제**다.

### 다른 API 회귀 점검 (같은 두 표본)

| API | 평균 ms 전 → 후 | 건수 전 → 후 | 요청당 SQL |
|---|---|---|---|
| 여행 상세 | 105 → 104 | 42 → 27 | 2 → 2 |
| 찜 | 86 → 78 | 43 → 29 | 1 → 1 |
| 폴더 | 111 → 106 | 40 → 28 | 2 → 2 |
| 알림 | 74 → 66 | | 불변 |
| 공유 링크 | 230 → 245 | 19 → 24 | 3 → 3 |
| 대기 장소 | 165 → 176 | 232 → 116 | 4 → 4 |
| 여행 목록 | 132 → 157 | 9 → 5 | 3 → 3 |
| 초안 상세 | 199 → 218 | 19 → 3 | 3 → 3 |
| 장소 | 138 → 147 | | 불변 |

빈 칸은 전달받은 값에 건수가 없어 비워 두었다.

- 모든 API의 요청당 SQL 횟수가 그대로이고 5xx는 0건이다.
- 10~25ms 차이는 건수가 적은 표본(3~43건)의 흔들림 수준으로 본다. 이 변경이 건드린 코드 경로도 아니다.
- **대안 장소** API는 같은 개인화 코드(`PersonalizationService`)를 쓰지만 두 표본 모두 호출 0건이라 측정하지 못했다. 다음 표본 과제다.

### pgvector 동일성 (다시 정리)

운영 배포 전 로컬 Testcontainers `pgvector/pgvector:0.8.1-pg17`에서 사용자 5명 × 후보 14개 = 70쌍의 점수와 최종 추천 순서가 이전 경로와 같음을 확인했다(PR #483, 위 "pgvector 실제 동일성"). 0 벡터에서 나오는 NaN 점수의 정렬은 이전부터 있던 결함이라 이번에 바꾸지 않았고 별도 과제로 둔다.

### 측정 중 사고 기록

- 19:15:50 KST, Android 쪽 자동 조작에서 초안 화면의 뒤로 가기가 실패했다. 그 뒤 좌표 탭이 '여행 만들기'에 들어가 '경주 당일치기' 여행 1건이 의도치 않게 저장됐다.
- 바로 조작을 멈추고 보고했고, 사용자 확인 뒤 20:05:32 KST에 그 여행 1건을 삭제했다.
- 측정 대상은 GET 응답이라 이 저장이 위 수치에 영향을 주지 않았다.
- 교훈: 자동 조작 표본에서는 저장 버튼이 있는 화면을 피하고, 뒤로 가기는 화면이 바뀐 것을 확인한 뒤 다음 탭을 누른다.

## 남은 것

- 응답 종류별(빈 시간 없음/추천 목록 있음) 분리 측정 — p50 증가가 표본 구성 차이인지 확인.
- 대안 장소 API(같은 개인화 코드) 운영 표본.
- 0 벡터 NaN 점수 정렬과 types 누락은 아래 T21(#487)에서 수정했다. pgvector 실제 재실행은 맥에서 필요하다.
- pgvector 동일성은 로컬 컨테이너(합성 데이터 70쌍)로 확인했다. 운영 데이터 분포에서의 묶음 SQL DB 안 시간은 따로 재지 않았다(운영 전후 평균·p95는 위 표).
- Docker가 없는 CI에서는 pgvector 비교 2건이 건너뛰어진다. 이 SQL을 고치면 Docker가 있는 곳에서 그 테스트를 한 번 돌려야 한다.
- 기본 H2를 컨텍스트별로 나눈 뒤 테스트 힙을 1GiB로 올렸다. 테스트 컨텍스트 수를 줄이는 정리는 별도 작업 후보다.

## T21: NaN 개인화와 types 누락 수정 (#487)

### 정책과 근거

- 후보별 top-5 조회와 SQL 순서는 바꾸지 않고, 반환된 유사도 중 `Double.isFinite`인 값만 같은 순서로 평균한다. 0 벡터 cosine의 NaN은 선호를 알 수 없는 신호이므로 평균의 분모에서도 제외한다. 남는 신호가 없으면 기존 콜드스타트와 같은 0점이다. NaN을 0으로 치환한 뒤 평균하면 정상 신호의 세기가 희석되므로 채택하지 않았다.
- 단건 `retrieveAndScore`와 묶음 `personalizationScores`가 같은 정책을 쓴다. 단건에서 반환하는 추천 이유용 신호도 무효 유사도를 제외한다. 전부 무효이면 이유를 생성하지 않는다. 유한한 신호만 있던 후보의 점수는 비트 단위로 보존한다.
- 최종 정렬에서도 비유한 점수(NaN·무한대)를 유한 점수 뒤로 둔다. 유한 점수는 기존 내림차순·동점 입력 순서를 유지한다. 대안 장소 역시 같은 개인화 점수를 쓰므로 동일하게 처리한다. 대안의 기본 검색 우선·보충 검색 후순위는 유한 후보 사이에서 유지하고, 무효 기본 검색 후보는 유효 보충 후보보다 뒤로 보낸다.
- Google `types` 누락/빈 배열은 `PlaceCatalogService`가 이미 `category=null`로 저장한다. `AlternativeFinderService`의 기존 제외 목록 검사도 null 카테고리를 남긴다. 빈 시간 추천도 그 의도에 맞춰 null을 유지하고, 알려진 숙소·역 등만 제외한다. 분류를 추측해서 채우거나 근거 없이 후보를 버리지 않는다.
- 0 벡터 후보는 유효 신호가 전혀 없어 개인화 0점으로 기본 인기도 순서에 참여한다. 정렬의 마지막 배치는 계산 이후에도 비유한 최종 점수가 남는 경우의 방어 규칙이다.

결정 기록: [ADR 0051](../../adr/0051-personalization-finite-scores.md). 운영 DB·외부 Google/Gemini API는 사용하지 않는다.

### 테스트 먼저와 변형 검사 (Windows)

기준은 `origin/main` `f45da199`이다. 첫 오프라인 실행은 Testcontainers 2.0.5 캐시가 없어 컴파일 전에 막혔다. 코디네이터 승인으로 선언된 의존성만 온라인 `./gradlew test --console=plain` 한 번으로 받았고 이후에는 오프라인으로 실행했다.

| 실행 | 결과 |
|---|---|
| 구현 전 전체(프로덕션 코드는 기준 그대로 + 새 회귀 테스트) | 1,279건, 실패 36, 건너뜀 8: 새 회귀 5건 + 기존 파이프라인 환경 실패 31건 |
| 구현 후 대상 5개 클래스 | 60건, 실패 0, pgvector 2건 건너뜀(58건 통과) |
| 변형 원복 후 전체 `./gradlew test --offline --console=plain` | 1,279건, 실패 33, 건너뜀 8: 환경 31건 + 자동 초안 순서 2건 |
| 프로덕션 변경을 모두 제거한 기준 코드 재검증 | 자동 초안 클래스 단독 4건 통과; 전체 1,279건 중 실패 37, 건너뜀 8: 회귀 5건 + 환경 31건 + 자동 초안 순서 1건 |
| 최종 코드 복원 후 `./gradlew build --offline --console=plain` (전체 테스트 포함) | 1,279건 중 1,240건 통과, 기존 환경 실패 31건, 건너뜀 8; 기준 코드 대비 새 실패 0. 자동 초안 4건 통과, JAR 생성 성공, test 단계 때문에 전체 build는 실패 |
| 변형: 빈 시간 유한 우선 가드 역전 | 정렬 회귀 1건 실패 |
| 변형: 대안 유한 우선 가드 역전 | 같은 검색 내 정렬·보충 후보와 정렬 회귀 2건 실패 |
| 변형: 묶음 평균의 유한 필터 제거 | 개인화 평균 회귀 1건 실패 |
| 변형: category null 가드 제거 | types 누락 HTTP 회귀 1건 NPE 실패 |

변형 4개는 한 실행에 넣었으며 각기 다른 회귀 5건으로 검출했다(58건 중 5건 실패). 모두 원복했다. 구현 전 평균 테스트는 단건 NaN에서 실패했고, 변형 검사에서는 단건을 통과한 뒤 묶음 평균 NaN에서 실패해 두 경로를 각각 확인했다.

환경 실패 31건은 `AuxiliaryRunnerInterruptTest` 5건, `AuxiliaryRunnerLifecycleTest` 16건, `PipelineRunnerLaunchPolicyTest` 3건, `PipelineRunnerProcessCleanupTest` 5건, `PipelineRunnerTest` 2건이다. XML에서 `Python was not found`(exit=9009), `nice` 실행 파일 없음, 그로 인한 자식 PID 파일 미생성을 확인했다. 이 작업에서 Python·Docker CLI를 직접 실행하거나 환경·빌드 설정을 바꾸지 않았다.

자동 초안 순서 실패는 `AutoDraftReadPerformanceTest`의 첫 항목 ID 기대값이다. 기준 프로덕션 코드와의 차이가 없음을 `git diff --exit-code -- src/main`으로 확인한 뒤 재실행했고, `여행으로_숨긴_뒤_열개를_고르고_장소와_여행_삭제를_바로_반영한다`가 기준 코드에서도 실패했다. `다시_짠_초안과_없는_영상과_실패한_최신_분석도_기존_응답과_같다`는 기준 재실행에서는 통과했다. 간헐 실패의 원인은 확정하지 않았으며 이 작업의 추천 코드나 테스트 외의 파일은 수정하지 않았다.

최종 전체 실행에서는 자동 초안 4건 모두 통과했다. 최종 실패 목록을 기준 코드 전체 실행의 실패 목록과 대조해 추가 실패가 없음을 확인했다. 테스트·빌드가 전부 초록이라고 보고하지 않는다. 저장소에 `scripts/check_docs.py`, `check_context.py`, `check_log.py`와 `.githooks`는 없으며 `git diff --check`는 통과했다.

**맥에서 Docker로 실행 필요**: 이 Windows 실행에서는 `PersonalizationPgvectorEquivalenceTest` 2건이 건너뛰어졌다. 코디네이터가 별도로 실행하기로 했다. 0 벡터 기대값은 0점으로 갱신했지만 실제 pgvector 실행 성공을 주장하지 않는다.
