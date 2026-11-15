# 03 여행 상세·삭제 영향·재구성의 일차별 장소 조회를 한 번으로

먼저 `docs/codex-tasks/00-common-rules.md`를 읽고 따른다.

## 목표

여행의 일차마다 장소를 따로 조회하는 세 곳을, 여행 하나의 장소를 한 번에 조회하도록 바꾼다. 응답 내용과 순서는 그대로여야 한다.

## 지금 코드 (2026-11-16 기준, 시작 전에 줄이 맞는지 확인하고 다르면 보고)

| 곳 | 줄 | 지금 |
|---|---|---|
| 여행 상세 `GET /api/trips/{id}`(날짜·제목 수정 응답도 같은 메서드) | `src/main/java/com/trova/backend/controller/TripController.java:272-280` `toDetail` | 일차 목록을 돌며 `findByItineraryOrderByVisitOrder` 호출 |
| 관리자 여행 삭제 영향 | `src/main/java/com/trova/backend/admin/action/DeleteTripAction.java:52-56` `impact` | 일차마다 장소 목록을 읽어 `.size()` |
| 일정 재구성 시작 | `src/main/java/com/trova/backend/replan/TripReplanGraph.java:129-133` `run` | 일차마다 장소 목록을 읽어 이어 붙임 |

- 저장소: `src/main/java/com/trova/backend/repository/TripPlaceRepository.java` — `findByItineraryOrderByVisitOrder`(16줄), `findByItinerary_Trip`(19줄)이 이미 있다.
- `TripPlace.itinerary`, `Itinerary.trip`, `Trip.user`는 fetch를 지정하지 않은 `ManyToOne`(기본 EAGER)이다. **엔티티의 fetch 설정은 바꾸지 않는다.**
- 세 곳 모두 트랜잭션 밖에서 돌고 `open-in-view`가 꺼져 있다(`src/main/resources/application.yml:30`). 그래서 `TripPlace`를 엔티티로 한 번에 읽기만 하면 EAGER 연관을 채우려고 장소가 있는 일차마다 SELECT가 더 나간다 — `TripPlaceRepository.java:21-22` 주석에 같은 일이 실측으로 적혀 있다. `findByItinerary_Trip`을 그대로 쓰는 것으로는 부족하다. join fetch, `@EntityGraph`, 프로젝션 중 하나로 연관 추가 조회까지 없앤다.
- 조회 수 테스트는 **`src/test/java/com/trova/backend/controller/ListQueryCountTest.java` 방식**으로 쓴다: 테스트 트랜잭션을 쓰지 않고(`:45` 주석), 요청을 한 번 미리 보낸 뒤 Hibernate 통계를 비우고 다시 보내 `getPrepareStatementCount()`를 비교한다. `TripListQueryCountTest.java`는 `@Transactional`이라 요청 전체가 한 세션이 되어 운영에서 나가는 추가 SELECT가 보이지 않는다 — 이 방식을 따라 쓰지 않는다.

## 할 일

1. `TripPlaceRepository`에 여행 하나의 장소를 일차·방문 순서대로 한 번에 가져오는 조회를 추가한다(이름과 방식은 기존 저장소 메서드 스타일에 맞춘다). 개수만 필요한 곳은 count 조회를 쓴다.
2. 위 세 곳을 그 조회로 바꾼다. 장소가 없는 일차도 응답에 빈 목록으로 남아야 한다. 일차 순서(`day`)와 일차 안 순서(`visitOrder`)가 지금과 같아야 한다.
3. 테스트 추가(`src/test/java/com/trova/backend/controller/`에 새 파일, 이름은 한글 문장):
   - 여행 상세: 일차 1개일 때와 5개일 때 SQL 문장 수가 같다. **일차마다 장소를 1개 이상 넣는다**(장소가 없으면 추가 SELECT가 나가지 않아 항상 통과한다).
   - 여행 상세: 장소 없는 일차가 빈 목록으로 나오고, 일차·방문 순서가 유지된다.
   - 새 저장소 조회 자체(H2, mock 아님): 2일차 장소를 먼저, `visitOrder`를 거꾸로 저장한 뒤 조회 결과가 일차 → 방문 순서로 나오는지, 다른 여행의 장소가 섞이지 않는지, SQL 문장 수가 일차 수와 상관없이 같은지. 재구성은 1일차 마지막과 2일차 첫 장소를 이웃으로 보지 않으므로(`TripReplanGraph.java:138-139` 주석) 순서가 틀리면 판단이 달라진다.
   - 삭제 영향: 기존 `src/test/java/com/trova/backend/admin/action/TripAndDraftActionsTest.java`는 장소가 0개인 여행만 본다(`:52`). 일차 3개에 장소 2·0·3개, 다른 여행에도 장소를 둔 뒤 "여행 1·일차 3·장소 5"가 나오는 테스트를 **반드시** 더한다.
   - 재구성: 기존 `src/test/java/com/trova/backend/replan/TripReplanGraphTest.java`는 저장소를 mock한다. 바뀐 조회에 맞게 mock만 고치고, 검증하는 내용은 줄이지 않는다. 조회 순서와 조회 수는 위의 저장소 테스트가 맡는다.

## 건드릴 파일

- `src/main/java/com/trova/backend/repository/TripPlaceRepository.java`
- `src/main/java/com/trova/backend/controller/TripController.java` (`toDetail`만)
- `src/main/java/com/trova/backend/admin/action/DeleteTripAction.java` (`impact`만)
- `src/main/java/com/trova/backend/replan/TripReplanGraph.java` (`run`의 장소 모으는 부분만)
- `src/test/java/com/trova/backend/` 아래 새 테스트 파일, 그리고 위 변경 때문에 깨지는 기존 테스트의 mock 설정

## 건드리지 말 파일

- `src/main/java/com/trova/backend/entity/` 전부(fetch 설정·컬럼 변경 금지)
- `src/main/java/com/trova/backend/service/TripService.java`(`deleteTrip`의 일차별 조회는 이번 범위가 아니다 — 보고의 "따로 볼 것"에만 적는다)
- `TripController.java`의 `toDetail` 밖(열린 PR #131이 이 파일을 고치고 있어 충돌을 줄여야 한다)
- `build.gradle`, `src/main/resources/`, `docs/`, `pipeline-test/`

## 완료 기준

- 세 곳 모두 일차 수와 상관없이 장소 조회가 한 번이고, 연관을 채우는 추가 SELECT가 일차 수에 따라 늘지 않는다.
- 여행 상세 응답 JSON이 바꾸기 전과 같다(기존 `TripControllerTest` 통과).
- 새 조회 수 테스트가 바꾸기 전 코드에서는 실패하고 바꾼 뒤에는 통과한다는 점을 보고에 적는다(직접 확인했으면 "확인", 아니면 "추정").
- `./gradlew build` 통과.

## 검증 명령

```bash
./gradlew build
```
