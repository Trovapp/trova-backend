# 관리자·추천·푸시·재구성 후속 코드 점검 (#267)

검토 기준: main `4307b79ace781dec36b26bff8cb5df83995a041e`(PR #262·#263·#265 머지 후). 이 기록은 제품 코드 수정이 아니라 검토 결과다. 아래 문제는 세 PR의 런타임 변경 파일 밖에 있던 기존 경로이며, 이번 머지의 새 회귀로 분류하지 않는다.

## 우선 수정할 사항

### P1 — 재구성 요청이 장소 호출 한도를 확인하지 않음

`TripReplanController.replan()` → `resolveJobId()` → `TripReplanJobService.process()`에는 `DailyQuotaService` 확인이 없다. 그래프는 최대 10개 대상마다 `AlternativeFinderService`를 불러 Google Places 검색 및 필요시 Gemini 연동으로 들어간다. 일반 추천·대안·빈 시간 추천의 컨트롤러에는 장소 호출 한도가 있지만 이 진입점은 빠져 있다.

한도를 1로 둔 실제 DailyQuotaService를 소진해 다음 장소 호출이 거절되는 상태를 만든 뒤, 같은 사용자의 실제 재구성 컨트롤러를 호출했다. 202와 새 jobId를 반환하고 작업자 호출이 발생했다. 컨트롤러의 사용자/여행/저장소/비동기 작업자는 mock이며, 실제 외부 API를 부르거나 운영 한도를 소진한 재현은 아니다. 컨트롤러가 해당 한도 서비스를 전혀 사용하지 않는 경계를 확인한 것이다.

완료 후 재요청이나 다른 여행 요청을 반복하면 사용자별 하루 장소 호출 제한의 보호를 받지 않는다. 풀 크기와 대상 수 제한은 하루 호출 제한을 대신하지 않는다.

권장 수정: 소유권·요청 유효성 확인 후 **새 작업을 만들 때만** 한도를 적용하고, 기존 작업 ID를 다시 돌려주는 폴링성 요청에서는 중복 차감하지 않는다. 작업 하나와 최대 여러 외부 호출의 계산 단위를 명시해야 한다. 실제 외부 호출 전체에 공통 예산을 도입하는 일은 더 큰 정책 결정이며 이번 리뷰에서 바꾸지 않는다.

근거: `controller/TripReplanController.java` 96–100, 122–137; `replan/TripReplanGraph.java` 247–262; `service/DailyQuotaService.java`; 비교 경로 `controller/TripController.java`의 `consumePlaceCall` 호출들.

### P1 — 감사 기록 저장 실패 전에 관리자 조치가 별도로 커밋됨

`AdminActionService.execute()`는 확인 토큰을 소비하고 `handler.execute()`가 반환한 뒤 `auditLog.record()`를 부른다. 전체를 감싸는 트랜잭션이 없고 삭제/숨김 등 핸들러는 자체 트랜잭션을 커밋한다. `AdminAuditLog.record()`도 별도 REQUIRES_NEW 트랜잭션이다. 따라서 조치 커밋 후 감사 DB 저장이 실패하면, HTTP 요청은 오류로 끝나지만 조치는 남고 감사 기록은 없다. 확인 토큰도 이미 소진돼 같은 요청의 단순 재시도로 복구할 수 없다.

실제 ActionService·ConfirmTokenStore·AdminAuditLog와 Spring 트랜잭션 프록시, H2 DB를 사용해 확인했다. fixture 핸들러가 DB 행을 커밋한 다음 감사 저장소에 실패를 주입했으며, 요청 예외 이후에도 조치 행 1개가 남고 토큰은 소진됐다. 실제 DeleteAccount/Trip의 데이터를 삭제한 테스트는 아니다. 제품 핸들러의 트랜잭션 경계는 코드로 확인했다.

권장 수정: DB 변경 조치와 성공 감사 기록의 커밋 경계를 함께 설계한다. 단순히 서비스 전체에 `@Transactional`을 붙이는 것으로 끝내면 안 된다. RetryJobAction은 작업 저장 커밋 후 enqueue해야 한다는 기존 계약이 있으며, 정지 목록 같은 메모리 부작용도 별도 처리해야 한다. 조치별 실패/커밋/큐 전달 테스트가 필요하다. ADR 0014의 기록 의무를 충족하는 구현 보강이며 이번 리뷰에서는 정책을 바꾸지 않는다.

근거: `admin/action/AdminActionService.java`의 handler 실행 → audit 호출; `AdminAuditLog.java`의 REQUIRES_NEW; `DeleteTripAction.java`, `DeleteAccountAction.java`, `HideDraftAction.java`, `RetryJobAction.java`.

## 후속 개선 사항

### P2 — 동일 재구성의 동시 제출이 서로 다른 작업을 생성함

`resolveJobId()`는 진행 중 작업 조회와 새 작업 저장을 분리한다. 두 요청이 동시에 빈 조회 결과를 받으면 각자 저장하고 비동기 처리를 시작한다. 엔티티에는 활성 작업의 조건부 유일 제약이 없다. 순차 중복 제출을 막는 기존 테스트가 이 경쟁을 막지는 않는다.

실제 컨트롤러 두 호출을 barrier로 같은 조회 시점에 맞췄다. mock 저장소의 빈 결과를 받은 두 요청은 서로 다른 jobId를 받고 save/worker 호출이 각각 2회였다. 실제 Postgres 경쟁을 실행한 것은 아니다. 단일 JVM에서의 접수 직렬화 또는 DB 원자적 생성 정책을 정하고, 기존 작업 재사용·한도 차감·큐 거절 시 정리까지 함께 검증하는 편이 좋다.

### P2 — 같은 장소의 동시 캐시 미스에서 상세 조회를 중복 호출함

`PlaceReviewService.getOrGenerateSummary()`는 캐시를 읽고, 없으면 상세 API를 호출한 뒤 요약을 저장한다. 같은 placeId에 대한 동시 miss를 묶는 장치가 없다. 요청별 고유 Python workDir는 파일 충돌만 막는다.

동일 장소의 독립된 엔티티 스냅샷을 반환하는 mock 저장소와 barrier를 둔 상세 클라이언트로 두 요청을 실행했다. 실제 서비스가 `getDetails()`를 2회 호출했다. 외부 요청은 mock이며 실제 과금이나 Gemini 중복 생성까지 실행한 것은 아니다. 권장 수정은 같은 장소의 진행 중 생성 작업을 공유하고 대기 후 캐시를 다시 읽는 방식이다. 실패 시 잠금/진행 상태 해제도 필요하다. 같은 최초 요청이 겹치는 비용 문제이며, 기존 캐시가 있는 순차 조회의 재사용은 유지된다.

### P2 — 형식이 잘못된 Expo 티켓을 성공으로 해석함

`HttpExpoPushClient.parseTickets()`는 status가 `error`인 경우만 실패로 만들고 나머지는 null(성공)로 넣는다. `{}`, JSON null, 미지의 status도 성공 티켓이 돼 `ExpoPushService`가 성공 횟수와 마지막 성공 시각을 갱신할 수 있다.

실제 파서에 `{"data":[{},null,{"status":"unknown"}]}`을 넣어 성공 null 3개를 확인했다. 운영에서 이런 응답이 관측됐다는 주장은 아니다. 명시적 `status=ok`만 성공으로 인정하고 나머지는 invalid-ticket 실패로 다루는 편이 좋다. 이는 수신 기기까지 최종 전달됐다는 보장과 별개의 응답 검증이다.

## 검토한 흐름과 한계

| 영역 | 이번에 상세히 읽은 핵심 경계 |
|---|---|
| 관리자 | AdminGuard, 로그인 교환 코드/용도/만료, 최근 로그인 확인, 확인 토큰, 실행 핸들러, 감사 저장, 사용자 검색/마스킹/소유자 번호 |
| 추천 | 추천 퍼널, 대안/빈 시간 후보, 카탈로그 upsert, 임베딩 batch·개인화 폴백, 리뷰/전화 캐시, Google 호출 재시도 |
| 푸시 | 커밋 후 이벤트·별도 풀, 웹/Expo 오류 분리, 구독 재할당·상한·조건부 갱신, 웹 endpoint 제한, HTTP timeout/retry, Expo 티켓 파싱 |
| 재구성 | POST/상태 소유권, 작업 재사용·진행/실패, 그래프 대상/반복 제한·날짜 경계, 후보 백트래킹·ThreadLocal 정리·직렬화 |

새 라이브러리 API나 버전을 도입하지 않았다. 외부 API·운영 데이터 변경 없이 5개 재현 probe가 현재 문제 동작을 확인했다. 이 probe는 **문제의 현 상태를 증명하는 검사**이므로 바람직한 제품 동작을 고정하는 회귀 테스트가 아니다. 소스는 `docs/reviews/probes/ExtendedReviewProbeTest.java`에 보관하고, 실행할 때만 src/test로 복사했으며 실행 후 제거했다. [실행 집계](extended-review-probes-20261031.json).

재현 명령(끝의 임시 복사본 삭제 포함). 아래 probe는 검토 당시 4307b79 소스용이다. 해당 버전을 별도 작업 공간에 준비하고 probe를 복사해 실행한다. 수정 후 main에서는 생성자와 기대 동작이 달라 역사적 probe를 현재 회귀 테스트로 실행하지 않는다:

```bash
mkdir -p src/test/java/com/trova/backend/reviews
test ! -e src/test/java/com/trova/backend/reviews/ExtendedReviewProbeTest.java
cp docs/reviews/probes/ExtendedReviewProbeTest.java src/test/java/com/trova/backend/reviews/
./gradlew test --tests '*ExtendedReviewProbeTest' -q
rm src/test/java/com/trova/backend/reviews/ExtendedReviewProbeTest.java
```

임시 probe를 제거한 뒤 일반 `./gradlew build -q`는 기존 951개 중 948 통과·실패/오류 0·opt-in 3 skip이었다. 정상 빌드 통과는 위 누락된 경계가 이미 방어된다는 의미가 아니다. 제품 코드·정답·운영 설정은 바꾸지 않았다.

관리자 지표의 모든 집계 SQL, 모든 엔티티/보안 분기의 동적 검증, 웹/모바일 화면, 실제 Expo/브라우저 전달, Google/Gemini 실패 응답 전체, 운영 부하·다중 JVM은 전수 감사하지 않았다. 앞선 공유·추출 검토와 이번 핵심 경계 검토를 합쳐 구조를 이어서 파악한 상태이며 전체 코드의 모든 줄을 검증했다고 표현하지 않는다. 기존 UTF-8 진행 로그 분할 디코딩 문제도 별도 미해결 상태다.


## 사용자 승인 후 수정·배포 상태

- 한도 누락과 동시 재구성 중복 접수는 #272(main60f1c4f)에서 수정했다. 새 생성 시도만 기존 장소 요청 한도를 쓰고 동일 여행의 접수를 단일 JVM에서 직렬화한다.
- 관리자 조치/감사 분리 커밋은 #273(main2ebf4b6)에서 수정했다. 실제 DB 롤백·커밋·큐 전달과 정지 목록 경계9개를 추가 검증했다.
- 두 수정과 이전 파이프라인 수정을 포함한 2ebf4b6을 운영에 배포하고 실제 영상105의 DONE·장소7개, 테스트 데이터 백업/정리0잔여를 확인했다(#274).
- 이 기록 PR도 최신 main에 맞춘 전체 build968개 중965통과·실패/오류0·opt-in3skip을 확인한다. 위 과거 재현 결과를 수정 후 현 상태로 오해하지 않는다. 남은 것은 상세 캐시 미스 중복 호출과 Expo 티켓 검증, 앞선 UTF-8 진행 표시 문제다.
