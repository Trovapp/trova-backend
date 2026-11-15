# 06 같은 영상 여행 찾기·관리자 사용자 상세의 항목별 조회 줄이기

먼저 `docs/codex-tasks/00-common-rules.md`를 읽고 따른다. 03번 작업이 머지된 뒤에 한다(그때 추가된 조회 수 테스트 방식을 따라 쓴다).

## 목표

목록을 돌며 항목마다 저장소를 부르는 두 곳을 한 번의 조회로 바꾼다. 결과는 그대로여야 한다.

## 지금 코드 (2026-11-16 기준, 시작 전에 줄이 맞는지 확인하고 다르면 보고)

| 곳 | 줄 | 지금 |
|---|---|---|
| 같은 영상으로 이미 만든 여행 찾기 — `GET /api/places/videos/{jobId}/trip`, `POST /api/places/videos/{jobId}/confirm-trip` | `src/main/java/com/trova/backend/service/TripService.java:654-660` `findExistingTripForVideo` | 사용자의 작업 전체를 읽고, 같은 영상(`VideoKey`)인 작업마다 `savedPlaceRepository.findByProcessingJob` 호출 |
| 관리자 사용자 상세 `GET /api/admin/users/{id}` | `src/main/java/com/trova/backend/admin/user/AdminUserService.java:101-107`, `src/main/java/com/trova/backend/admin/action/RetryJobAction.java:87-97` | 최근 작업(최대 10개)마다 `isRetryable` → 재시도할 수 있는 실패면 `existsByUserAndSourceUrlAndIdGreaterThan` 호출 |

- 조회 수 테스트의 기존 방식: `src/test/java/com/trova/backend/controller/ListQueryCountTest.java`, `TripListQueryCountTest.java`.
- 기존 기능 테스트: `src/test/java/com/trova/backend/controller/TripControllerTest.java`(같은 영상 재분석·중복 확정), `src/test/java/com/trova/backend/controller/AdminUsersControllerTest.java`(`retryable` 응답).

## 할 일

1. `findExistingTripForVideo`: 같은 영상인 작업들을 먼저 고른 뒤 그 작업들의 `SavedPlace`를 한 번에 조회한다(`SavedPlaceRepository`에 여러 작업을 받는 조회 추가). 같은 영상 작업이 없으면 조회하지 않는다. `VideoKey` 비교 방식과 그 뒤 `findExistingTripForSavedPlaces` 호출은 그대로 둔다.
2. 관리자 사용자 상세: 최근 작업 목록에 대해 "같은 사용자·같은 링크로 더 나중에 만든 작업이 있는가"를 항목마다 묻지 않고 알 수 있게 한다. 방법은 두 가지를 비교해 더 단순한 쪽을 고르고 보고에 이유를 적는다 — (가) 필요한 링크들에 대해 가장 큰 작업 ID를 한 번에 조회, (나) 이미 읽은 최근 작업 목록 안에서 먼저 판단하고 목록 밖일 수 있는 경우만 조회. **`RetryJobAction.isRetryable(job)` 하나만 부르는 다른 호출자(미리보기·실행)의 판단 결과는 바뀌면 안 된다.**
3. 테스트 추가:
   - 같은 영상을 다시 분석한 작업이 1개일 때와 5개일 때 `GET /api/places/videos/{jobId}/trip`의 SQL 문장 수가 같다.
   - 관리자 사용자 상세에서 재시도할 수 있는 실패 작업이 1개일 때와 5개일 때 SQL 문장 수가 같다.
   - `retryable` 값이 바꾸기 전과 같다: 가장 최근 작업만 true, 같은 링크의 더 나중 작업이 있으면 false, 재시도 대상이 아닌 실패는 false.

## 건드릴 파일

- `src/main/java/com/trova/backend/service/TripService.java` (`findExistingTripForVideo`만)
- `src/main/java/com/trova/backend/repository/SavedPlaceRepository.java` (조회 추가만)
- `src/main/java/com/trova/backend/admin/user/AdminUserService.java` (`detail`의 최근 작업 부분만)
- `src/main/java/com/trova/backend/admin/action/RetryJobAction.java`, `src/main/java/com/trova/backend/repository/ProcessingJobRepository.java` (필요할 때만)
- `src/test/java/com/trova/backend/controller/` 아래 새 테스트 파일

## 건드리지 말 파일

- `src/main/java/com/trova/backend/entity/` 전부
- `SavedPlaceRepository.java`의 기존 메서드(열린 PR #133이 이 파일을 고치고 있다 — 새 메서드는 파일 끝에 더한다)
- 관리자 조치의 감사 기록 흐름(`AdminActionService`, ADR 0026)
- `build.gradle`, `src/main/resources/`, `docs/`, `pipeline-test/`

## 완료 기준

- 두 곳 모두 항목 수와 상관없이 SQL 문장 수가 같다.
- 기존 `TripControllerTest`, `AdminUsersControllerTest`, 관리자 조치 테스트가 그대로 통과한다.
- 새 조회 수 테스트가 바꾸기 전 코드에서는 실패한다는 점을 보고에 적는다(직접 확인했으면 "확인", 아니면 "추정").
- `./gradlew build` 통과.

## 검증 명령

```bash
./gradlew build
```
