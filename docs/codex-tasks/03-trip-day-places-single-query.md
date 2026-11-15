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
- 조회 수 테스트의 기존 방식: `src/test/java/com/trova/backend/controller/TripListQueryCountTest.java`, `ListQueryCountTest.java`(요청을 한 번 미리 보내고, Hibernate 통계를 비운 뒤 다시 보내 `getPrepareStatementCount()`를 비교).

## 할 일

1. `TripPlaceRepository`에 여행 하나의 장소를 일차·방문 순서대로 한 번에 가져오는 조회를 추가한다(이름과 방식은 기존 저장소 메서드 스타일에 맞춘다). 개수만 필요한 곳은 count 조회를 쓴다.
2. 위 세 곳을 그 조회로 바꾼다. 장소가 없는 일차도 응답에 빈 목록으로 남아야 한다. 일차 순서(`day`)와 일차 안 순서(`visitOrder`)가 지금과 같아야 한다.
3. 테스트 추가(`src/test/java/com/trova/backend/controller/`에 새 파일, 이름은 한글 문장):
   - 여행 상세: 일차 1개일 때와 5개일 때 SQL 문장 수가 같다.
   - 여행 상세: 장소 없는 일차가 빈 목록으로 나오고, 일차·방문 순서가 유지된다.
   - 삭제 영향 문구("여행 1·일차 N·장소 M")가 바꾸기 전과 같다 — 기존 `src/test/java/com/trova/backend/admin/action/TripAndDraftActionsTest.java`가 통과하면 된다. 필요하면 일차 여러 개 경우를 더한다.
   - 재구성: 기존 `src/test/java/com/trova/backend/replan/TripReplanGraphTest.java`는 저장소를 mock한다. 바뀐 조회에 맞게 mock만 고치고, 검증하는 내용은 줄이지 않는다.

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

- 세 곳 모두 일차 수와 상관없이 장소 조회가 한 번이다.
- 여행 상세 응답 JSON이 바꾸기 전과 같다(기존 `TripControllerTest` 통과).
- 새 조회 수 테스트가 바꾸기 전 코드에서는 실패하고 바꾼 뒤에는 통과한다는 점을 보고에 적는다(직접 확인했으면 "확인", 아니면 "추정").
- `./gradlew build` 통과.

## 검증 명령

```bash
./gradlew build
```
