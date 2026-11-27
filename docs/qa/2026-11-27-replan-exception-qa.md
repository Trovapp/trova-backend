# 여행 일정 다시 짜기 예외 처리 QA

> **수정 후 계약** · 아래 표는 PR #552 시점의 재현 기록이다. 후속 수정은 ②에서 진행 중 작업 ID를 담은 409, ③에서 같은 UUID 키의 202 재생과 READY 결과 한 건을 검증한다. 동시 접수도 같은 키와 서로 다른 키로 각각 검증한다. [API 계약](../api/trip-draft-admission.md) · [ADR 0061](../adr/0061-trip-draft-admission.md)

기존 수정 이후 모의 Gemini가 503 또는 타임아웃으로 끝나도 원본 일정 A를 홈과 상세에서 그대로 조회할 수 있었다. 새로 재현한 문제는 **첫 접수 응답을 잃고 같은 요청을 다시 보내면 하나의 사용자 동작으로 생성 작업 2개가 실행되고 대체 일정 2개가 홈 목록에 남는 것**이다.

대상은 승인 전 일정 초안의 ‘조건 바꿔 다시 짜기’와 위치 변경 후 ‘다시 짜기’다. 과거 제보와 수정은 #424 기록에서 확인했다. 승인된 여행의 장소 재추천 `/api/trips/{id}/replan`은 별도 기능이며, 그 기능의 중복 접수 정책(ADR 0025)은 이 초안 API에 적용되지 않는다. [기존 수정 기록](../experience-notes.md) · [ADR 0025](../adr/0025-replan-admission-quota.md)

## 시나리오별 결과

A는 기존 09:00 공원 방문 일정, B와 C는 접수 순서대로 만든 대체 초안이다. 목록 순서는 앱이 호출하는 `GET /api/trip-drafts/auto`의 실제 응답 순서다. 아래 줄 번호는 [QA 당시 테스트](https://github.com/Trovapp/trova-backend/blob/39ec2a4/src/test/java/com/trova/backend/controller/TripDraftReplacementQaTest.java) 기준이다.

| 재현 조건 | 기대 동작 | 실제 동작 | 근거가 되는 테스트·로그·코드 위치 | 판정 |
|---|---|---|---|---|
| ① A에서 B 요청, AI가 503을 3회 반환 | B 실패 종료, A 내용과 노출 유지, 재시도 가능 | B는 DB·상세 모두 FAILED. A의 DB 일정 JSON과 미닫힘 상태를 확인했고, 목록·A 상세 응답 전체가 요청 전과 같았다. | 107행, 매개변수 503 | 정상 확인 |
| ① 같은 조건에서 소켓 타임아웃 3회 | 작업이 진행 중에 남지 않고 A를 다시 열 수 있음 | B는 FAILED, 목록은 A 한 건. A 상세와 DB 원문 보존. | 107행, 매개변수 timeout | 정상 확인 |
| ① 실패 후 같은 조건으로 재시도하고 AI 성공 | 새 일정으로 교체 | 새 ID가 202로 접수됐다. 11:00 일정이 READY가 되고 목록에는 새 ID만 남았다. A는 닫혔지만 ID로 조회하면 이전과 같은 상세를 반환했다. | 127–134행, 두 실패 조건 각각 실행 | 정상 확인 |
| ② A에 오전 요청 B를 보내 AI에서 멈춘 뒤 오후 요청 C를 제출, C 먼저 완료 | 활성 작업 중 조건 변경을 막을지, 두 요청 중 무엇을 채택할지 결정 필요 | 202 2회, 서로 다른 큐 ID 2개, 모델 호출 2회. C 접수 직후 목록은 C·B·A. C 완료 직후 C(READY)·B(PROCESSING), B 완료 후 C(READY, 13:00)·B(READY, 11:00). B가 C의 본문을 덮어쓰지 않았고 둘 다 남았다. | 138행, 래치로 처리 순서를 고정해 각 단계에서 GET | 정책 확인 필요 |
| ③ 첫 202 본문을 버리고 B가 대기 중일 때 동일 본문 재전송 | 한 번의 사용자 요청을 재시도하면 하나의 결과를 복구 | 두 번 접수하고 두 번 생성. 재조회하면 C·B 둘 다 READY. 두 일정의 시각은 각각 13:00과 11:00이었다. | 176행, 매개변수 false | 결함 재현 |
| ③ 첫 202 본문을 버리고 B가 완료되어 A가 이미 내려간 뒤 동일 본문 재전송 | 완료된 최초 결과를 복구 | 재시도 직전 목록은 B 하나였다. 서버는 다시 C를 생성했다. 최종 DB·상세는 둘 다 READY, 목록은 C·B였다. | 176행, 매개변수 true | 결함 재현 |
| 같은 앱 화면에서 시작·재생성 핸들러를 각각 연속 호출 | 같은 탭 동작에서 POST 한 번 | 각 핸들러의 동시 호출 2회당 생성 함수 1회. READY에서 교체, FAILED에서 A로 복귀하는 상태 규칙도 통과했다. | 앱 `analytics-events.test.mjs:41`, `plan-request.test.mjs:65`, `screen-rules.test.mjs:784` | 정상 확인 |
| 기기에서 전송 중 연결이 끊긴 뒤 앱을 재실행 | 사용자가 실제 보게 되는 화면 확인 | API 응답과 DB 상태까지만 검증했다. 반복 GET과 앱 상태 규칙은 실행했고, 네트워크 단절·실기기 화면은 실행하지 않았다. | MockMvc에서 첫 응답 본문 미소비, 두 번째 POST 후 새 GET | 환경상 미검증 |

## 요청에서 화면 갱신까지

1. 앱의 조건 변경 버튼은 이전 초안 ID와 입력 조건을 복원하고, ‘일정 짜기’를 눌렀을 때 전송한다. 위치 변경 후 ‘다시 짜기’는 즉시 전송한다. 두 핸들러가 같은 ref 잠금을 쓰며 응답 처리 후 잠금을 해제한다. POST 본문에는 영상 ID, 조건, `replacesDraftId=A`가 들어간다. [버튼과 핸들러](https://github.com/Trovapp/trova-app/blob/b24d928/src/screens/PlanTripScreen.tsx#L173) · [API 요청](https://github.com/Trovapp/trova-app/blob/b24d928/src/lib/api/tripDrafts.ts#L59)
2. 서버는 요청마다 새 PENDING 초안을 저장한 다음 ID를 큐에 넣고 202를 보낸다. 목록 API는 미닫힘 PENDING·PROCESSING·READY를 최신 생성순으로 최대 10개 반환한다. 따라서 B는 **접수 직후 진행 카드**로 노출될 수 있다. 이 테스트에서는 그때 A의 완성 일정도 함께 조회됐다. [접수](../../src/main/java/com/trova/backend/controller/TripDraftController.java) · [목록 필터](../../src/main/java/com/trova/backend/service/AutoDraftQueryService.java) · [정렬](../../src/main/java/com/trova/backend/repository/TripDraftRepository.java)
3. 비동기 큐는 `process`를, Kafka 리스너는 `processNow`를 부른다. 작업자는 초안 ID 단위로 직렬화하고 PROCESSING으로 바꾼다. 일정 그래프가 Gemini에 생성 요청을 보낸다. 503·타임아웃 재시도까지 실패하면 B를 FAILED로 저장한다. B는 목록에서 빠지고 B 상세로 실패를 조회할 수 있다. [큐](../../src/main/java/com/trova/backend/queue/AsyncDraftJobQueue.java) · [리스너](../../src/main/java/com/trova/backend/queue/DraftJobListener.java) · [그래프](../../src/main/java/com/trova/backend/planner/TripPlanGraph.java) · [Gemini 재시도](../../src/main/java/com/trova/backend/planner/GeminiJsonClientImpl.java)
4. 성공 경로는 **B의 READY 상태와 일정 JSON을 먼저 저장**하고 이어 A의 `dismissedAt`을 저장한다. 첫 저장 뒤 B의 완성 내용을 조회할 수 있고, 다음 저장 뒤 A가 홈 목록에서 빠진다. A 상세는 계속 200이다. 두 저장 사이의 정확한 순간은 코드 순서로 확인했고, 테스트는 처리 전후의 커밋 결과를 별도 요청으로 읽었다. [저장 순서, 286–289행](../../src/main/java/com/trova/backend/service/TripPlannerService.java) · [상세 조회, 131행](../../src/main/java/com/trova/backend/controller/TripDraftController.java)
5. 앱은 새 ID의 상세를 진행 중에 1초마다 조회한다. READY면 이전 초안 닫기를 요청하고 홈 캐시를 갱신한다. 새 초안이 FAILED이고 이전 초안이 READY면 오류를 표시하고 이전 ID를 다시 연다. 홈과 영상 기록은 목록에서 첫 READY를 선택하므로 완료 역전 사례의 첫 READY는 C다. [폴링·교체](https://github.com/Trovapp/trova-app/blob/b24d928/src/screens/PlanTripScreen.tsx#L134) · [홈](https://github.com/Trovapp/trova-app/blob/b24d928/src/screens/HomeScreen.tsx#L126) · [영상 기록](https://github.com/Trovapp/trova-app/blob/b24d928/src/screens/VideoGroupScreen.tsx#L86)

## 재현된 결함과 수정 후보

**응답 유실 후 동일 생성 요청이 두 번 실행된다.** 같은 초안에서 다시 짜기를 한 번 의도한 사용자가 서로 다른 완성 일정 두 개를 받는다. 테스트에서는 동일 입력 두 번에 방문 시각이 다른 모델 응답을 주었다. 결과를 다시 열면 어느 ID를 이어서 봐야 할지 모호하고, 생성 호출도 두 번 소비한다.

**가장 짧은 재현 절차** — 홈에서 조회되는 A를 준비한다. A를 대체하는 POST의 202 본문을 소비하지 않고 동일 본문을 한 번 더 보낸다. 큐에 들어간 두 ID를 성공 처리한 뒤 홈 목록을 다시 읽으면 두 READY ID가 나온다. 첫 작업이 이미 완료된 시점에 재시도해도 같다.

**수정 후보** — 앱이 생성 동작마다 요청 키를 만들고 통신 재시도·화면 복구 시 같은 키를 재사용한다. 서버는 사용자와 키의 조합으로 최초 초안 ID를 원자적으로 저장해 진행 중과 완료 후 모두 그 ID를 반환한다. 큐 등록도 최초 접수에서 한 번만 수행한다. 수정 시 ③의 두 테스트를 기대 동작(같은 ID, 생성 1회, READY 카드 1개)으로 바꿀 것.

**정책 확인 필요** — 서로 다른 조건의 B·C를 사용자가 의도적으로 보낸 경우는 별개다. 확인한 초안 명세와 기존 테스트에는 두 결과 중 하나를 최종으로 채택하는 규칙이 없다. 단일 결과가 필요하다면 같은 A의 활성 요청을 제한하거나, 교체 요청의 버전을 저장하고 선택한 버전만 목록에 남기는 방법이 있다. 정책 확정 뒤 ②의 단언을 바꾼다. [초안 설계](../superpowers/specs/2026-10-07-auto-draft-and-push-design.md) · [기존 목록 테스트](../../src/test/java/com/trova/backend/controller/TripDraftAutoControllerTest.java) · [기존 생성 실패 테스트](../../src/test/java/com/trova/backend/service/TripPlannerServiceTest.java)

## 실행 근거

- 백엔드 기준 `772d227`, 앱 기준 `b24d928`. H2 메모리 DB에 가상 사용자, 완료 영상, 장소 한 곳과 READY 초안을 저장했다. MockMvc 인증 요청은 실제 컨트롤러·JPA·일정 그래프·모델 재시도 코드를 거쳤다. 큐 접수, 외부 모델과 영업시간 조회만 대역으로 두었다. 작업자는 큐 ID에 대해 직접 실행했고, 완료 순서 역전에는 별도 스레드를 사용했다.
- `.\gradlew.bat test --tests '*TripDraftReplacementQaTest'` — 신규 5건 통과, 실패·건너뜀 0. 재실행 시 JUnit 결과는 `build/test-results/test/TEST-com.trova.backend.controller.TripDraftReplacementQaTest.xml`에 생성된다.
- 앱 Node.js 24에서 `node --experimental-strip-types --test scripts/plan-request.test.mjs` 17건, `--test-name-pattern='조건 바꿔 다시 짜기'`로 화면 연결 규칙 1건, `--test-name-pattern='PlanTrip 요청'`으로 실제 핸들러 연타 대역 테스트 1건 통과. 앱을 실행하지 않아 종단 간 동작은 API 응답과 DB 상태까지만 검증했다. 앱 판정은 Node 테스트와 소스 확인에 한정했다. [상태 규칙](https://github.com/Trovapp/trova-app/blob/b24d928/scripts/plan-request.test.mjs) · [화면 연결](https://github.com/Trovapp/trova-app/blob/b24d928/scripts/screen-rules.test.mjs#L784) · [핸들러 호출](https://github.com/Trovapp/trova-app/blob/b24d928/scripts/analytics-events.test.mjs#L41)
- Windows에서 `.\gradlew.bat build` 성공(7분 43초). JUnit XML 1,594건 중 1,571건 통과, 23건 건너뜀, 실패·오류 0건. 신규 테스트 5건은 전체 빌드에서도 모두 통과했다.

## 포트폴리오에 넣을 검증 사실

H2와 모의 Gemini로 503·타임아웃을 각각 세 번 발생시켜 기존 수정 이후 원본 일정의 DB 내용과 홈·상세 응답이 유지되고 재시도 성공 시 새 일정 하나로 교체됨을 검증했다. 같은 생성 요청의 202 응답을 버리고 첫 작업 완료 전후에 각각 재전송해, 두 경우 모두 생성 작업 두 개가 실행되고 READY 일정 두 개가 홈 조회에 남는 결함을 재현했다.

## PR #553 리뷰 회귀 테스트

`TripDraftReplacementQaTest`는 키 없는 질문 대기 재접수, 구버전 원본 번호가 없는 진행 행, 정리 직후 늦게 돌아온 모델 응답을 검증한다. 교체 직전 읽은 초안을 뒤늦게 닫아도 종료한 질문은 복원되지 않아야 한다.

기본 테스트는 H2에서 실행한다. PostgreSQL에서는 루프백 `127.0.0.1:56553`의 일회용 DB `trova_draft_qa`와 소유자 `draft_qa`를 준비한다. `TROVA_DRAFT_POSTGRES_QA=1`과 `TROVA_DRAFT_QA_PASSWORD`를 설정한 뒤 `./gradlew test --tests '*TripDraftReplacementQaTest'`를 실행한다. 테스트가 이 DB의 스키마를 생성하고 종료 시 지운다. PostgreSQL 지연 제약을 사용해 INSERT 이후 커밋 실패 시 큐가 비어 있는지 확인하고, 정상 접수 시 큐 콜백이 별도 연결로 PENDING 행을 읽는지 검사한다.
