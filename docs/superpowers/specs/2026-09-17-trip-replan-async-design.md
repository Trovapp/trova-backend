# 전체 일정 재구성 — 비동기 처리 전환 설계 스펙

## 배경

"전체 일정 재구성"(PR #6, LangGraph4j 기반)을 실제 운영 DB로 실측한 결과, 6개
타겟 처리에 44~47초가 걸렸다(`docs/benchmarks/2026-09-16-trip-replan-latency.md`).
`MAX_TARGETS=10`(실제 배포 상한)까지 가면 약 75~80초로 추정되는데, 이는 대부분의
인그레스/모바일 HTTP 클라이언트 기본 타임아웃(보통 60초)을 넘어선다. 해당 벤치마크
문서에 "비동기 전환 전까지는 앱 UI 연결도, 실제 배포 노출도 하지 말 것"이라는 게이트를
명시적으로 남겼고, 이 스펙이 그 게이트를 해소한다.

CLAUDE.md의 API 설계 원칙("외부 API 호출이 포함된 처리는 반드시 비동기(@Async)로
처리")에도 정확히 해당하는 케이스이며, 이 프로젝트에는 이미 영상 처리 파이프라인이
쓰는 비동기 작업 패턴(`ProcessingJob`/`@Async("pipelineTaskExecutor")`/폴링)이 있다.
이 스펙은 그 패턴을 그대로 따르되, 재구성 전용 새 엔티티로 분리한다.

## 목표

- `POST /api/trips/{tripId}/replan`을 즉시 응답(202 + jobId)으로 바꾸고, 실제
  재구성 처리는 백그라운드에서 진행한다.
- 진행 중인 작업의 진행률("N개 중 M개 처리")을 폴링으로 확인할 수 있게 한다.
- 실패(예상 밖 예외)는 작업 상태로 명확히 드러나야 하고, 조용히 삼켜지면 안 된다.
- 기존 영상 파이프라인과 스레드 풀을 다투지 않도록 분리한다.

## 범위 밖

- 앱(trova-app) 쪽 UI(폴링 로직, 진행률 표시 화면)는 이 스펙 범위 밖 — 별도 계획.
- `ProcessingJob`을 재구성 작업에 맞게 확장하는 것 — 검토 결과 영상 처리에
  강하게 결합돼 있어(`sourceUrl`/`sourcePlatform` 필수, `ProcessingStage`가
  파이프라인 단계에 하드코딩, `SavedPlace`가 `ProcessingJob`에 직접 FK) 재사용하지
  않고 새 엔티티(`TripReplanJob`)로 분리한다.
- 기존 동기 `TripReplanGraph.run(User, Trip, boolean)`의 내부 로직(그래프
  노드/사이클/백트래킹) 변경 — 그대로 재사용, 진행률 콜백 파라미터만 추가.
- 작업 자동 재시도 — 영상 파이프라인도 자동 재시도가 없다(실패하면 `errorMessage`만
  기록, `retryCount` 필드도 없음 — `ProcessingJob`의 `retryCount`는 있지만 실제로는
  단순 카운터일 뿐 자동 재시도 트리거가 아님). 이 스펙도 동일하게 자동 재시도 없음.

## 아키텍처

```
TripReplanController.POST /api/trips/{tripId}/replan
  - 소유권 확인(기존과 동일)
  - 같은 (user, trip, indoorOnly)로 PENDING/PROCESSING인 TripReplanJob이 있으면
    그 jobId를 재사용(신규 생성 안 함) — 영상 파이프라인의 중복 제출 방지 패턴과 동일
  - 없으면 TripReplanJob 저장(PENDING) → TripReplanJobService.process(jobId) 호출
    (@Async("replanTaskExecutor"))
  ← 즉시 202 + { jobId }

TripReplanJobService.process(Long jobId)  (@Async, 별도 스레드)
  - markProcessing(jobId)
  - Trip/User를 jobId로부터 다시 조회(엔티티를 스레드 경계 너머로 직접 넘기지 않음
    — 영상 파이프라인이 jobId만 넘기는 것과 같은 이유)
  - TripReplanGraph.run(user, trip, indoorOnly, onProgress) 호출
      onProgress(completed, total)이 호출될 때마다 TripReplanJob의
      completedTargets/totalTargets를 갱신+저장
  - 성공: ReplanOutcome을 JSON으로 직렬화해 markDone(jobId, resultJson)
  - 예외: markFailed(jobId, e.getMessage()) — 2000자로 truncate(기존 패턴과 동일)

TripReplanController.GET /api/trips/{tripId}/replan/{jobId}
  - 소유권 확인, 없으면 404
  - { status, completedTargets, totalTargets, result(DONE일 때만), errorMessage(FAILED일 때만) }
```

## 데이터 모델

새 테이블 `trip_replan_jobs`, 기존 `JobStatus` enum(PENDING/PROCESSING/DONE/FAILED)
재사용, `ProcessingJob`은 건드리지 않는다.

```java
@Entity
@Table(name = "trip_replan_jobs")
public class TripReplanJob {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(name = "indoor_only", nullable = false)
    private boolean indoorOnly;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Column(name = "completed_targets", nullable = false)
    private int completedTargets;

    // identify_targets가 끝나기 전까지는 총 타겟 수를 모르므로 null 허용.
    @Column(name = "total_targets")
    private Integer totalTargets;

    // ReplanOutcome(정확히는 TripReplanController.TripReplanResponse와 같은 모양)을
    // JSON으로 직렬화해 저장 — DONE일 때만 채워짐. 새 관계형 테이블을 만들지 않는
    // 이유: 재구성 결과는 영속 개념이 아니라 미리보기용 임시 데이터이고(확정은 항상
    // 별도 replacePlace 호출로 일어남), ProcessingJob↔SavedPlace처럼 결과 자체가
    // 독립된 라이프사이클을 갖는 엔티티가 아니다.
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // 생성자/마킹 메서드(markProcessing/updateProgress/markDone/markFailed)는
    // ProcessingJob과 동일한 패턴 — 각 메서드가 updatedAt도 같이 갱신.
}
```

**진행률 콜백** (신규, `com.trova.backend.replan` 패키지):

```java
public interface TripReplanProgressListener {
    void onProgress(int completed, int total);
}
```

`TripReplanGraph.run(...)`의 시그니처에 마지막 파라미터로 추가한다:
```java
public ReplanOutcome run(User user, Trip trip, boolean indoorOnly, TripReplanProgressListener onProgress)
```
그래프 구조는 바뀌지 않는다 — `route_next` 노드(현재 `state -> Map.of()`인 자리)가
진입할 때마다 `onProgress.onProgress(state.cursor(), state.targetIndexes().size())`를
호출하도록 한 줄만 추가한다. 동기 호출부(있다면, 혹은 테스트)를 위해
`onProgress`가 `null`이면 호출을 건너뛰는 널가드를 둔다.

## 엔드포인트

```
POST /api/trips/{tripId}/replan
  Request: { indoorOnly: boolean }  — 기존과 동일(v1은 indoorOnly=true만 허용, 400 규칙 동일)
  Response: 202 { jobId: number }

GET /api/trips/{tripId}/replan/{jobId}
  Response: {
    status: "PENDING" | "PROCESSING" | "DONE" | "FAILED",
    completedTargets: number,
    totalTargets: number | null,       // PENDING/PROCESSING 초반에는 null 가능
    result: TripReplanResponse | null, // DONE일 때만(기존 동기 응답과 동일한 모양)
    errorMessage: string | null        // FAILED일 때만
  }
```

기존 동기 `POST /api/trips/{tripId}/replan`(즉시 `TripReplanResponse` 반환)은
**삭제하고 위 비동기 버전으로 교체**한다 — 같은 URL을 재사용하되 응답 모양만
바뀌므로, 이 엔드포인트를 아직 아무 클라이언트도 안 쓰는 지금이 교체하기 가장 싼
시점이다.

## Executor 분리

`AsyncConfig`에 재구성 전용 executor를 새로 추가한다 — 기존 `pipelineTaskExecutor`
(core 2/max 4, 영상 처리용)와 스레드 풀을 공유하면, 재구성(최대 80초)과 영상 처리가
서로의 처리를 지연시킬 수 있다. 오라클 클라우드 무료 티어 서버의 제한된 리소스를
고려해 코어 풀을 작게 잡는다:

```java
@Bean("replanTaskExecutor")
public Executor replanTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(20);
    executor.setThreadNamePrefix("replan-");
    executor.initialize();
    return executor;
}
```

## 에러 처리

- **후보 조회 실패(구글 Places 등)**: `AlternativeFinderService`가 이미 빈 리스트로
  폴백하므로, 해당 타겟만 실패 목록에 들어갈 뿐 작업 전체는 DONE으로 정상 종료된다.
- **그래프 실행 중 예상 밖 예외**: `TripReplanJobService.process(jobId)`가 잡아서
  `markFailed(jobId, e.getMessage())` 호출, 2000자로 truncate 후 저장(영상 파이프라인
  `ProcessingJobLifecycleService.markFailed`와 완전히 동일한 패턴) — 조용히 삼키지
  않고 작업 상태로 명확히 드러낸다.
- **폴링 시 소유권 없는 작업 조회, 또는 존재하지 않는 jobId**: 404.
- **동시 중복 제출**: 같은 (user, trip, indoorOnly)로 PENDING/PROCESSING 중인 작업이
  있으면 새로 안 만들고 기존 jobId를 재반환.

## 테스트 전략

- `TripReplanGraphTest`: 진행률 콜백이 타겟 수만큼, 정확한 (completed, total)
  순서로 호출되는지 검증. `onProgress=null`이어도 예외 없이 동작하는지도 검증.
- `TripReplanJobServiceTest`(신규): `@Async` 처리 흐름 전체 — 성공 시
  `markDone`+`resultJson` 저장, 예외 시 `markFailed`+에러메시지 저장(2000자 truncate
  포함), 진행률 콜백이 실제로 `TripReplanJob` 엔티티에 반영되는지.
- `TripReplanControllerTest`: POST가 202+jobId 반환하는지, 같은 조건으로 중복
  제출 시 기존 jobId를 재사용하는지(신규 row 안 생기는지), GET 폴링이 상태별로
  올바른 응답 모양을 주는지(PROCESSING엔 result 없음, DONE엔 result 있음, FAILED엔
  errorMessage 있음), 소유권 검증(남의 tripId/jobId → 404).

## 이 스펙 이후

앱(trova-app) 쪽 UI(폴링 로직, 진행률 표시)는 이 스펙 범위 밖 — 별도 계획에서
다룬다.
