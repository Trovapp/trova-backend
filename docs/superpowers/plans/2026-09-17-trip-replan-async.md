# 전체 일정 재구성 — 비동기 처리 전환 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /api/trips/{tripId}/replan`을 즉시 202+jobId 응답으로 바꾸고, 실제 LangGraph4j 재구성 처리는 `@Async` 백그라운드 스레드에서 진행하며, 새 `GET /api/trips/{tripId}/replan/{jobId}`로 진행률/결과를 폴링할 수 있게 한다.

**Architecture:** 기존 영상 처리 파이프라인의 `ProcessingJob`/`ProcessingJobLifecycleService`/`@Async` 패턴을 그대로 재사용하되, 재구성 전용 새 엔티티 `TripReplanJob`으로 분리한다. `TripReplanGraph.run(...)`에 진행률 콜백 파라미터를 추가해 `route_next` 노드가 진입할 때마다 `(completed, total)`을 알리고, `TripReplanJobLifecycleService`가 그 값을 트랜잭션 안에서 엔티티에 반영한다. `TripReplanJobService.process(jobId)`가 `@Async("replanTaskExecutor")`로 이 전체 흐름을 오케스트레이션한다.

**Tech Stack:** Spring Boot(Java 21), Spring Data JPA(H2/Postgres), Jackson(jackson-databind, record 직렬화 기본 지원), LangGraph4j, JUnit5 + Mockito + AssertJ, MockMvc.

**Spec:** `docs/superpowers/specs/2026-09-17-trip-replan-async-design.md`

## Global Constraints

- 외부 API 호출이 포함된 처리는 반드시 비동기(`@Async`)로 — 이미 스펙이 반영, 이 계획은 그 배선만 한다.
- 유료 API를 기본 옵션으로 하드코딩하지 않는다(이 계획에는 해당 없음 — 새 외부 API 호출 없음).
- DB 마이그레이션 스크립트는 필요 없다 — `application.yml`의 `spring.jpa.hibernate.ddl-auto: update`가 새 엔티티의 테이블을 자동 생성한다(`src/main/resources/application.yml:17`, 테스트는 `create-drop`).
- 커밋 메시지는 `타입: 작업 내용` 형식만 사용(타입: feat/fix/docs/style/refactor/chore/perf/test). **커밋에 AI 관련 서명/트레일러(Generated with Claude Code, Co-Authored-By: Claude 등)를 절대 추가하지 않는다** — CLAUDE.md가 예외 없이 적용을 명시한 저장소 규칙이며, 이 계획을 실행하는 모든 태스크의 커밋에 적용된다.
- 커밋 전 `./gradlew build`가 통과해야 한다.
- 패키지 구조 관례를 따른다: 엔티티는 `entity`, 리포지토리는 `repository`, 오케스트레이션/트랜잭션 서비스는 `service`, 그래프/콜백 인터페이스는 `replan`, HTTP 계층은 `controller`.
- 기존 `TripReplanGraph`의 그래프 구조(노드/사이클/백트래킹 로직)는 변경하지 않는다 — 이 계획은 진행률 콜백 파라미터 추가와 `route_next` 노드 한 줄 추가만 다룬다.
- 기존 동기 `POST /api/trips/{tripId}/replan`(즉시 `TripReplanResponse` 반환)은 삭제하고 같은 URL을 202+jobId 응답으로 교체한다 — 별도 신규 엔드포인트를 추가하지 않는다.

---

### Task 1: 진행률 콜백 인터페이스 + TripReplanGraph 배선

**Files:**
- Create: `src/main/java/com/trova/backend/replan/TripReplanProgressListener.java`
- Modify: `src/main/java/com/trova/backend/replan/TripReplanGraph.java`
- Modify: `src/test/java/com/trova/backend/replan/TripReplanGraphTest.java`

**Interfaces:**
- Produces: `com.trova.backend.replan.TripReplanProgressListener` — `void onProgress(int completed, int total)`. Task 4가 `TripReplanJobService`에서 이 인터페이스의 람다 구현체를 만들어 `TripReplanGraph.run(...)`에 전달한다.
- Produces: `TripReplanGraph.run(User user, Trip trip, boolean indoorOnly, TripReplanProgressListener onProgress)` — 기존 3-인자 시그니처를 대체한다(하위호환 오버로드 없음). `onProgress`가 `null`이면 콜백을 건너뛴다.
- Consumes: 없음(이 태스크가 그래프의 최초 배선 지점).

- [ ] **Step 1: 진행률 콜백 인터페이스 작성**

```java
package com.trova.backend.replan;

public interface TripReplanProgressListener {
    void onProgress(int completed, int total);
}
```

- [ ] **Step 2: `TripReplanGraph`에 ThreadLocal 진행률 리스너 필드 추가**

`src/main/java/com/trova/backend/replan/TripReplanGraph.java`에서 기존 `currentUser` ThreadLocal 바로 아래에 추가한다(파일 86번째 줄 부근, `private final ThreadLocal<User> currentUser = new ThreadLocal<>();` 다음):

```java
    // TripReplanProgressListener(람다)도 User와 같은 이유로 상태 맵에 넣지 않고
    // ThreadLocal로 들고 다닌다 — 그래프가 매 노드 실행마다 상태를 자바 직렬화로
    // 클론하는데(cloneState), 람다 인스턴스는 Serializable을 보장하지 않는다.
    private final ThreadLocal<TripReplanProgressListener> currentProgress = new ThreadLocal<>();
```

- [ ] **Step 3: `run()` 시그니처에 `onProgress` 파라미터 추가, ThreadLocal 설정/해제**

`run(User user, Trip trip, boolean indoorOnly)`를 다음으로 교체한다(메서드 본문은 시그니처와 `currentUser.set`/`finally` 블록만 변경, 그 사이 로직은 그대로 유지):

```java
    public ReplanOutcome run(User user, Trip trip, boolean indoorOnly, TripReplanProgressListener onProgress) {
        long start = System.currentTimeMillis();
        List<Itinerary> itineraries = itineraryRepository.findByTripOrderByDay(trip);
        List<TripPlace> orderedPlaces = new ArrayList<>();
        for (Itinerary itinerary : itineraries) {
            orderedPlaces.addAll(tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary));
        }

        List<TripReplanState.PlaceSnapshot> snapshots = orderedPlaces.stream()
                .map(p -> new TripReplanState.PlaceSnapshot(
                        p.getId(), p.getLatitude(), p.getLongitude(), p.getSpace(), p.getItinerary().getId()))
                .toList();

        Map<String, Object> initial = new HashMap<>();
        initial.put(TripReplanState.INDOOR_ONLY_KEY, indoorOnly);
        initial.put(TripReplanState.PLACES_KEY, snapshots);
        initial.put(TripReplanState.CURSOR_KEY, 0);
        initial.put(TripReplanState.MATCHES_KEY, new ArrayList<TripReplanState.Match>());
        initial.put(TripReplanState.FAILED_KEY, new ArrayList<Long>());

        currentUser.set(user);
        currentProgress.set(onProgress);
        try {
            TripReplanState finalState = compiledGraph.invoke(initial)
                    .orElseThrow(() -> new IllegalStateException("일정 재구성 그래프가 결과를 반환하지 않았습니다"));

            Map<Long, String> nameByTripPlaceId = new HashMap<>();
            for (TripPlace p : orderedPlaces) {
                nameByTripPlaceId.put(p.getId(), p.getPlaceName());
            }

            List<ReplanMatch> matches = finalState.matches().stream()
                    .map(m -> new ReplanMatch(m.tripPlaceId(), nameByTripPlaceId.get(m.tripPlaceId()), m.candidate()))
                    .toList();

            apiCallLogService.record(
                    "internal", "trip-replan", null, System.currentTimeMillis() - start, true,
                    null, null, null, null);

            return new ReplanOutcome(matches, finalState.failed());
        } catch (RuntimeException e) {
            apiCallLogService.record(
                    "internal", "trip-replan", null, System.currentTimeMillis() - start, false,
                    e.getMessage(), null, null, null);
            throw e;
        } finally {
            currentUser.remove();
            currentProgress.remove();
        }
    }
```

- [ ] **Step 4: `route_next` 노드가 진입할 때마다 진행률을 알리도록 변경**

`buildGraph()`에서 `.addNode("route_next", node_async(state -> Map.of()))`를 다음으로 교체:

```java
                .addNode("route_next", node_async(this::onRouteNextEnter))
```

`routeNext(TripReplanState state)` 메서드 바로 위에 새 private 메서드를 추가한다:

```java
    private Map<String, Object> onRouteNextEnter(TripReplanState state) {
        TripReplanProgressListener listener = currentProgress.get();
        if (listener != null) {
            listener.onProgress(state.cursor(), state.targetIndexes().size());
        }
        return Map.of();
    }
```

- [ ] **Step 5: 빌드해서 컴파일 에러(기존 호출부) 확인**

Run: `./gradlew compileJava compileTestJava 2>&1 | grep -A3 "run(user, trip, true)"`
Expected: `src/test/java/com/trova/backend/replan/TripReplanGraphTest.java`의 12곳에서 "method run cannot be applied to given types" 컴파일 에러.

- [ ] **Step 6: 테스트 파일의 기존 12개 호출부를 4-인자로 일괄 치환**

`src/test/java/com/trova/backend/replan/TripReplanGraphTest.java`에서 리터럴 문자열 `graph.run(user, trip, true)`를 `graph.run(user, trip, true, null)`로 **전체 치환**(파일 내 정확히 12곳, 다른 문맥에서 부분 일치하지 않는 고유 문자열이므로 파일 전체에 안전하게 replace-all 가능).

- [ ] **Step 7: 진행률 콜백 테스트 2개 추가**

파일 마지막 테스트(`후보_좌표가_null이어도_충돌판정에서_NPE_없이_확정된다`) 뒤, 닫는 `}` 앞에 추가:

```java

    @Test
    void 진행률_콜백이_타겟_수만큼_정확한_completed_total로_호출된다() throws Exception {
        Itinerary day1 = itinerary(1);
        TripPlace target1 = tripPlace(day1, 1L, 37.50, 127.00, "OUTDOOR", 1);
        TripPlace target2 = tripPlace(day1, 2L, 37.60, 127.10, "OUTDOOR", 2);
        when(itineraryRepository.findByTripOrderByDay(trip)).thenReturn(List.of(day1));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(day1)).thenReturn(List.of(target1, target2));
        when(alternativeFinderService.findAlternatives(any(), any(), any()))
                .thenReturn(Optional.of(List.of()));

        List<int[]> calls = new java.util.ArrayList<>();
        TripReplanProgressListener listener = (completed, total) -> calls.add(new int[]{completed, total});

        graph.run(user, trip, true, listener);

        // route_next는 타겟마다 1회(진입 시 cursor) + 마지막 종료판정 1회 진입한다 —
        // 타겟 2개면 (0,2)->(1,2)->(2,2) 순서로 정확히 3번 호출돼야 한다.
        assertThat(calls).hasSize(3);
        assertThat(calls.get(0)).containsExactly(0, 2);
        assertThat(calls.get(1)).containsExactly(1, 2);
        assertThat(calls.get(2)).containsExactly(2, 2);
    }

    @Test
    void onProgress가_null이어도_예외없이_동작한다() throws Exception {
        Itinerary day1 = itinerary(1);
        TripPlace indoorPlace = tripPlace(day1, 1L, 37.50, 127.00, "INDOOR", 1);
        when(itineraryRepository.findByTripOrderByDay(trip)).thenReturn(List.of(day1));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(day1)).thenReturn(List.of(indoorPlace));

        TripReplanGraph.ReplanOutcome outcome = graph.run(user, trip, true, null);

        assertThat(outcome.matches()).isEmpty();
        assertThat(outcome.failedTripPlaceIds()).isEmpty();
    }
```

- [ ] **Step 8: 테스트 실행해서 전부 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.replan.TripReplanGraphTest"`
Expected: BUILD SUCCESSFUL, 14개 테스트 전부 통과(기존 12개 + 신규 2개).

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/trova/backend/replan/TripReplanProgressListener.java \
        src/main/java/com/trova/backend/replan/TripReplanGraph.java \
        src/test/java/com/trova/backend/replan/TripReplanGraphTest.java
git commit -m "feat: 일정 재구성 그래프에 진행률 콜백 배선"
```

---

### Task 2: TripReplanJob 엔티티 + 리포지토리

**Files:**
- Create: `src/main/java/com/trova/backend/entity/TripReplanJob.java`
- Create: `src/main/java/com/trova/backend/repository/TripReplanJobRepository.java`
- Create: `src/test/java/com/trova/backend/repository/TripReplanJobRepositoryTest.java`

**Interfaces:**
- Produces: `TripReplanJob(User user, Trip trip, boolean indoorOnly)` 생성자(PENDING 상태로 시작), `markProcessing()`, `updateProgress(int completed, int total)`, `markDone(String resultJson)`, `markFailed(String errorMessage)`, 및 각 필드의 getter(`getId`, `getUser`, `getTrip`, `isIndoorOnly`, `getStatus`, `getCompletedTargets`, `getTotalTargets`, `getResultJson`, `getErrorMessage`, `getCreatedAt`, `getUpdatedAt`). Task 3(lifecycle 서비스)이 이 엔티티의 마킹 메서드를 직접 호출한다.
- Produces: `TripReplanJobRepository.findByUserAndTripAndIndoorOnlyAndStatusIn(User user, Trip trip, boolean indoorOnly, List<JobStatus> statuses)`. Task 5(컨트롤러)가 중복 제출 방지에 사용한다.
- Consumes: 기존 `com.trova.backend.entity.JobStatus`(PENDING/PROCESSING/DONE/FAILED, 변경 없이 재사용), `com.trova.backend.entity.User`, `com.trova.backend.entity.Trip`.

- [ ] **Step 1: 리포지토리 테스트부터 작성(실패 확인용)**

```java
package com.trova.backend.repository;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripReplanJob;
import com.trova.backend.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class TripReplanJobRepositoryTest {

    @Autowired
    private TripReplanJobRepository tripReplanJobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TripRepository tripRepository;

    @Test
    void PENDING과_PROCESSING만_동일_조건으로_조회한다() {
        User user = userRepository.save(new User("google", "1", "테스트유저", null));
        Trip trip = tripRepository.save(new Trip(user, "테스트 여행", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)));

        TripReplanJob pending = tripReplanJobRepository.save(new TripReplanJob(user, trip, true));
        TripReplanJob done = tripReplanJobRepository.save(new TripReplanJob(user, trip, true));
        done.markProcessing();
        done.markDone("{}");
        tripReplanJobRepository.save(done);

        List<TripReplanJob> active = tripReplanJobRepository.findByUserAndTripAndIndoorOnlyAndStatusIn(
                user, trip, true, List.of(JobStatus.PENDING, JobStatus.PROCESSING));

        assertThat(active).extracting(TripReplanJob::getId).containsExactly(pending.getId());
    }

    @Test
    void indoorOnly값이_다르면_조회되지_않는다() {
        User user = userRepository.save(new User("google", "2", "테스트유저2", null));
        Trip trip = tripRepository.save(new Trip(user, "테스트 여행2", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)));
        tripReplanJobRepository.save(new TripReplanJob(user, trip, false));

        List<TripReplanJob> active = tripReplanJobRepository.findByUserAndTripAndIndoorOnlyAndStatusIn(
                user, trip, true, List.of(JobStatus.PENDING, JobStatus.PROCESSING));

        assertThat(active).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실행해서 컴파일 실패 확인**

Run: `./gradlew compileTestJava 2>&1 | tail -20`
Expected: `TripReplanJob`/`TripReplanJobRepository` 클래스를 찾을 수 없다는 컴파일 에러.

- [ ] **Step 3: `TripReplanJob` 엔티티 작성**

```java
package com.trova.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "trip_replan_jobs")
public class TripReplanJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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

    // ReplanOutcome을 JSON으로 직렬화해 저장 — DONE일 때만 채워짐. 재구성 결과는
    // 영속 개념이 아니라 미리보기용 임시 데이터라(확정은 항상 별도 replacePlace
    // 호출로 일어남) 별도 관계형 테이블을 만들지 않는다.
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected TripReplanJob() {
    }

    public TripReplanJob(User user, Trip trip, boolean indoorOnly) {
        this.user = user;
        this.trip = trip;
        this.indoorOnly = indoorOnly;
        this.status = JobStatus.PENDING;
        this.completedTargets = 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public void markProcessing() {
        this.status = JobStatus.PROCESSING;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateProgress(int completed, int total) {
        this.completedTargets = completed;
        this.totalTargets = total;
        this.updatedAt = LocalDateTime.now();
    }

    public void markDone(String resultJson) {
        this.status = JobStatus.DONE;
        this.resultJson = resultJson;
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailed(String errorMessage) {
        this.status = JobStatus.FAILED;
        this.errorMessage = errorMessage;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public Trip getTrip() { return trip; }
    public boolean isIndoorOnly() { return indoorOnly; }
    public JobStatus getStatus() { return status; }
    public int getCompletedTargets() { return completedTargets; }
    public Integer getTotalTargets() { return totalTargets; }
    public String getResultJson() { return resultJson; }
    public String getErrorMessage() { return errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 4: `TripReplanJobRepository` 작성**

```java
package com.trova.backend.repository;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripReplanJob;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripReplanJobRepository extends JpaRepository<TripReplanJob, Long> {
    List<TripReplanJob> findByUserAndTripAndIndoorOnlyAndStatusIn(
            User user, Trip trip, boolean indoorOnly, List<JobStatus> statuses);
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.repository.TripReplanJobRepositoryTest"`
Expected: BUILD SUCCESSFUL, 2개 테스트 통과.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/trova/backend/entity/TripReplanJob.java \
        src/main/java/com/trova/backend/repository/TripReplanJobRepository.java \
        src/test/java/com/trova/backend/repository/TripReplanJobRepositoryTest.java
git commit -m "feat: 일정 재구성 작업 엔티티/리포지토리 추가"
```

---

### Task 3: TripReplanJobLifecycleService

**Files:**
- Create: `src/main/java/com/trova/backend/service/TripReplanJobLifecycleService.java`
- Create: `src/test/java/com/trova/backend/service/TripReplanJobLifecycleServiceIntegrationTest.java`

**Interfaces:**
- Produces: `TripReplanJobLifecycleService.JobContext` — `record JobContext(User user, Trip trip, boolean indoorOnly)`, `TripReplanJobLifecycleService`의 public 정적 중첩 타입.
- Produces: `markProcessing(Long jobId): JobContext`(PROCESSING으로 바꾸고 그래프 실행에 필요한 값만 반환), `updateProgress(Long jobId, int completed, int total): void`, `markDone(Long jobId, String resultJson): void`, `markFailed(Long jobId, String rawMessage): void`(2000자 초과 시 뒤쪽 2000자로 truncate). Task 4가 이 4개 메서드만으로 `TripReplanJob` 엔티티에 직접 접근하지 않고 전체 흐름을 처리한다.
- Consumes: Task 2의 `TripReplanJobRepository`.

**Reference pattern:** `src/main/java/com/trova/backend/service/ProcessingJobLifecycleService.java`(트랜잭션 경계, truncate 로직)와 `src/test/java/com/trova/backend/service/ProcessingJobLifecycleServiceIntegrationTest.java`(클래스 레벨 `@Transactional` 없이 `@SpringBootTest`로 실제 커밋 검증)를 그대로 따른다.

- [ ] **Step 1: 통합 테스트부터 작성(실패 확인용)**

```java
package com.trova.backend.service;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripReplanJob;
import com.trova.backend.entity.User;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.TripReplanJobRepository;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 트랜잭션 경계를 실제로 검증해야 하므로 일부러 클래스 레벨 {@code @Transactional}을 붙이지 않는다.
 * (ProcessingJobLifecycleServiceIntegrationTest와 동일한 이유)
 */
@SpringBootTest
class TripReplanJobLifecycleServiceIntegrationTest {

    private static final String PROVIDER_USER_ID = "replan-lifecycle-1";

    @Autowired
    private TripReplanJobLifecycleService lifecycleService;

    @Autowired
    private TripReplanJobRepository tripReplanJobRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void tearDown() {
        userRepository.findByProviderAndProviderUserId("google", PROVIDER_USER_ID)
                .ifPresent(user -> {
                    tripReplanJobRepository.deleteAll(tripReplanJobRepository.findAll().stream()
                            .filter(j -> j.getUser().getId().equals(user.getId())).toList());
                    tripRepository.deleteAll(tripRepository.findByUserOrderByCreatedAtDesc(user));
                    userRepository.delete(user);
                });
    }

    private TripReplanJob newJob() {
        User user = userRepository.findByProviderAndProviderUserId("google", PROVIDER_USER_ID)
                .orElseGet(() -> userRepository.save(new User("google", PROVIDER_USER_ID, "재구성라이프사이클", null)));
        Trip trip = tripRepository.save(
                new Trip(user, "라이프사이클 여행", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)));
        return tripReplanJobRepository.save(new TripReplanJob(user, trip, true));
    }

    @Test
    void markProcessing_직후_다른_조회에서_PROCESSING과_컨텍스트가_보인다() {
        TripReplanJob job = newJob();

        TripReplanJobLifecycleService.JobContext context = lifecycleService.markProcessing(job.getId());

        assertThat(context.indoorOnly()).isTrue();
        assertThat(context.user().getId()).isEqualTo(job.getUser().getId());
        assertThat(context.trip().getId()).isEqualTo(job.getTrip().getId());

        TripReplanJob reloaded = tripReplanJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.PROCESSING);
    }

    @Test
    void updateProgress가_커밋된다() {
        TripReplanJob job = newJob();

        lifecycleService.updateProgress(job.getId(), 2, 5);

        TripReplanJob reloaded = tripReplanJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getCompletedTargets()).isEqualTo(2);
        assertThat(reloaded.getTotalTargets()).isEqualTo(5);
    }

    @Test
    void markDone이_결과JSON과_함께_커밋된다() {
        TripReplanJob job = newJob();

        lifecycleService.markDone(job.getId(), "{\"matches\":[]}");

        TripReplanJob reloaded = tripReplanJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(reloaded.getResultJson()).isEqualTo("{\"matches\":[]}");
    }

    @Test
    void 최대길이를_넘는_에러메시지는_뒤쪽_2000자로_잘려_저장된다() {
        TripReplanJob job = newJob();
        String message = "y".repeat(1000) + "x".repeat(2000);

        lifecycleService.markFailed(job.getId(), message);

        TripReplanJob reloaded = tripReplanJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(reloaded.getErrorMessage()).hasSize(2000).isEqualTo("x".repeat(2000));
    }
}
```

- [ ] **Step 2: 테스트 실행해서 컴파일 실패 확인**

Run: `./gradlew compileTestJava 2>&1 | tail -20`
Expected: `TripReplanJobLifecycleService` 클래스를 찾을 수 없다는 컴파일 에러.

- [ ] **Step 3: `TripReplanJobLifecycleService` 작성**

```java
package com.trova.backend.service;

import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripReplanJob;
import com.trova.backend.entity.User;
import com.trova.backend.repository.TripReplanJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripReplanJobLifecycleService {

    private static final int ERROR_MESSAGE_MAX_LENGTH = 2000;

    private final TripReplanJobRepository tripReplanJobRepository;

    public TripReplanJobLifecycleService(TripReplanJobRepository tripReplanJobRepository) {
        this.tripReplanJobRepository = tripReplanJobRepository;
    }

    public record JobContext(User user, Trip trip, boolean indoorOnly) {
    }

    @Transactional
    public JobContext markProcessing(Long jobId) {
        TripReplanJob job = getJob(jobId);
        job.markProcessing();
        return new JobContext(job.getUser(), job.getTrip(), job.isIndoorOnly());
    }

    @Transactional
    public void updateProgress(Long jobId, int completed, int total) {
        getJob(jobId).updateProgress(completed, total);
    }

    @Transactional
    public void markDone(Long jobId, String resultJson) {
        getJob(jobId).markDone(resultJson);
    }

    @Transactional
    public void markFailed(Long jobId, String rawMessage) {
        getJob(jobId).markFailed(truncate(rawMessage));
    }

    private TripReplanJob getJob(Long jobId) {
        return tripReplanJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("TripReplanJob을 찾을 수 없습니다: " + jobId));
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > ERROR_MESSAGE_MAX_LENGTH
                ? message.substring(message.length() - ERROR_MESSAGE_MAX_LENGTH)
                : message;
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.service.TripReplanJobLifecycleServiceIntegrationTest"`
Expected: BUILD SUCCESSFUL, 4개 테스트 통과.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/trova/backend/service/TripReplanJobLifecycleService.java \
        src/test/java/com/trova/backend/service/TripReplanJobLifecycleServiceIntegrationTest.java
git commit -m "feat: 일정 재구성 작업 lifecycle 서비스 추가"
```

---

### Task 4: TripReplanJobService(@Async 오케스트레이터) + executor 분리

**Files:**
- Modify: `src/main/java/com/trova/backend/config/AsyncConfig.java`
- Create: `src/main/java/com/trova/backend/service/TripReplanJobService.java`
- Create: `src/test/java/com/trova/backend/service/TripReplanJobServiceTest.java`

**Interfaces:**
- Produces: `@Bean("replanTaskExecutor")` — Task 5가 참조하지는 않지만(빈 이름은 `TripReplanJobService` 내부에서만 참조), 영상 파이프라인의 `pipelineTaskExecutor`와 스레드 풀을 분리하기 위해 필요하다.
- Produces: `TripReplanJobService.process(Long jobId): void` — `@Async("replanTaskExecutor")`. Task 5(컨트롤러)가 이 메서드를 호출해 백그라운드 처리를 시작시킨다.
- Consumes: Task 1의 `TripReplanGraph.run(User, Trip, boolean, TripReplanProgressListener)`, Task 3의 `TripReplanJobLifecycleService`(`markProcessing`/`updateProgress`/`markDone`/`markFailed`).

**Reference pattern:** `src/main/java/com/trova/backend/service/PlaceExtractionService.java`의 `process(Long jobId)` — lifecycle 서비스에서 필요한 값만 꺼내 쓰고, `catch (Exception e)`로 실패를 잡아 `markFailed`로 넘기는 구조를 그대로 따른다.

- [ ] **Step 1: `AsyncConfig`에 재구성 전용 executor 추가**

`src/main/java/com/trova/backend/config/AsyncConfig.java`의 기존 `pipelineTaskExecutor()` 빈 뒤(닫는 클래스 `}` 앞)에 추가:

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

- [ ] **Step 2: `TripReplanJobServiceTest` 작성(실패 확인용)**

```java
package com.trova.backend.service;

import com.trova.backend.entity.Trip;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.replan.TripReplanGraph;
import com.trova.backend.replan.TripReplanProgressListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripReplanJobServiceTest {

    @Mock private TripReplanJobLifecycleService lifecycleService;
    @Mock private TripReplanGraph tripReplanGraph;

    @InjectMocks
    private TripReplanJobService tripReplanJobService;

    private void setId(Object entity, Long id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private TripReplanJobLifecycleService.JobContext newContext(Long userId, Long tripId, boolean indoorOnly) throws Exception {
        User user = new User("google", "u" + userId, "테스트유저", null);
        setId(user, userId);
        Trip trip = new Trip(user, "테스트 여행", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1));
        setId(trip, tripId);
        return new TripReplanJobLifecycleService.JobContext(user, trip, indoorOnly);
    }

    @Test
    void 성공하면_결과가_JSON으로_직렬화되어_markDone에_전달된다() throws Exception {
        TripReplanJobLifecycleService.JobContext context = newContext(1L, 10L, true);
        when(lifecycleService.markProcessing(5L)).thenReturn(context);

        AlternativeCandidate candidate = new AlternativeCandidate(
                99L, "g-99", "실내카페", "cafe", 4.7, 200, 37.501, 127.001, "서울",
                null, null, false, null, null);
        TripReplanGraph.ReplanMatch match = new TripReplanGraph.ReplanMatch(1L, "야외공원", candidate);
        TripReplanGraph.ReplanOutcome outcome = new TripReplanGraph.ReplanOutcome(List.of(match), List.of());
        when(tripReplanGraph.run(eq(context.user()), eq(context.trip()), eq(true), any()))
                .thenReturn(outcome);

        tripReplanJobService.process(5L);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(lifecycleService).markDone(eq(5L), jsonCaptor.capture());
        assertThat(jsonCaptor.getValue()).contains("\"tripPlaceId\":1").contains("\"placeId\":99");
    }

    @Test
    void 실패하면_예외메시지로_markFailed가_호출된다() {
        when(lifecycleService.markProcessing(6L))
                .thenThrow(new IllegalStateException("TripReplanJob을 찾을 수 없습니다: 6"));

        tripReplanJobService.process(6L);

        verify(lifecycleService).markFailed(6L, "TripReplanJob을 찾을 수 없습니다: 6");
    }

    @Test
    void 진행률_콜백이_updateProgress로_전달된다() throws Exception {
        TripReplanJobLifecycleService.JobContext context = newContext(2L, 11L, true);
        when(lifecycleService.markProcessing(7L)).thenReturn(context);

        ArgumentCaptor<TripReplanProgressListener> listenerCaptor =
                ArgumentCaptor.forClass(TripReplanProgressListener.class);
        when(tripReplanGraph.run(eq(context.user()), eq(context.trip()), eq(true), listenerCaptor.capture()))
                .thenReturn(new TripReplanGraph.ReplanOutcome(List.of(), List.of()));

        tripReplanJobService.process(7L);
        listenerCaptor.getValue().onProgress(2, 5);

        verify(lifecycleService).updateProgress(7L, 2, 5);
    }
}
```

- [ ] **Step 3: 테스트 실행해서 컴파일 실패 확인**

Run: `./gradlew compileTestJava 2>&1 | tail -20`
Expected: `TripReplanJobService` 클래스를 찾을 수 없다는 컴파일 에러.

- [ ] **Step 4: `TripReplanJobService` 작성**

```java
package com.trova.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.replan.TripReplanGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class TripReplanJobService {

    private static final Logger log = LoggerFactory.getLogger(TripReplanJobService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TripReplanJobLifecycleService lifecycleService;
    private final TripReplanGraph tripReplanGraph;

    public TripReplanJobService(
            TripReplanJobLifecycleService lifecycleService,
            TripReplanGraph tripReplanGraph
    ) {
        this.lifecycleService = lifecycleService;
        this.tripReplanGraph = tripReplanGraph;
    }

    @Async("replanTaskExecutor")
    public void process(Long jobId) {
        try {
            TripReplanJobLifecycleService.JobContext context = lifecycleService.markProcessing(jobId);
            log.info("TripReplanJob {} 재구성 시작: tripId={}", jobId, context.trip().getId());

            TripReplanGraph.ReplanOutcome outcome = tripReplanGraph.run(
                    context.user(), context.trip(), context.indoorOnly(),
                    (completed, total) -> lifecycleService.updateProgress(jobId, completed, total));

            String resultJson = MAPPER.writeValueAsString(outcome);
            lifecycleService.markDone(jobId, resultJson);
            log.info("TripReplanJob {} DONE", jobId);
        } catch (Exception e) {
            log.error("TripReplanJob {} 처리 실패", jobId, e);
            lifecycleService.markFailed(jobId, e.getMessage());
        }
    }
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.service.TripReplanJobServiceTest"`
Expected: BUILD SUCCESSFUL, 3개 테스트 통과.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/trova/backend/config/AsyncConfig.java \
        src/main/java/com/trova/backend/service/TripReplanJobService.java \
        src/test/java/com/trova/backend/service/TripReplanJobServiceTest.java
git commit -m "feat: 일정 재구성 비동기 오케스트레이터와 전용 executor 추가"
```

---

### Task 5: TripReplanController 비동기 전환

**Files:**
- Modify: `src/main/java/com/trova/backend/controller/TripReplanController.java`
- Modify: `src/test/java/com/trova/backend/controller/TripReplanControllerTest.java`

**Interfaces:**
- Produces: `POST /api/trips/{tripId}/replan` → 202 `{ jobId }`(신규 생성 또는 기존 PENDING/PROCESSING 작업 재사용), `GET /api/trips/{tripId}/replan/{jobId}` → 200 `{ status, completedTargets, totalTargets, result, errorMessage }` 또는 404.
- Consumes: Task 2의 `TripReplanJobRepository`, Task 4의 `TripReplanJobService.process(Long)`, Task 1의 `TripReplanGraph.ReplanOutcome`(JSON 역직렬화 대상).

- [ ] **Step 1: 기존 컨트롤러 테스트를 비동기 버전으로 전체 교체(실패 확인용)**

`src/test/java/com/trova/backend/controller/TripReplanControllerTest.java` 전체를 다음으로 교체한다:

```java
package com.trova.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripReplanJob;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.replan.TripReplanGraph;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.TripReplanJobRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.service.TripReplanJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TripReplanControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TripRepository tripRepository;
    @Autowired private ItineraryRepository itineraryRepository;
    @Autowired private TripReplanJobRepository tripReplanJobRepository;

    // 실제 그래프 실행(TripReplanGraph)은 TripReplanGraphTest가, JSON 왕복은
    // TripReplanJobServiceTest가 이미 검증했다. 여기서는 컨트롤러의 인증/소유권/
    // 중복 제출 방지/상태별 응답 변환만 검증하므로 비동기 오케스트레이터 자체를
    // 목으로 대체해 실제 백그라운드 처리가 일어나지 않게 한다.
    @MockitoBean private TripReplanJobService tripReplanJobService;

    private final ObjectMapper mapper = new ObjectMapper();

    private User me;
    private Trip trip;

    @BeforeEach
    void setUp() {
        me = userRepository.save(new User("google", "replan1", "재구성유저", null));
        trip = tripRepository.save(new Trip(me, "테스트 여행", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)));
        doNothing().when(tripReplanJobService).process(anyLong());
    }

    private RequestPostProcessor loginAs(String sub, String name) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("google")
                .clientId("test-client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .build();
        return oauth2Login()
                .clientRegistration(registration)
                .attributes(attrs -> {
                    attrs.put("sub", sub);
                    attrs.put("name", name);
                    attrs.put("picture", "https://example.com/p.jpg");
                });
    }

    @Test
    void indoorOnly가_없으면_400() throws Exception {
        mockMvc.perform(post("/api/trips/" + trip.getId() + "/replan")
                        .with(loginAs("replan1", "재구성유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 남의_여행이면_POST에서_404() throws Exception {
        User other = userRepository.save(new User("google", "other", "다른유저", null));
        Trip otherTrip = tripRepository.save(new Trip(other, "다른 여행", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1)));

        mockMvc.perform(post("/api/trips/" + otherTrip.getId() + "/replan")
                        .with(loginAs("replan1", "재구성유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"indoorOnly\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 정상_요청이면_202와_jobId를_반환하고_비동기_처리를_시작한다() throws Exception {
        mockMvc.perform(post("/api/trips/" + trip.getId() + "/replan")
                        .with(loginAs("replan1", "재구성유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"indoorOnly\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").exists());

        List<TripReplanJob> jobs = tripReplanJobRepository.findAll();
        org.assertj.core.api.Assertions.assertThat(jobs).hasSize(1);
        verify(tripReplanJobService).process(jobs.get(0).getId());
    }

    @Test
    void 같은_조건으로_중복_제출하면_기존_jobId를_재사용한다() throws Exception {
        TripReplanJob existing = tripReplanJobRepository.save(new TripReplanJob(me, trip, true));

        mockMvc.perform(post("/api/trips/" + trip.getId() + "/replan")
                        .with(loginAs("replan1", "재구성유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"indoorOnly\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(existing.getId()));

        org.assertj.core.api.Assertions.assertThat(tripReplanJobRepository.findAll()).hasSize(1);
        verify(tripReplanJobService, never()).process(anyLong());
    }

    @Test
    void 남의_작업을_폴링하면_404() throws Exception {
        User other = userRepository.save(new User("google", "other2", "다른유저2", null));
        Trip otherTrip = tripRepository.save(new Trip(other, "다른 여행2", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1)));
        TripReplanJob otherJob = tripReplanJobRepository.save(new TripReplanJob(other, otherTrip, true));

        mockMvc.perform(get("/api/trips/" + otherTrip.getId() + "/replan/" + otherJob.getId())
                        .with(loginAs("replan1", "재구성유저")))
                .andExpect(status().isNotFound());
    }

    @Test
    void PROCESSING_상태를_폴링하면_결과없이_진행률만_담긴다() throws Exception {
        TripReplanJob job = tripReplanJobRepository.save(new TripReplanJob(me, trip, true));
        job.markProcessing();
        job.updateProgress(2, 5);
        tripReplanJobRepository.save(job);

        mockMvc.perform(get("/api/trips/" + trip.getId() + "/replan/" + job.getId())
                        .with(loginAs("replan1", "재구성유저")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.completedTargets").value(2))
                .andExpect(jsonPath("$.totalTargets").value(5))
                .andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(jsonPath("$.errorMessage").doesNotExist());
    }

    @Test
    void DONE_상태를_폴링하면_결과가_담긴다() throws Exception {
        Itinerary itinerary = itineraryRepository.save(new Itinerary(trip, 1, LocalDate.of(2026, 1, 1)));
        AlternativeCandidate candidate = new AlternativeCandidate(
                99L, "g-99", "실내카페", "cafe", 4.7, 200, 37.501, 127.001, "서울",
                null, null, false, null, null);
        TripReplanGraph.ReplanMatch match = new TripReplanGraph.ReplanMatch(1L, "야외공원", candidate);
        TripReplanGraph.ReplanOutcome outcome = new TripReplanGraph.ReplanOutcome(List.of(match), List.of(2L));
        String resultJson = mapper.writeValueAsString(outcome);

        TripReplanJob job = tripReplanJobRepository.save(new TripReplanJob(me, trip, true));
        job.markProcessing();
        job.markDone(resultJson);
        tripReplanJobRepository.save(job);

        mockMvc.perform(get("/api/trips/" + trip.getId() + "/replan/" + job.getId())
                        .with(loginAs("replan1", "재구성유저")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.result.replaced[0].tripPlaceId").value(1))
                .andExpect(jsonPath("$.result.replaced[0].originalName").value("야외공원"))
                .andExpect(jsonPath("$.result.replaced[0].candidate.placeId").value(99))
                .andExpect(jsonPath("$.result.failedTripPlaceIds[0]").value(2))
                .andExpect(jsonPath("$.errorMessage").doesNotExist());
    }

    @Test
    void FAILED_상태를_폴링하면_에러메시지가_담긴다() throws Exception {
        TripReplanJob job = tripReplanJobRepository.save(new TripReplanJob(me, trip, true));
        job.markProcessing();
        job.markFailed("그래프 실행 중 예외 발생");
        tripReplanJobRepository.save(job);

        mockMvc.perform(get("/api/trips/" + trip.getId() + "/replan/" + job.getId())
                        .with(loginAs("replan1", "재구성유저")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorMessage").value("그래프 실행 중 예외 발생"))
                .andExpect(jsonPath("$.result").doesNotExist());
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인(컨트롤러가 아직 옛 동기 응답을 반환)**

Run: `./gradlew test --tests "com.trova.backend.controller.TripReplanControllerTest"`
Expected: 컴파일 에러(옛 `TripReplanController`가 `TripReplanJobRepository`/`TripReplanJobService`를 모르고, GET 엔드포인트 자체가 없음) 또는 테스트 실패.

- [ ] **Step 3: `TripReplanController` 전체 교체**

```java
package com.trova.backend.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripReplanJob;
import com.trova.backend.entity.User;
import com.trova.backend.replan.TripReplanGraph;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.TripReplanJobRepository;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.TripReplanJobService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TripReplanController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CurrentUserService currentUserService;
    private final TripRepository tripRepository;
    private final TripReplanJobRepository tripReplanJobRepository;
    private final TripReplanJobService tripReplanJobService;

    public TripReplanController(
            CurrentUserService currentUserService,
            TripRepository tripRepository,
            TripReplanJobRepository tripReplanJobRepository,
            TripReplanJobService tripReplanJobService
    ) {
        this.currentUserService = currentUserService;
        this.tripRepository = tripRepository;
        this.tripReplanJobRepository = tripReplanJobRepository;
        this.tripReplanJobService = tripReplanJobService;
    }

    public record TripReplanRequest(Boolean indoorOnly) {
    }

    public record ReplanResultResponse(
            Long tripPlaceId, String originalName, TripController.AlternativeCandidateResponse candidate
    ) {
        static ReplanResultResponse from(TripReplanGraph.ReplanMatch match) {
            return new ReplanResultResponse(
                    match.tripPlaceId(), match.originalName(),
                    TripController.AlternativeCandidateResponse.from(match.candidate()));
        }
    }

    public record TripReplanResponse(List<ReplanResultResponse> replaced, List<Long> failedTripPlaceIds) {
        static TripReplanResponse from(TripReplanGraph.ReplanOutcome outcome) {
            return new TripReplanResponse(
                    outcome.matches().stream().map(ReplanResultResponse::from).toList(),
                    outcome.failedTripPlaceIds());
        }
    }

    public record CreateReplanJobResponse(Long jobId) {
    }

    public record ReplanJobStatusResponse(
            String status, int completedTargets, Integer totalTargets,
            TripReplanResponse result, String errorMessage
    ) {
    }

    @PostMapping("/api/trips/{tripId}/replan")
    public ResponseEntity<?> replan(
            Authentication authentication, @PathVariable Long tripId, @RequestBody TripReplanRequest request
    ) {
        User user = currentUserService.resolve(authentication);
        // v1은 "실내 위주로 바꾸기" 한 방향만 지원한다 — indoorOnly가 없거나
        // false면 재구성할 조건 자체가 없으므로 400.
        if (request.indoorOnly() == null || !request.indoorOnly()) {
            return ResponseEntity.badRequest().build();
        }
        return tripRepository.findById(tripId)
                .filter(t -> t.getUser().getId().equals(user.getId()))
                .map(trip -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(new CreateReplanJobResponse(resolveJobId(user, trip))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/trips/{tripId}/replan/{jobId}")
    public ResponseEntity<?> status(
            Authentication authentication, @PathVariable Long tripId, @PathVariable Long jobId
    ) {
        User user = currentUserService.resolve(authentication);
        return tripReplanJobRepository.findById(jobId)
                .filter(job -> job.getUser().getId().equals(user.getId()) && job.getTrip().getId().equals(tripId))
                .map(job -> ResponseEntity.ok(toStatusResponse(job)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // 같은 (user, trip, indoorOnly)로 PENDING/PROCESSING인 작업이 있으면 그 jobId를
    // 재사용한다 — 영상 파이프라인(SharesController)의 중복 제출 방지와 동일한 패턴.
    private Long resolveJobId(User user, Trip trip) {
        List<TripReplanJob> inFlight = tripReplanJobRepository.findByUserAndTripAndIndoorOnlyAndStatusIn(
                user, trip, true, List.of(JobStatus.PENDING, JobStatus.PROCESSING));
        if (!inFlight.isEmpty()) {
            return inFlight.get(0).getId();
        }
        TripReplanJob job = tripReplanJobRepository.save(new TripReplanJob(user, trip, true));
        tripReplanJobService.process(job.getId());
        return job.getId();
    }

    private ReplanJobStatusResponse toStatusResponse(TripReplanJob job) {
        TripReplanResponse result = null;
        if (job.getStatus() == JobStatus.DONE && job.getResultJson() != null) {
            try {
                TripReplanGraph.ReplanOutcome outcome =
                        MAPPER.readValue(job.getResultJson(), TripReplanGraph.ReplanOutcome.class);
                result = TripReplanResponse.from(outcome);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("저장된 재구성 결과를 읽을 수 없습니다: jobId=" + job.getId(), e);
            }
        }
        return new ReplanJobStatusResponse(
                job.getStatus().name(), job.getCompletedTargets(), job.getTotalTargets(), result, job.getErrorMessage());
    }
}
```

- [ ] **Step 4: 테스트 실행해서 전부 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.controller.TripReplanControllerTest"`
Expected: BUILD SUCCESSFUL, 8개 테스트 전부 통과.

- [ ] **Step 5: 전체 빌드로 회귀 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, 기존 341개 + 이 계획에서 추가한 테스트(약 20개) 전부 통과.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/trova/backend/controller/TripReplanController.java \
        src/test/java/com/trova/backend/controller/TripReplanControllerTest.java
git commit -m "feat: 일정 재구성 API를 비동기 202+폴링 방식으로 전환"
```

---

## 이 계획 이후

- 벤치마크 문서(`docs/benchmarks/2026-09-16-trip-replan-latency.md`)가 남긴 게이트("비동기 전환 전까지는 앱 UI 연결도, 실제 배포 노출도 하지 말 것")가 이 계획 완료로 해소된다.
- 앱(trova-app) 쪽 UI(폴링 로직, 진행률 표시 화면)는 이 계획 범위 밖 — 별도 계획에서 다룬다.
- Gemini 무료 티어 RPM 버스트 한도(타겟 하나당 태깅+임베딩+설명생성 호출이 몰리는 문제, 스펙 문서가 남긴 미해결 관찰)는 이 계획에서 다루지 않았다 — 실제 운영 트래픽에서 429가 관측되면 별도로 검토한다.
