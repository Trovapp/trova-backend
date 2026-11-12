# 0043 작업 저장과 Kafka 전달 사이에서 작업이 사라지지 않게 — 아웃박스 패턴을 쓸지 정한다
- 상태: 확정(3번, 사용자 선택 "1" 2026-11-12) · 날짜: 2026-11-12 · 근거: 이슈 #399, #261(닫힘)에서 남은 질문, [0021](0021-share-job-queue-kafka-optional.md) "남은 Kafka 후보: 아웃박스(#261 근본 해결)"
- 관련: [0002](0002-cost-zero-free-tiers.md)(비용 0원), [0014](0014-admin-actions-and-suspension.md)(멈춘 작업은 자동 정리), [0024](0024-single-jvm-analysis-exclusion.md)(단일 JVM 중복 실행 막기), [0026](0026-admin-action-atomic-audit.md)(큐 전달은 커밋 뒤, outbox는 도입하지 않음)
- 사용자가 3번(멈춘 작업 다시 보내기)을 골랐다. 구현은 아래 "결정" 절.

## 풀려는 문제
작업 하나는 "DB에 작업 행 저장(커밋)" → "Kafka로 작업 id 보내기(실패하면 서버 안 대기열)" 두 단계로 시작된다.
둘은 한 트랜잭션이 아니어서, 사이에서 서버가 죽거나 보내기가 서버 안 대기열로 넘어간 뒤 죽으면 작업이 처리되지 않는다.
지금은 15분 뒤 실패(FAILED)로 정리되고 사용자가 다시 눌러야 한다. 이걸 그대로 둘지, 아웃박스 같은 구조로 닫을지 정한다.

## 지금 코드가 하는 일 (2026-11-12, e981bd0 기준)
대기열 세 개(공유 분석·일정 초안·일정 재구성)가 같은 구조다. `src/main/java/com/trova/backend/queue/`

- 보내기: `KafkaShareJobQueue.java:59`(초안·재구성도 같음) — `send(...).get(5초)`. 실패하면 `:68-77`에서 1분 동안 Kafka를 건너뛰고
  서버 안 `@Async` 실행기로 넘긴다(지표 `trova.queue.*fallback`).
- 받기: 리스너가 `processNow`를 같은 스레드에서 끝까지 돌린 뒤 오프셋을 커밋한다(최소 한 번 전달). 처리 중 강제 종료는 다시 받아 끝까지 한다 —
  운영 실험(2026-11-12) 자동 초안 2분 29초, 재구성 작업 12번 2분 23초, 외부 호출 중복 없음.
- 다시 받아도 안전한 이유: 상태 확인 + id별 잠금.
  - 분석 `PlaceExtractionService.java:97-98`(잠금) → `ProcessingJobLifecycleService.java:51-57`(DONE·FAILED·분석 결과 있음이면 건너뜀)
  - 초안 `TripPlannerService.java:131-142`(PENDING·PROCESSING만, `:47`)
  - 재구성 `TripReplanJobLifecycleService.java:36-44`(PENDING·PROCESSING만)
  - #261의 "보내기 응답이 5초 넘게 늦어 서버 안 대기열로 넘겼는데, 늦게 도착한 Kafka 메시지도 돌아 두 번 실행" 문제는 위 잠금·상태 확인으로 막혀 있다.
    같은 JVM 안에서는 두 번째 실행이 첫 실행이 끝나기를 기다린 뒤 끝난 상태를 보고 건너뛴다(ADR 0024). 즉 #261에서 남은 것은 "중복"이 아니라 "유실"이다.
- 저장과 보내기 순서(트랜잭션 경계). `spring.jpa.open-in-view: false`(`application.yml:30`), 컨트롤러 메서드에 `@Transactional` 없음 → `save`가 끝나면 이미 커밋.
  - 공유 `SharesController.java:123` 저장 → `:125` enqueue
  - 재구성 `TripReplanController.java:151` 저장 → `:154` enqueue
  - 직접 초안 `TripDraftController.java:89`(`TripPlannerService.create`는 트랜잭션 없음, `TripPlannerService.java:73`), 답하기 `:105`(`answer`는 `@Transactional`, `TripPlannerService.java:96`이 끝난 뒤)
  - 자동 초안 `AutoDraftService.java:61`(분석 완료 커밋 뒤 `PlaceExtractionService.java:201`에서 부름)
  - 관리자 재실행 `RetryJobAction.java:68-77` — `afterCommit`에서 enqueue(ADR 0026)
- 멈춘 작업 처리: `OrphanedJobRecoveryService`가 서버 시작 때(`:60`)와 5분마다(`:67`) **15분**(`:39`) 넘게 갱신 없는 PENDING·PROCESSING을
  **FAILED로 바꾼다**(다시 돌리지 않는다). 재구성은 5분 넘은 작업을 재사용하지 않고 새로 받는다(`TripReplanController.java:127, 143-145`).
  다시 보내기(re-enqueue)를 하는 코드는 없다.
- 서버 안 대기열은 종료 신호(SIGTERM) 때 최대 60초 기다린다(`AsyncConfig.java:91-92`). SIGKILL·OOM·VM 재부팅에는 효과 없다.

## 코드로 확인한 틈
| 틈 | 무슨 일 | 지금 결과 |
|---|---|---|
| (a) 커밋 뒤, 보내기 전에 죽음 | 행은 PENDING인데 Kafka에도 서버 안 대기열에도 없다. 틈은 `save` 커밋부터 Kafka 응답까지(보통 수십~수백 ms, 최대 5초) | 15분 뒤 FAILED, 사용자가 다시 시도 |
| (b) 보내기 실패 → 서버 안 대기열로 넘긴 뒤 죽음 | Kafka 장애 동안(1분 건너뛰기 포함) 들어온 작업은 메모리에만 있다. 분석 한 건이 수십 초~수 분이라 틈이 (a)보다 훨씬 길다 | 15분 뒤 FAILED |
| (c) 롤백될 트랜잭션 안에서 enqueue | **지금은 없음** — 위 다섯 곳 모두 커밋 뒤에 보낸다. 다만 누가 호출부를 `@Transactional`로 감싸면 커밋 전 메시지가 도착해 `findById`가 빈 값 → 건너뜀(유실)이 될 수 있다. 구조가 아니라 관례로 막고 있다 | 해당 없음(앞으로의 위험) |
| (d) 리스너 예외 → 실패 작업 토픽 | 다시 돌리지 않는다(`KafkaQueueConfig.java:61`, `FixedBackOff(0,0)`). 행은 PENDING·PROCESSING으로 남는다 | 15분 뒤 FAILED, 사람이 관리자 재시도 |
| (e) Kafka에서 15분 넘게 기다림 | 소비 지연이 길면 정리기가 먼저 FAILED로 바꾸고, 늦게 받은 메시지는 건너뛴다 | FAILED(0021에 적힌 대가) |

## 운영 데이터 (2026-11-12, 읽기 전용 조회, `users.provider='loadtest'` 제외)
| 표 | 전체 | 최근 14일 | 지금 PENDING·PROCESSING | 1시간 넘게 멈춤 | 정리기가 FAILED로 바꾼 것(전체) |
|---|---|---|---|---|---|
| `processing_jobs` | 82 | 50 | 0 | 0 | 0 |
| `trip_drafts` | 15 | 8 | 0 | 0 | 0 |
| `trip_replan_jobs` | 10 | 0 | 0 | 0 | 0 |

- 하루 최대 분석 22건(2026-11-01). 실패 6건은 모두 파이프라인 오류(다운로드·시간 초과)이고 "처리가 중단됐어요" 정리는 0건.
- 한계: QA·측정 데이터는 qaflow가 지우므로 지워진 행은 세지 못했다. "틈이 실제로 터진 적이 없다"는 남아 있는 행 기준이다.
- 해석: 지금 규모에서 (a)(b)는 실측으로 드러난 손실이 없다. 문제는 확률이 낮지만 생기면 사용자가 15분 기다린 뒤 다시 눌러야 한다는 것이다.

## 선택지
| | 닫는 틈 | DB 비용(Supabase 무료) | Kafka 비용(Aiven 무료) | 메모리(힙 144MiB) | 복잡도 |
|---|---|---|---|---|---|
| 1. 그대로 두고 기록만 | 없음 | 0 | 0 | 0 | 0 |
| 2. 트랜잭션 아웃박스 | a, b, c | 새 테이블 + 주기 조회 | 새 토픽 없음 | 작음 | 큼 |
| 3. 멈춘 작업 다시 보내기(정리기 확장) | a, b, (d 일부) | 기존 조회에 조건 추가 | 새 토픽 없음, 중복 메시지 소량 | 거의 0 | 작음 |
| 4. CDC(Debezium·Supabase Realtime) | a, b, c | 논리 복제 슬롯 | 커넥터 실행 장소 필요 | 커넥터 JVM이 서버에 안 들어감 | 매우 큼 |
| 5. DB 테이블 대기열(0021 대안 1) | a, b, c | 주기 조회 | Kafka를 안 씀 | 작음 | 중간 |

### 1. 그대로 두고 기록만
- 지금의 15분 FAILED 정리를 유지하고, 틈을 이 ADR과 Runbook(`docs/runbooks/kafka-queue.md`)에 적는다.
- 장점: 비용·코드 0. 실측 손실 0건(위 표).
- 단점: (a)(b)가 생기면 사용자가 15분 "처리 중"을 본 뒤 실패를 받는다. (c)는 관례로만 막는다.

### 2. 트랜잭션 아웃박스
- 작업 행과 `job_outbox`(queue, job_id, created_at, sent_at, attempts) 행을 한 트랜잭션에 저장한다. 그러려면 다섯 호출부를 서비스 트랜잭션으로 감싸야 한다.
- 전달: 커밋 뒤 바로 보내 보고(`afterCommit`, 화면 대기 시간 유지), 성공하면 `sent_at`을 채운다. `@Scheduled` 릴레이가 `sent_at is null and created_at < now()-N초`를 다시 보낸다.
  - (b)를 닫으려면 서버 안 대기열로 넘겼을 때는 `sent_at`을 채우지 않아야 한다. 브로커가 돌아오면 릴레이가 Kafka로 다시 보내고, 이미 끝났으면 상태 확인이 건너뛴다.
  - 서버가 하나라 릴레이 잠금은 `@Scheduled` 단일 스레드면 충분하다. 서버가 늘면 `FOR UPDATE SKIP LOCKED`가 필요하다.
- 비용: 새 토픽 없음(무료 5개 중 4개 사용 유지). Supabase 무료 용량 500MB(레포 문서 기준)에 행이 쌓이므로 보낸 행은 지운다.
  릴레이를 10초마다 돌리면 하루 8,640번 조회 — 인덱스(`sent_at is null` 부분 인덱스)로 가볍지만 0은 아니다.
- 단점: 작업 테이블이 이미 "아직 안 끝난 작업" 목록(PENDING)이라 같은 정보를 두 번 저장한다. 다섯 호출부·세 큐·테스트를 모두 바꾼다.
  (d)(e)는 닫지 못한다(보낸 뒤의 문제라서).

### 3. 멈춘 작업 다시 보내기 (작업 테이블을 아웃박스로 쓰기)
- 작업 행의 PENDING·PROCESSING 상태 자체가 "아직 전달·처리되지 않음" 표시다. 새 테이블 없이 정리기를 확장한다.
- 안(구체 값은 구현 계획에서 정한다):
  - 1~2분마다 `PENDING`이고 `updated_at`이 2분 넘게 지난 작업, `PROCESSING`이고 갱신이 임대 시간(예: 8분, 실측 처리 최대보다 길게) 넘게 없는 작업을 각 큐의 `enqueue`로 다시 보낸다.
  - 생성 후 15분이 지나면 지금처럼 FAILED — 다시 보내기 횟수가 저절로 제한된다. Gemini 하루 한도를 지키려고 작업당 다시 보내기 상한(예: 1회)을 둔다.
    상한 기록은 `ProcessingJob.retryCount`처럼 행에 두거나, 초안·재구성에는 열을 더한다(스키마 변경).
  - 다시 보낸 메시지가 아직 돌고 있는 작업과 겹쳐도 잠금·상태 확인이 건너뛴다(같은 JVM). 다만 기다리는 동안 리스너 스레드 하나를 잡는다.
- 닫는 틈: (a)(b). (d)는 다시 돌리면 같은 이유로 또 실패할 수 있어 상한 1회 뒤 FAILED. (c)는 지금 없는 틈이지만 생겨도 PENDING 행이 남으므로 이 장치가 살린다.
- 비용: 새 테이블·토픽 없음. 조회는 지금의 5분 정리기와 같은 형태(상태+갱신 시각). 다시 보내는 메시지는 몇 바이트라 125KiB/s와 무관.
- 대가: 정상 경로보다 최대 수 분 늦게 처리된다(15분 FAILED보다는 낫다). 로컬과 운영이 같은 DB를 쓰면 서로의 작업을 다시 보낼 수 있다 — 지금은 로컬이 개발 DB라 해당 없음.

### 4. CDC(Debezium·Supabase Realtime)
- DB 변경 기록을 읽어 Kafka로 내보낸다. Debezium은 Kafka Connect 워커(별도 JVM)가 필요한데 운영 서버(956MB, 앱 힙 144MiB)에 같이 띄우지 않는다는 0021 원칙과 부딪힌다.
- Aiven 무료 플랜에서 Kafka Connect를 쓸 수 있는지, Supabase 무료 플랜에서 논리 복제·Realtime을 이 용도로 쓸 수 있는지는 레포 문서로 확인되지 않았다(확인 전). 처리량 이득도 없다.
- 결론: 후보에서 뺀다.

### 5. DB 테이블 대기열(Kafka 없이)
- 0021에서 이미 비교한 대안 1. 틈을 모두 닫지만 사용자가 고른 Kafka 구조를 걷어내는 것이라 이 ADR 범위 밖이다. 0021을 바꿀 때만 다시 본다.

## 결정 (2026-11-12)
3번을 고른다. 구현 `StuckJobRequeueService`:
- 1분마다(서버 시작 2분 뒤부터) 작업 표 세 개에서 PENDING이고 3분 넘게 갱신 없는 것, PROCESSING이고 8분 넘게 갱신 없는 것을 고른다.
  정리기 기준(15분)을 넘긴 것은 고르지 않는다 — 그건 지금처럼 FAILED.
- 각 큐의 `enqueue`로 다시 보낸다(운영은 Kafka, 실패 시 서버 안 대기열 — 보내는 길은 처음과 같다).
- 작업당 한 번만(서버 메모리에 기록, 15분 지나면 지움). 서버가 재시작되면 기록이 사라져 한 번 더 보낼 수 있지만 15분 FAILED가 끝을 정한다.
  그래서 행에 횟수 열을 더하지 않았다(스키마 변경 없음).
- 지표 `trova.queue.requeued{queue=share|draft|replan}`(0으로 미리 만듦). 끄기 `APP_QUEUE_REQUEUE_ENABLED=false`, 테스트에서는 꺼 둔다.
- 대가: 소비 지연이 3분 넘는 PENDING도 다시 보내 메시지가 하나 더 생긴다. 같은 id 잠금에서 기다린 뒤 끝난 상태를 보고 건너뛴다.
  실패 작업 토픽으로 간 작업(d)도 한 번은 다시 돈다(Gemini 1회 더 가능).

## 추천 (결정 전 기록)
- **3번(멈춘 작업 다시 보내기)을 추천한다.** 작업 테이블이 이미 아웃박스 역할을 할 상태를 갖고 있고, 잠금·상태 확인으로 중복 실행이 안전하다는 것이
  운영 강제 종료 실험으로 확인됐다. 새 테이블·토픽·호출부 변경 없이 (a)(b)를 닫고, 지금 0건인 손실 규모에 맞는 비용이다.
- 2번은 "아웃박스 패턴을 운영에 적용했다"는 경험 자체가 목적이면 고를 만하다. 이때도 호출부 다섯 곳을 서비스 트랜잭션으로 옮기는 일이 먼저다.
- 1번은 지금 규모에서 합리적이지만, (b)는 Kafka 장애 때 몰려서 생기므로 장애 한 번에 여러 건이 동시에 15분 실패가 될 수 있다.
- 사용자가 고르면 상태를 `확정`으로 바꾸고, 0026 "한계" 단락이 가리키는 "기존 멈춘 작업 복구 정책"이 바뀌는 것이므로 0026에 이 ADR 링크를 단다(그때 사용자 확인).

## 검증 계획 (고른 선택지를 구현할 때)
- 구현 때 한 것(로컬): 단위 6개(`StuckJobRequeueServiceTest` — 고르는 시간 범위, 대기열별 보내기·지표, 한 번만, 기록 지우기, 한 건 실패해도 계속),
  H2 통합 2개(`StuckJobRequeueServiceIntegrationTest` — 실제 조회로 1분·5분·10분·20분·DONE·NEEDS_INPUT 구분). Kafka 왕복은 기존
  `*KafkaRoundTripTest`가 enqueue → 리스너 → 같은 작업 다시 받기를 이미 보므로 따로 더하지 않았다. 운영 실험은 아래 1번이 남았다.
- 단위 테스트(한글 이름)
  - 3번: `void PENDING이_2분_넘게_멈추면_다시_보낸다()`, `void 다시_보내기는_작업당_한_번만()`, `void 15분_지나면_다시_보내지_않고_FAILED()`,
    `void 갱신이_최근인_PROCESSING은_건드리지_않는다()`.
  - 2번: 저장 트랜잭션이 롤백되면 아웃박스 행도 없음, 서버 안 대기열로 넘기면 `sent_at`이 비어 있음, 릴레이가 보낸 뒤 `sent_at`을 채움.
- Kafka 왕복 테스트(내장 Kafka, 기존 `ShareJobKafkaRoundTripTest`·`ReplanJobKafkaRoundTripTest`·`DraftJobKafkaRoundTripTest`와 같은 방식)
  - 행만 저장하고 보내지 않은 상태(틈 a 재현) → 정리기/릴레이 한 번 → 리스너가 받아 DONE.
  - 같은 작업 메시지 두 개(다시 보내기 + 원래 메시지) → 외부 호출 1번(가짜 파이프라인 호출 수로 확인).
  - `ShareJobProcessRecoveryTest`처럼 처리 중 소비자를 닫은 뒤 다시 받는 경우와 다시 보내기가 겹쳐도 한 번만 실행.
- 운영 실험(테스트 계정, Gemini 사용량 먼저 확인, QA·측정과 동시에 하지 않음)
  1. 틈 b: 운영 서버에서 Aiven 브로커 주소로 나가는 연결을 잠깐 막는다(iptables OUTPUT DROP, 실험 뒤 즉시 해제) → 공유 → `trova.queue.fallback` 증가 확인
     → 분석이 PROCESSING일 때 `systemctl kill -s SIGKILL` → 연결 막기 해제 → 재시작 뒤 몇 분 안에 다시 보내져 DONE인지(지금 동작이면 15분 뒤 FAILED).
  2. 틈 a: 코드로 재현하기 어렵다 — 테스트 계정으로 PENDING 행을 직접 만들지 않고, 로컬 왕복 테스트로 대신한다.
  3. 기록: 시작~완료 시간, Gemini 호출 수, 다시 보낸 횟수 지표를 `docs/experience-notes.md`에 로컬·운영 구분해 남긴다.
- **운영 확인(2026-11-12, 18dd819, 테스트 계정, #404) — 틈 b가 닫혔다.**
  - 10:58:22 운영 서버 OUTPUT에서 Aiven Kafka 포트(tcp 26991)만 REJECT → 10:58:24 릴스 공유(작업 5669) → 5초 뒤 "Kafka에 넣지 못해 서버 안 대기열로 넘김"
  - 10:58:30 PROCESSING → 10:58:35 `systemctl kill -s SIGKILL` → 연결 막기 해제. 작업은 메모리에만 있었으므로 Kafka 재수신 대상이 아니다.
  - 11:06:47 `StuckJobRequeueService`가 "멈춘 작업 다시 보냄: share 5669"(마지막 갱신 뒤 8분 17초) → 11:07:17 **DONE**(공유부터 8분 53초).
  - 예전 동작이면 10:58:30 + 15분 = 11:13:30에 FAILED("처리가 중단됐어요")였다. 지표 `trova_queue_requeued_total{queue="share"}` 1.
  - Gemini는 다시 한 분석 2번(collect·filter) + 자동 초안 1번. 죽기 전 첫 분석은 Gemini 호출 기록이 남기 전에 끊겼다.

## 다시 볼 조건
- 서버가 두 대 이상이 되면: 3번의 같은 JVM 잠금 가정이 깨지므로 행 잠금(`SKIP LOCKED`)이나 2번을 다시 본다(0024·0025와 같은 조건).
- Kafka 넘기기 지표(`trova.queue.*fallback`)나 "처리가 중단됐어요" FAILED가 실제로 쌓이기 시작하면 우선순위를 올린다.
- Oracle A1 확보로 브로커를 직접 띄우게 되면 4번의 실행 장소 문제를 다시 본다.
