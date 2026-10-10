# 추천 — home-trips (2026-10-10)

benchflow 0.3.0 `check --tag qa` → advise. 환경: 로컬 서버(Mac, 커밋 5a9c9b5) + 개발 DB(Supabase 서울), 테스트 계정 user 1, 30회·워밍업 3회.

| 지표 | 기준(baseline) | 지금(check) | 판정 |
|---|---|---|---|
| home-trips `GET /api/trips` | 239.2ms | 237.2ms | 변화 없음 |
| home-auto-drafts `GET /api/trip-drafts/auto` | 41.0ms | 40.4ms | 변화 없음 |

추천 이유: 판정은 변화 없음이지만 같은 환경에서 내 여행 목록이 자동 일정 카드보다 약 5.9배 느림.

## 결론(코드로 센 추정, SQL 로그 미확인)
`findByItineraryTripIn`(TripPlaceRepository.java:14)이 돌려준 TripPlace마다 기본 EAGER `@ManyToOne`
(TripPlace.java:16 → Itinerary.java:15 → Trip.java:16)로 일차(Itinerary)마다 후속 SELECT가 나가는 N+1.
`open-in-view: false`(application.yml:30)이고 listTrips(TripController.java:217-228)에 트랜잭션이 없어 리포지토리 호출마다 영속성 컨텍스트가 새로 열림.
쿼리 수 추정: 목록 약 4+K개(K = 장소가 있는 일차 수) vs 자동 일정 카드 2~7개(고정).

## 추천
1. **(region 프로젝션)** 응답(TripResponse.from, TripController.java:113-126)에 필요한 건 여행별 장소 수와 지역뿐 → `select tp.itinerary.trip.id, tp.region ... order by tp.itinerary.day, tp.visitOrder` 프로젝션으로 엔티티 로딩 제거. 예상 쿼리 3~4개, 중앙값 60~110ms대(**추정**). 검증: 이름표 `projection`으로 재측정 + 로컬 SQL 로그로 쿼리 수 기록, `TripControllerTest.여행_목록에_장소_수와_많이_나온_지역을_준다`.
2. **(대안: @EntityGraph)** `findByItineraryTripIn`에 `@EntityGraph(attributePaths = {"itinerary", "itinerary.trip", "itinerary.trip.user"})` — 레포 선례 SavedPlaceRepository.java:15-17(#29, SQL 22개 감소 실측). 변경이 가장 작음. 예상 비슷(**추정**), 단 memo 등 전체 컬럼은 계속 읽음. 이름표 `entity-graph`.
3. **(덧붙임: 읽기 전용 트랜잭션 하나)** 목록 조립을 `TripService`의 `@Transactional(readOnly = true)`로 → User·Trip 1차 캐시 재사용, 커넥션·COMMIT 왕복 감소. 단독 효과 약 20~50ms(**추정**). 이름표 `single-tx`.

위험·비용: 유료 서비스 없음(ADR 0002), 좌표 없는 장소도 그대로 셈(ADR 0006), 코드 스타일 ADR 0009(와일드카드 import·var 없음).

## 같은 패턴 / 근거 부족
- `GET /api/trips/{id}`(TripController.java:230-249): 일차마다 쿼리 루프 — 상세 화면 지표가 생기면 다음 후보.
- EAGER 기본 `@ManyToOne` 엔티티 12개 중 @EntityGraph로 막은 곳은 SavedPlaceRepository 하나.
- 185.5ms(b7997ad) → 239.2ms: 측정 경로 코드 변화 없음(git diff 확인). 데이터 증가 vs 네트워크 차이는 근거 부족.
- 값이 약 185ms·약 270ms 두 갈래로 나뉨 — 커넥션 풀/PgBouncer 의심, Hikari 지표와 함께 재야 함.

## 검증 메모
인용한 위치(TripController.java:113-126·217-228, TripPlaceRepository.java:14, TripPlace/Itinerary/Trip `@ManyToOne(optional = false)`, SavedPlaceRepository.java:15-17, application.yml:26·30, CurrentUserService.java:40)를 직접 열어 확인함.

## 적용 → 전/후 (#153, 커밋 35e635b)
사용자가 추천 1(프로젝션)과 3(읽기 전용 트랜잭션 하나)을 골라 적용.

| 항목 | 전(5a9c9b5) | 후(35e635b) | 어디서 |
|---|---|---|---|
| 요청 1번 SQL 수 | 13개(일차 9·사용자 2·여행 1·여행 장소 1) | 4개(사용자 2·여행 1·지역 프로젝션 1) | 로컬 서버 + 개발 DB, user 1(여행 4개·장소 49곳), `--logging.level.org.hibernate.SQL=debug`로 1회 |
| 테스트 쿼리 수(H2) | 일차 1개=4, 일차 10개=13 | 일차 1개=3, 일차 10개=3 | `TripListQueryCountTest` |
| home-trips 중앙값 / p90 | 239.2 / 291.0ms | 71.7 / 74.8ms (−70.0%) | benchflow check, 30회·워밍업 3회, SQL 로그 끔 |
| 응답 본문 | — | 전과 같음(여행 4개 전부) | 같은 토큰으로 전·후 응답 비교 |

예상(추정) 60~110ms대 → 실측 71.7ms. 같은 때 코드 변경이 없는 home-auto-drafts도 41.0→36.3ms(−11.5%)로 "좋아짐" 판정이 나왔다 —
환경 변동이 10%를 넘을 수 있다는 뜻이라, 허용 오차 10%(감으로 정함)는 이 환경에서 좁다. 다음엔 15% 안팎으로 다시 정할 것.

## 운영 전후 (2026-10-10, #154 배포 후)
운영 서버(Oracle E2.1.Micro) + 운영 DB, user 2(여행 6·장소 있는 일차 5·장소 24곳), 서버 안에서 `127.0.0.1:8080`을 호출(인터넷 구간 제외), 30회·워밍업 3회.
"전"은 운영을 백업 JAR(b7997ad, 해시 3fb7d569)로 잠시 되돌려 쟀고, 다시 614d82d(해시 9aefc07a)로 복구했다(재시작 2번, 각각 UP 91·93초, 진행 중 작업 0건 확인 후).

| 측정 | 코드 | 중앙값 | p90 |
|---|---|---|---|
| 후 1회차 | 614d82d | 252.5ms | 256.9ms |
| 전 | b7997ad | 398.1ms | 413.1ms |
| 후 2회차(복구 직후) | 614d82d | 233.9ms | 237.1ms |

- 운영 기준 −36.6~−41.2%(398.1 → 233.9~252.5ms). 로컬(−70.0%)보다 줄어든 폭이 작은 것은 user 2의 장소 있는 일차가 5개라 줄어든 쿼리가 13→4가 아니라 약 9→4(코드로 센 추정)이기 때문.
- 남은 4쿼리에 약 240ms → 운영 서버에서 운영 DB까지 쿼리 1번 왕복이 약 60ms로 추정(로컬 Mac → 개발 DB는 약 15ms). 운영은 쿼리 수가 응답 시간을 거의 결정한다.
- 운영에서 SQL 로그는 켜지 않았다(재시작이 더 필요해서) — 운영 쿼리 수는 추정.
- 주의: benchflow 기록의 `commit` 칸은 측정한 Mac 작업 폴더의 커밋(614d82d)이라 "전" 줄도 614d82d로 찍혔다. 실제 운영 코드는 이름표(before-b7997ad)와 위 표가 맞다.
