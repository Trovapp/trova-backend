# 전체 일정 재구성 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 여행 전체(여러 날짜/장소)를 훑어서 조건(v1: 실내 위주)에 안 맞는 장소들을
LangGraph4j 기반 그래프로 순회하며 재구성 후보를 찾고, 이웃 장소와 이동시간 충돌이
있으면 다음 순위 후보로 백트래킹한다.

**Architecture:** 새 패키지 `com.trova.backend.replan`. 그래프는 기존
`AlternativeFinderService`를 노드 안에서 그대로 호출해 후보를 구한다(장소당 정확히
1회). 그래프 상태(`TripReplanState`)는 JPA 엔티티와 완전히 분리된 순수 데이터라,
리포지토리/트랜잭션 없이 Mockito만으로 그래프 로직(특히 백트래킹)을 검증할 수 있다.
`TripReplanGraph.run(User, Trip, boolean)`이 리포지토리 조회 → 그래프 실행 → 결과
변환까지의 오케스트레이션 경계이고, `TripReplanController`가 이를 호출한다.

**Tech Stack:** Spring Boot(Java 21), `org.bsc.langgraph4j:langgraph4j-core:1.8.13`
(신규 도입 — LangChain4j/Spring AI 불필요, 기존 `AlternativeFinderService` 그대로
재사용).

**Spec:** [docs/superpowers/specs/2026-09-16-trip-replan-design.md](../specs/2026-09-16-trip-replan-design.md)

## Global Constraints

- v1은 `indoorOnly=true` 조건만 지원한다 — 요청에 `indoorOnly`가 없거나 `false`면
  400을 반환한다(스펙의 "v1: 실내/실외" 절을 "실내 위주로 바꾸기" 한 방향으로 좁힌
  구현 결정 — "다 실외로 바꾸기"는 원래 요청에 없던 대칭 기능이라 범위 밖).
- 처리 대상 장소(타겟) 수 상한: 최대 10개(비용 상한, 스펙 "비용 관점" 절).
- 후보 재시도 상한: 타겟당 최대 3개 후보까지만 시도(스펙과 동일).
- 충돌 판정 기준: 이웃 장소까지 도보(4km/h 가정, `AlternativeFinderService`의
  `AVERAGE_SPEED_KMH`의 WALK 속도와 동일 값) 예상 이동시간이 30분 초과.
- 후보 조회(`AlternativeFinderService.findAlternatives`)는 타겟당 정확히 1회만
  호출한다 — 백트래킹은 이미 받아온 후보 목록 안에서만 이루어진다(API 재호출 없음).
  이 계획의 모든 태스크가 이 불변조건을 테스트로 검증해야 한다.
- 이 기능은 절대 자동으로 일정을 바꾸지 않는다 — 확정은 기존
  `replacePlace`(`POST /api/trip-places/{id}/replace`)를 사용자가 직접 눌러야
  일어난다. 이 계획에서 새 확정 API를 만들지 않는다.
- `space`(실내외 태그)가 `null`인 장소는 재구성 대상에서 제외한다(온디맨드 태깅은
  범위 밖).
- **Ruling(스펙 대비 범위 축소, 근거 명시)**: 스펙의 "그래프 실행 중 예상 밖
  예외" 절은 부분 결과를 보존하라고 했으나, 실제로 따져보면 이 그래프가 부르는
  유일한 외부 실패 지점(`AlternativeFinderService.findAlternatives`)은 이미 그
  서비스 내부에서 실패를 예외로 던지지 않고 빈 리스트로 폴백하도록 구현돼 있다
  (`AlternativeFinderService.java`의 구글 Places 검색 `catch` 블록, 기존 코드,
  변경 없음). 따라서 `TripReplanGraph`의 노드에서 예외가 나는 경우는 "예상되는
  외부 API 실패"가 아니라 "코드 버그"뿐이다. 이 프로젝트는 이런 진짜 버그 상황에
  대해 전역 `@ControllerAdvice` 없이 Spring 기본 500 응답으로 흘려보내는 것을
  이미 다른 컨트롤러들(예: `NotificationController`, `ConversationController`)의
  실제 관례로 삼고 있다 — 외부 서비스의 "예상되는" 실패만 절대 500이 되면 안
  된다는 원칙이지, 모든 예외를 여기서 특별히 잡아 부분 결과로 감싸라는 뜻이
  아니다. 이 계획은 `TripReplanGraph`/`TripReplanController`에 예외를 잡아
  부분 결과로 바꾸는 별도 로직을 추가하지 않는다 — 만들면 테스트되지 않는 복잡성만
  늘어난다(YAGNI). 틀렸을 때 비용: 실제로 코드 버그가 나면 그 요청은 500으로
  실패하고 재구성 결과를 하나도 못 받는다 — 이미 확정된 다른 장소들의 교체는
  전혀 실행되지 않았으므로(확정은 항상 사용자가 별도로 `replacePlace`를 눌러야
  일어남) 데이터가 망가지는 위험은 없다.
- 커밋 메시지는 EXACTLY `타입: 내용` 형식만 사용한다 — AI 서명/트레일러/이모지
  절대 금지(CLAUDE.md, 이 세션 내내 예외 없이 지켜온 규칙).
- 커밋 전 `./gradlew build`(전체 빌드) 실행 필수.
- `TripReplanController`는 `com.trova.backend.controller` 패키지에 둔다(기존
  `TripController`/`ConversationController`와 같은 위치 — `ConversationController`가
  도메인 로직은 `conversation` 패키지, 컨트롤러 자체는 `controller` 패키지에 둔
  전례와 동일). 이 위치 덕분에 `TripController`의 기존 `AlternativeCandidateResponse`
  (package-private `from(...)`)를 새로 만들지 않고 그대로 재사용할 수 있다 —
  이전 "대화형 비서" 최종 리뷰에서 지적된 카드 DTO 중복 문제를 이번엔 처음부터
  피한다.

---

## LangGraph4j API — 이 계획에서 실제로 쓰는 부분(실제 소스 확인 완료, 추측 아님)

아래는 `langgraph4j-core:1.8.13`의 실제 소스(`github.com/langgraph4j/langgraph4j`)를
직접 읽어서 확인한 시그니처다 — 온라인 튜토리얼 요약이 실제와 다른 부분이 있어서
(예: `Channels.lastValue()`는 실제로 존재하지 않음, `addConditionalEdges`는 라우팅
함수만이 아니라 `Map<String,String>` 매핑도 반드시 필요함), 소스 코드로 직접
검증했다.

- **State**: `TripReplanState extends AgentState`. `AgentState(Map<String,Object> initData)`
  생성자 필수. 값 읽기는 `this.<T>value(key).orElse(default)`.
- **Schema/Channel**: `Map<String, Channel<?>> SCHEMA`. 리스트를 "누적"하고 싶은
  키만 `Channels.appender(ArrayList::new)`를 채널로 지정한다 — 채널을 지정 안 한
  키는 **자동으로 덮어쓰기**된다(별도의 "lastValue" 채널 같은 건 없음, 그냥
  스키마에서 빼면 됨).
- **Node**: `NodeAction<S>` — `Map<String,Object> apply(S state) throws Exception`.
  `AsyncNodeAction.node_async(NodeAction)`로 감싸서 등록.
- **Edge(조건부)**: `EdgeAction<S>` — `String apply(S state) throws Exception`(다음
  분기 이름을 문자열로 반환). `AsyncEdgeAction.edge_async(EdgeAction)`로 감싸고,
  `addConditionalEdges(sourceId, edge_async(...), Map.of("라우팅키", "다음노드id"))`처럼
  **라우팅 함수가 반환한 문자열 → 실제 노드 id**의 매핑 맵을 반드시 같이 넘긴다.
- **Appender 채널에 단일 값 반환**: 채널이 `appender`인 키에 대해 노드가 리스트가
  아니라 **단일 객체**를 반환해도(`Map.of(MATCHES_KEY, oneMatch)`) 자동으로
  `List.of(oneMatch)`로 감싸져서 기존 리스트에 append된다(`AppenderChannel.update`
  소스 확인) — 매번 전체 리스트를 다시 만들어 반환할 필요 없다.
- **StateGraph 생성**: `new StateGraph<>(SCHEMA, initData -> new TripReplanState(initData))`.
- **START/END**: `import static org.bsc.langgraph4j.StateGraph.START;` /
  `...END;` (실제로는 `GraphDefinition` 인터페이스에 정의돼 있고
  `StateGraph`가 그 인터페이스를 구현해서 상속받음).
- **컴파일/실행**: `stateGraph.compile()` → `CompiledGraph<State>`(체크
  예외 `GraphStateException` 던짐). `compiledGraph.invoke(Map<String,Object> inputs)`
  → **`Optional<State>`를 동기로 즉시 반환**(`CompletableFuture`가 아님 — 실제
  `CompiledGraph.java` 소스로 확인).

---

### Task 1: `GeoUtils` — 이동시간 추정 유틸(신규, 공용)

**Files:**
- Create: `build.gradle` (수정)
- Create: `src/main/java/com/trova/backend/replan/GeoUtils.java`
- Test: `src/test/java/com/trova/backend/replan/GeoUtilsTest.java`

**Interfaces:**
- Produces: `GeoUtils.haversineKm(double lat1, double lng1, double lat2, double lng2)`
  → `double`, `GeoUtils.estimatedWalkMinutes(double lat1, double lng1, double lat2, double lng2)`
  → `int`

- [ ] **Step 1: `build.gradle`에 LangGraph4j 의존성 추가**

`build.gradle`의 `dependencies` 블록에 한 줄 추가:

```groovy
dependencies {
	implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
	implementation 'org.springframework.boot:spring-boot-starter-security'
	implementation 'org.springframework.boot:spring-boot-starter-security-oauth2-client'
	implementation 'org.springframework.boot:spring-boot-starter-webmvc'
	implementation 'org.springframework.boot:spring-boot-starter-actuator'
	implementation 'io.micrometer:micrometer-registry-prometheus'
	implementation 'com.fasterxml.jackson.core:jackson-databind'
	implementation 'org.bsc.langgraph4j:langgraph4j-core:1.8.13'
	runtimeOnly 'org.postgresql:postgresql'
	implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
	runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.6'
	runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.6'
	testImplementation 'org.springframework.boot:spring-boot-starter-data-jpa-test'
	testImplementation 'org.springframework.boot:spring-boot-starter-security-oauth2-client-test'
	testImplementation 'org.springframework.boot:spring-boot-starter-security-test'
	testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
	testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
	testRuntimeOnly 'com.h2database:h2'
}
```

- [ ] **Step 2: 의존성이 실제로 받아지는지 확인**

Run: `./gradlew dependencies --configuration compileClasspath | grep langgraph4j`
Expected: `org.bsc.langgraph4j:langgraph4j-core:1.8.13`가 목록에 나타남(추측 없이
실제로 resolve되는지 먼저 확인 — 이 프로젝트의 "측정하지 추측하지 않는다" 원칙).

- [ ] **Step 3: 실패 테스트부터 작성**

`src/test/java/com/trova/backend/replan/GeoUtilsTest.java`:

```java
package com.trova.backend.replan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoUtilsTest {

    @Test
    void 같은_좌표는_거리가_0이다() {
        double km = GeoUtils.haversineKm(37.5665, 126.9780, 37.5665, 126.9780);
        assertThat(km).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void 경복궁과_서울역_거리는_약_2_3km다() {
        // 실측 검증 가능한 유명 랜드마크 좌표 — 경복궁(37.5796, 126.9770),
        // 서울역(37.5547, 126.9707). 직선거리 약 2.9km.
        double km = GeoUtils.haversineKm(37.5796, 126.9770, 37.5547, 126.9707);
        assertThat(km).isBetween(2.5, 3.3);
    }

    @Test
    void 도보_30분_거리는_2km_안팎이다() {
        // 4km/h 가정이므로 30분 = 정확히 2.0km 지점이 임계값.
        int minutesAt2km = GeoUtils.estimatedWalkMinutes(37.5665, 126.9780, 37.5665, 126.9960);
        // 위 좌표쌍은 경도 차이만으로 약 1.6km — 정확한 임계값 테스트보다,
        // 함수가 haversineKm/4*60 계산을 정확히 수행하는지를 직접 검증한다.
        double km = GeoUtils.haversineKm(37.5665, 126.9780, 37.5665, 126.9960);
        int expectedMinutes = (int) Math.round(km / 4.0 * 60);
        assertThat(minutesAt2km).isEqualTo(expectedMinutes);
    }
}
```

- [ ] **Step 4: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.replan.GeoUtilsTest"`
Expected: FAIL — `GeoUtils` 클래스가 없음(컴파일 실패).

- [ ] **Step 5: 구현**

`src/main/java/com/trova/backend/replan/GeoUtils.java`:

```java
package com.trova.backend.replan;

/**
 * 일정 재구성의 이동시간 충돌 판정에 쓰는 순수 지리 계산 유틸. 새 후보와 이웃
 * 장소 사이의 이동시간을 추정한다.
 */
public final class GeoUtils {

    private static final double EARTH_RADIUS_KM = 6371.0;
    // 재구성 요청은 이동 수단을 받지 않는다(v1은 indoorOnly만 지원) — 가장 보수적인
    // (느린) 도보 속도를 기본값으로 써서, 실제보다 이동시간을 더 길게 잡아 충돌을
    // 과소가 아닌 과대평가하는 쪽으로 안전하게 치우친다. AlternativeFinderService의
    // AVERAGE_SPEED_KMH의 WALK 값(4.0)과 동일하게 맞췄다.
    private static final double WALK_SPEED_KMH = 4.0;

    private GeoUtils() {
    }

    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    public static int estimatedWalkMinutes(double lat1, double lng1, double lat2, double lng2) {
        double km = haversineKm(lat1, lng1, lat2, lng2);
        return (int) Math.round(km / WALK_SPEED_KMH * 60);
    }
}
```

- [ ] **Step 6: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.replan.GeoUtilsTest"`
Expected: PASS — 3개 테스트 전부 통과.

- [ ] **Step 7: 전체 빌드로 의존성 통합 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — 새 의존성이 기존 빌드를 깨지 않음을 확인.

- [ ] **Step 8: 커밋**

```bash
git add build.gradle src/main/java/com/trova/backend/replan/GeoUtils.java \
        src/test/java/com/trova/backend/replan/GeoUtilsTest.java
git commit -m "feat: LangGraph4j 의존성 추가 및 이동시간 추정 유틸 추가"
```

---

### Task 2: `TripReplanState` — 그래프 상태 정의

**Files:**
- Create: `src/main/java/com/trova/backend/replan/TripReplanState.java`
- Test: `src/test/java/com/trova/backend/replan/TripReplanStateTest.java`

**Interfaces:**
- Consumes: `com.trova.backend.recommendation.AlternativeCandidate`(기존, 필드 14개
  레코드)
- Produces:
  - `TripReplanState.PlaceSnapshot(Long tripPlaceId, Double latitude, Double longitude, String space)`
  - `TripReplanState.Match(Long tripPlaceId, AlternativeCandidate candidate)`
  - 상수: `INDOOR_ONLY_KEY`, `PLACES_KEY`, `TARGET_INDEXES_KEY`, `CURSOR_KEY`,
    `CANDIDATES_KEY`, `CANDIDATE_TRY_KEY`, `MATCHES_KEY`, `FAILED_KEY`, `ROUTE_KEY`
    (전부 `String`)
  - `TripReplanState.SCHEMA` — `Map<String, Channel<?>>`
  - 읽기 메서드: `indoorOnly()`, `places()`, `targetIndexes()`, `cursor()`,
    `candidates()`, `candidateTry()`, `matches()`, `failed()`

- [ ] **Step 1: 실패 테스트부터 작성**

`src/test/java/com/trova/backend/replan/TripReplanStateTest.java`:

```java
package com.trova.backend.replan;

import com.trova.backend.recommendation.AlternativeCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TripReplanStateTest {

    private AlternativeCandidate candidate(Long placeId) {
        return new AlternativeCandidate(placeId, "g-" + placeId, "장소" + placeId, "cafe", 4.5, 100,
                37.5, 127.0, "서울", null, null, false, null, null);
    }

    @Test
    void 값이_없으면_기본값을_반환한다() {
        TripReplanState state = new TripReplanState(Map.of());

        assertThat(state.indoorOnly()).isFalse();
        assertThat(state.places()).isEmpty();
        assertThat(state.targetIndexes()).isEmpty();
        assertThat(state.cursor()).isZero();
        assertThat(state.candidates()).isEmpty();
        assertThat(state.candidateTry()).isZero();
        assertThat(state.matches()).isEmpty();
        assertThat(state.failed()).isEmpty();
    }

    @Test
    void initData로_넘긴_값을_그대로_읽는다() {
        var snapshot = new TripReplanState.PlaceSnapshot(1L, 37.5, 127.0, "INDOOR");
        TripReplanState state = new TripReplanState(Map.of(
                TripReplanState.INDOOR_ONLY_KEY, true,
                TripReplanState.PLACES_KEY, List.of(snapshot),
                TripReplanState.CURSOR_KEY, 2
        ));

        assertThat(state.indoorOnly()).isTrue();
        assertThat(state.places()).containsExactly(snapshot);
        assertThat(state.cursor()).isEqualTo(2);
    }

    @Test
    void MATCHES_KEY와_FAILED_KEY는_appender_채널로_단일값을_누적한다() {
        // AgentState.updateState는 스키마의 채널을 통해 partial state를 병합한다 —
        // 그래프 노드가 실제로 이렇게(단일 값 반환) 호출할 상황을 직접 재현해서
        // appender 채널이 기대대로 누적되는지 검증한다(라이브러리 동작을 신뢰만
        // 하지 않고 실제로 확인).
        Map<String, Object> initial = Map.of(TripReplanState.MATCHES_KEY, List.of());
        var match1 = new TripReplanState.Match(1L, candidate(1L));
        var match2 = new TripReplanState.Match(2L, candidate(2L));

        Map<String, Object> afterFirst = org.bsc.langgraph4j.state.AgentState.updateState(
                initial, Map.of(TripReplanState.MATCHES_KEY, match1), TripReplanState.SCHEMA);
        Map<String, Object> afterSecond = org.bsc.langgraph4j.state.AgentState.updateState(
                afterFirst, Map.of(TripReplanState.MATCHES_KEY, match2), TripReplanState.SCHEMA);

        TripReplanState finalState = new TripReplanState(afterSecond);
        assertThat(finalState.matches()).containsExactly(match1, match2);
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.replan.TripReplanStateTest"`
Expected: FAIL — `TripReplanState` 클래스가 없음.

- [ ] **Step 3: 구현**

`src/main/java/com/trova/backend/replan/TripReplanState.java`:

```java
package com.trova.backend.replan;

import com.trova.backend.recommendation.AlternativeCandidate;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "전체 일정 재구성" 그래프가 실행되는 동안 들고 다니는 상태. PLACES_KEY 안의
 * PlaceSnapshot은 JPA 엔티티와 완전히 분리된 순수 데이터라, 그래프 로직(특히
 * 백트래킹)을 리포지토리/트랜잭션 없이 Mockito만으로 테스트할 수 있다.
 */
public class TripReplanState extends AgentState {

    public static final String INDOOR_ONLY_KEY = "indoorOnly";
    public static final String PLACES_KEY = "places";
    public static final String TARGET_INDEXES_KEY = "targetIndexes";
    public static final String CURSOR_KEY = "cursor";
    public static final String CANDIDATES_KEY = "candidates";
    public static final String CANDIDATE_TRY_KEY = "candidateTry";
    public static final String MATCHES_KEY = "matches";
    public static final String FAILED_KEY = "failed";
    public static final String ROUTE_KEY = "route";

    // MATCHES_KEY/FAILED_KEY만 누적(appender) — 나머지는 채널을 지정하지 않아
    // 매번 덮어쓰기된다(예: fetch_candidates가 매 타겟마다 CANDIDATES_KEY를
    // 통째로 교체).
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            MATCHES_KEY, Channels.appender(ArrayList::new),
            FAILED_KEY, Channels.appender(ArrayList::new)
    );

    /** day+visitOrder 순으로 정렬된 여행 내 장소 하나의 좌표/실내외 스냅샷. */
    public record PlaceSnapshot(Long tripPlaceId, Double latitude, Double longitude, String space) {
    }

    /** 그래프가 확정한 대안 — tripPlaceId와 후보만 담는다(이름은 그래프 경계 밖에서 채움). */
    public record Match(Long tripPlaceId, AlternativeCandidate candidate) {
    }

    public TripReplanState(Map<String, Object> initData) {
        super(initData);
    }

    public boolean indoorOnly() {
        return this.<Boolean>value(INDOOR_ONLY_KEY).orElse(false);
    }

    public List<PlaceSnapshot> places() {
        return this.<List<PlaceSnapshot>>value(PLACES_KEY).orElse(List.of());
    }

    public List<Integer> targetIndexes() {
        return this.<List<Integer>>value(TARGET_INDEXES_KEY).orElse(List.of());
    }

    public int cursor() {
        return this.<Integer>value(CURSOR_KEY).orElse(0);
    }

    public List<AlternativeCandidate> candidates() {
        return this.<List<AlternativeCandidate>>value(CANDIDATES_KEY).orElse(List.of());
    }

    public int candidateTry() {
        return this.<Integer>value(CANDIDATE_TRY_KEY).orElse(0);
    }

    public List<Match> matches() {
        return this.<List<Match>>value(MATCHES_KEY).orElse(List.of());
    }

    public List<Long> failed() {
        return this.<List<Long>>value(FAILED_KEY).orElse(List.of());
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.replan.TripReplanStateTest"`
Expected: PASS — 3개 테스트 전부 통과. (특히 3번째 테스트가 appender 채널의 실제
동작을 라이브러리 코드로 직접 검증함 — 이 계획의 나머지 태스크가 이 동작에
의존하므로 추측이 아니라 실측으로 여기서 고정해둔다.)

- [ ] **Step 5: 전체 빌드**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/trova/backend/replan/TripReplanState.java \
        src/test/java/com/trova/backend/replan/TripReplanStateTest.java
git commit -m "feat: 일정 재구성 그래프 상태(TripReplanState) 추가"
```

---

### Task 3: `TripReplanGraph` — 그래프 정의 + 실행 경계

**Files:**
- Create: `src/main/java/com/trova/backend/replan/TripReplanGraph.java`
- Test: `src/test/java/com/trova/backend/replan/TripReplanGraphTest.java`

**Interfaces:**
- Consumes: `TripReplanState`(Task 2), `GeoUtils`(Task 1),
  `AlternativeFinderService.findAlternatives(User, Long, AlternativeFilter)` →
  `Optional<List<AlternativeCandidate>>`(기존, 변경 없음),
  `AlternativeFilter(String category, Boolean indoorOnly, Double maxDistanceKm, Integer maxTravelMinutes, TransportMode transportMode)`(기존),
  `TripPlaceRepository.findByItineraryOrderByVisitOrder(Itinerary)`(기존),
  `ItineraryRepository.findByTripOrderByDay(Trip)`(기존), `TripPlace` 엔티티(기존
  — `getId()`, `getLatitude()`, `getLongitude()`, `getSpace()`, `getPlaceName()`
  게터 사용)
- Produces:
  - `TripReplanGraph.ReplanMatch(Long tripPlaceId, String originalName, AlternativeCandidate candidate)`
  - `TripReplanGraph.ReplanOutcome(List<ReplanMatch> matches, List<Long> failedTripPlaceIds)`
  - `TripReplanGraph.run(User user, Trip trip, boolean indoorOnly)` → `ReplanOutcome`

- [ ] **Step 1: 실패 테스트부터 작성**

`src/test/java/com/trova/backend/replan/TripReplanGraphTest.java`:

```java
package com.trova.backend.replan;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.recommendation.AlternativeFilter;
import com.trova.backend.recommendation.AlternativeFinderService;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TripReplanGraphTest {

    @Mock private AlternativeFinderService alternativeFinderService;
    @Mock private ItineraryRepository itineraryRepository;
    @Mock private TripPlaceRepository tripPlaceRepository;

    private TripReplanGraph graph;
    private User user;
    private Trip trip;

    @BeforeEach
    void setUp() throws Exception {
        graph = new TripReplanGraph(alternativeFinderService, itineraryRepository, tripPlaceRepository);
        user = new User("google", "u1", "테스트유저", null);
        setId(user, 1L);
        trip = new Trip(user, "테스트 여행", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1));
        setId(trip, 10L);
    }

    private void setId(Object entity, Long id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private Itinerary itinerary(int day) throws Exception {
        Itinerary itinerary = new Itinerary(trip, day, LocalDate.of(2026, 1, day));
        setId(itinerary, (long) (100 + day));
        return itinerary;
    }

    private TripPlace tripPlace(Itinerary itinerary, Long id, double lat, double lng, String space, int visitOrder) throws Exception {
        TripPlace place = new TripPlace(itinerary, "장소" + id, "서울", "cafe", lat, lng, null, null, visitOrder, PlaceSource.NORMAL, null);
        place.applySpace(space);
        setId(place, id);
        return place;
    }

    private AlternativeCandidate candidate(Long placeId, double lat, double lng) {
        return new AlternativeCandidate(placeId, "g-" + placeId, "대안" + placeId, "cafe", 4.5, 100,
                lat, lng, "서울", null, null, false, null, null);
    }

    @Test
    void 후보가_이웃과_충돌하면_다음_순위_후보로_백트래킹한다() throws Exception {
        Itinerary day1 = itinerary(1);
        // 이웃(장소1, 37.50/127.00, INDOOR) - 타겟(장소2, 37.50/127.00, OUTDOOR) 순서.
        // 타겟 하나뿐이라 이웃은 장소1(이전)만 있고 다음은 없음.
        TripPlace neighbor = tripPlace(day1, 1L, 37.50, 127.00, "INDOOR", 1);
        TripPlace target = tripPlace(day1, 2L, 37.50, 127.00, "OUTDOOR", 2);
        when(itineraryRepository.findByTripOrderByDay(trip)).thenReturn(List.of(day1));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(day1)).thenReturn(List.of(neighbor, target));

        // 1등 후보는 이웃과 40분 거리(도보 4km/h 기준 약 2.67km, 위도 1도≈111km로
        // 환산해 위도차 0.024도로 역산) — 충돌(30분 초과).
        // 2등 후보는 이웃과 약 10분 거리(약 0.67km, 위도차 0.006도) — 충돌 없음.
        AlternativeCandidate farCandidate = candidate(20L, 37.5240, 127.00);
        AlternativeCandidate nearCandidate = candidate(21L, 37.5060, 127.00);
        when(alternativeFinderService.findAlternatives(eq(user), eq(2L), any(AlternativeFilter.class)))
                .thenReturn(Optional.of(List.of(farCandidate, nearCandidate)));

        TripReplanGraph.ReplanOutcome outcome = graph.run(user, trip, true);

        assertThat(outcome.matches()).hasSize(1);
        assertThat(outcome.matches().get(0).tripPlaceId()).isEqualTo(2L);
        assertThat(outcome.matches().get(0).candidate().placeId()).isEqualTo(21L);
        assertThat(outcome.failedTripPlaceIds()).isEmpty();
        // 백트래킹 중에도 후보 조회는 타겟당 정확히 1회만 — 재시도할 때 API를 또
        // 부르지 않는다는 비용 설계를 고정한다.
        verify(alternativeFinderService, times(1)).findAlternatives(eq(user), eq(2L), any());
    }

    @Test
    void 모든_후보가_충돌하면_실패목록에_들어가고_원래장소는_그대로다() throws Exception {
        Itinerary day1 = itinerary(1);
        TripPlace neighbor = tripPlace(day1, 1L, 37.50, 127.00, "INDOOR", 1);
        TripPlace target = tripPlace(day1, 2L, 37.50, 127.00, "OUTDOOR", 2);
        when(itineraryRepository.findByTripOrderByDay(trip)).thenReturn(List.of(day1));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(day1)).thenReturn(List.of(neighbor, target));

        // 3개 후보 전부 이웃과 40분 이상 거리.
        List<AlternativeCandidate> allFar = List.of(
                candidate(20L, 37.5240, 127.00),
                candidate(21L, 37.5240, 127.01),
                candidate(22L, 37.5240, 127.02));
        when(alternativeFinderService.findAlternatives(eq(user), eq(2L), any(AlternativeFilter.class)))
                .thenReturn(Optional.of(allFar));

        TripReplanGraph.ReplanOutcome outcome = graph.run(user, trip, true);

        assertThat(outcome.matches()).isEmpty();
        assertThat(outcome.failedTripPlaceIds()).containsExactly(2L);
        verify(alternativeFinderService, times(1)).findAlternatives(eq(user), eq(2L), any());
    }

    @Test
    void 한_타겟이_실패해도_나머지_타겟은_계속_처리된다() throws Exception {
        Itinerary day1 = itinerary(1);
        TripPlace target1 = tripPlace(day1, 2L, 37.50, 127.00, "OUTDOOR", 1);
        TripPlace target2 = tripPlace(day1, 3L, 37.60, 127.10, "OUTDOOR", 2);
        when(itineraryRepository.findByTripOrderByDay(trip)).thenReturn(List.of(day1));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(day1)).thenReturn(List.of(target1, target2));

        when(alternativeFinderService.findAlternatives(eq(user), eq(2L), any(AlternativeFilter.class)))
                .thenReturn(Optional.of(List.of())); // target1: 후보 자체가 없음(즉시 실패, 좌표는 원래 그대로 남음)
        // target2의 유일한 이웃은 target1(실패해서 원래 좌표 37.50/127.00 그대로) —
        // 그 근처(37.501/127.001, 약 150m)로 후보를 잡아 충돌 없이 확정되게 한다.
        when(alternativeFinderService.findAlternatives(eq(user), eq(3L), any(AlternativeFilter.class)))
                .thenReturn(Optional.of(List.of(candidate(30L, 37.501, 127.001))));

        TripReplanGraph.ReplanOutcome outcome = graph.run(user, trip, true);

        assertThat(outcome.failedTripPlaceIds()).containsExactly(2L);
        assertThat(outcome.matches()).hasSize(1);
        assertThat(outcome.matches().get(0).tripPlaceId()).isEqualTo(3L);
    }

    @Test
    void 실내인_장소는_indoorOnly_요청에서_타겟이_되지_않는다() throws Exception {
        Itinerary day1 = itinerary(1);
        TripPlace indoorPlace = tripPlace(day1, 1L, 37.50, 127.00, "INDOOR", 1);
        when(itineraryRepository.findByTripOrderByDay(trip)).thenReturn(List.of(day1));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(day1)).thenReturn(List.of(indoorPlace));

        TripReplanGraph.ReplanOutcome outcome = graph.run(user, trip, true);

        assertThat(outcome.matches()).isEmpty();
        assertThat(outcome.failedTripPlaceIds()).isEmpty();
        verifyNoInteractions(alternativeFinderService);
    }

    @Test
    void space가_null인_장소는_보수적으로_제외된다() throws Exception {
        Itinerary day1 = itinerary(1);
        TripPlace untagged = tripPlace(day1, 1L, 37.50, 127.00, null, 1);
        when(itineraryRepository.findByTripOrderByDay(trip)).thenReturn(List.of(day1));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(day1)).thenReturn(List.of(untagged));

        TripReplanGraph.ReplanOutcome outcome = graph.run(user, trip, true);

        assertThat(outcome.matches()).isEmpty();
        assertThat(outcome.failedTripPlaceIds()).isEmpty();
        verifyNoInteractions(alternativeFinderService);
    }

    @Test
    void 타겟이_10개_넘으면_최대_10개만_처리한다() throws Exception {
        Itinerary day1 = itinerary(1);
        List<TripPlace> places = new java.util.ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            places.add(tripPlace(day1, (long) i, 37.50 + i * 0.001, 127.00, "OUTDOOR", i));
        }
        when(itineraryRepository.findByTripOrderByDay(trip)).thenReturn(List.of(day1));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(day1)).thenReturn(places);
        when(alternativeFinderService.findAlternatives(any(), any(), any()))
                .thenReturn(Optional.of(List.of()));

        TripReplanGraph.ReplanOutcome outcome = graph.run(user, trip, true);

        assertThat(outcome.matches().size() + outcome.failedTripPlaceIds().size()).isEqualTo(10);
        verify(alternativeFinderService, times(10)).findAlternatives(any(), any(), any());
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.replan.TripReplanGraphTest"`
Expected: FAIL — `TripReplanGraph` 클래스가 없음.

**주의**: `TripPlace` 생성자 시그니처와 `applySpace` 메서드명이 실제
`TripPlace.java`와 일치하는지 먼저 확인할 것 — 이전 태스크(대화형 비서)에서 계획
문서의 테스트 코드가 실제 엔티티 생성자와 미묘하게 달라 구현자가 수정해야 했던
사례가 있었다(예: `Place`의 no-arg 생성자가 protected). 다르면 실제 생성자에 맞게
고쳐서 진행한다(프로덕션 코드는 그대로 두고 테스트만 수정).

- [ ] **Step 3: 구현**

`src/main/java/com/trova/backend/replan/TripReplanGraph.java`:

```java
package com.trova.backend.replan;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.recommendation.AlternativeFilter;
import com.trova.backend.recommendation.AlternativeFinderService;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * "전체 일정 재구성" — 여행의 여러 장소를 순회하며 조건(v1: 실내 위주)에 안 맞는
 * 장소마다 대안을 찾고, 이웃 장소와 이동시간 충돌이 있으면 다음 순위 후보로
 * 백트래킹한다(LangGraph4j 사이클). 새 추천 로직은 없다 — 후보 조회는 기존
 * AlternativeFinderService를 타겟당 정확히 1회만 호출한다.
 *
 * 그래프: identify_targets -> route_next
 *   route_next --(더 처리할 타겟 있음)--> fetch_candidates
 *   route_next --(없음)--> END
 *   fetch_candidates --(후보 있음)--> check_conflict
 *   fetch_candidates --(후보 없음: 즉시 실패)--> route_next
 *   check_conflict --(충돌, 다음 후보 있음)--> check_conflict(사이클)
 *   check_conflict --(확정 또는 후보 소진)--> route_next
 */
@Component
public class TripReplanGraph {

    private static final int MAX_TARGETS = 10;
    private static final int MAX_CANDIDATE_TRIES = 3;
    private static final int CONFLICT_THRESHOLD_MINUTES = 30;

    private final AlternativeFinderService alternativeFinderService;
    private final ItineraryRepository itineraryRepository;
    private final TripPlaceRepository tripPlaceRepository;
    private final CompiledGraph<TripReplanState> compiledGraph;

    public TripReplanGraph(
            AlternativeFinderService alternativeFinderService,
            ItineraryRepository itineraryRepository,
            TripPlaceRepository tripPlaceRepository
    ) {
        this.alternativeFinderService = alternativeFinderService;
        this.itineraryRepository = itineraryRepository;
        this.tripPlaceRepository = tripPlaceRepository;
        try {
            this.compiledGraph = buildGraph().compile();
        } catch (GraphStateException e) {
            throw new IllegalStateException("TripReplanGraph 그래프 구성 실패", e);
        }
    }

    public record ReplanMatch(Long tripPlaceId, String originalName, AlternativeCandidate candidate) {
    }

    public record ReplanOutcome(List<ReplanMatch> matches, List<Long> failedTripPlaceIds) {
    }

    public ReplanOutcome run(User user, Trip trip, boolean indoorOnly) {
        List<Itinerary> itineraries = itineraryRepository.findByTripOrderByDay(trip);
        List<TripPlace> orderedPlaces = new ArrayList<>();
        for (Itinerary itinerary : itineraries) {
            orderedPlaces.addAll(tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary));
        }

        List<TripReplanState.PlaceSnapshot> snapshots = orderedPlaces.stream()
                .map(p -> new TripReplanState.PlaceSnapshot(p.getId(), p.getLatitude(), p.getLongitude(), p.getSpace()))
                .toList();

        Map<String, Object> initial = new HashMap<>();
        initial.put(TripReplanState.INDOOR_ONLY_KEY, indoorOnly);
        initial.put(TripReplanState.PLACES_KEY, snapshots);
        initial.put(TripReplanState.CURSOR_KEY, 0);
        // MATCHES_KEY/FAILED_KEY는 appender 채널의 기본값(빈 리스트)에 기대지 않고
        // 여기서 명시적으로 빈 리스트로 시작한다 — 타겟이 하나도 없어 두 키가 한 번도
        // 갱신되지 않는 경우에도 finalState.matches()/failed()가 항상 안전하게
        // 빈 리스트를 반환하게 한다.
        initial.put(TripReplanState.MATCHES_KEY, new ArrayList<TripReplanState.Match>());
        initial.put(TripReplanState.FAILED_KEY, new ArrayList<Long>());
        initial.put("__user__", user);

        TripReplanState finalState = compiledGraph.invoke(initial)
                .orElseThrow(() -> new IllegalStateException("일정 재구성 그래프가 결과를 반환하지 않았습니다"));

        Map<Long, String> nameByTripPlaceId = new HashMap<>();
        for (TripPlace p : orderedPlaces) {
            nameByTripPlaceId.put(p.getId(), p.getPlaceName());
        }

        List<ReplanMatch> matches = finalState.matches().stream()
                .map(m -> new ReplanMatch(m.tripPlaceId(), nameByTripPlaceId.get(m.tripPlaceId()), m.candidate()))
                .toList();

        return new ReplanOutcome(matches, finalState.failed());
    }

    private StateGraph<TripReplanState> buildGraph() throws GraphStateException {
        return new StateGraph<>(TripReplanState.SCHEMA, initData -> new TripReplanState(initData))
                .addNode("identify_targets", node_async(this::identifyTargets))
                .addNode("route_next", node_async(state -> Map.of()))
                .addNode("fetch_candidates", node_async(this::fetchCandidates))
                .addNode("check_conflict", node_async(this::checkConflict))
                .addEdge(START, "identify_targets")
                .addEdge("identify_targets", "route_next")
                .addConditionalEdges("route_next", edge_async(this::routeNext),
                        Map.of("has_more", "fetch_candidates", "done", END))
                .addConditionalEdges("fetch_candidates", edge_async(state -> state.candidates().isEmpty() ? "empty" : "found"),
                        Map.of("found", "check_conflict", "empty", "route_next"))
                .addConditionalEdges("check_conflict", edge_async(this::routeConflict),
                        Map.of("retry", "check_conflict", "resolved", "route_next"));
    }

    // --- 노드 구현 ---

    private Map<String, Object> identifyTargets(TripReplanState state) {
        boolean indoorOnly = state.indoorOnly();
        List<TripReplanState.PlaceSnapshot> places = state.places();
        List<Integer> targetIndexes = new ArrayList<>();
        for (int i = 0; i < places.size() && targetIndexes.size() < MAX_TARGETS; i++) {
            TripReplanState.PlaceSnapshot p = places.get(i);
            if (p.space() == null || p.latitude() == null || p.longitude() == null) {
                continue;
            }
            boolean isIndoor = "INDOOR".equals(p.space());
            if (indoorOnly && !isIndoor) {
                targetIndexes.add(i);
            }
        }
        return Map.of(TripReplanState.TARGET_INDEXES_KEY, targetIndexes);
    }

    private String routeNext(TripReplanState state) {
        return state.cursor() < state.targetIndexes().size() ? "has_more" : "done";
    }

    private Map<String, Object> fetchCandidates(TripReplanState state) {
        int placeIndex = state.targetIndexes().get(state.cursor());
        TripReplanState.PlaceSnapshot target = state.places().get(placeIndex);
        User user = (User) state.data().get("__user__");

        AlternativeFilter filter = new AlternativeFilter(null, true, null, null, null);
        List<AlternativeCandidate> candidates = alternativeFinderService
                .findAlternatives(user, target.tripPlaceId(), filter)
                .orElse(List.of());

        if (candidates.isEmpty()) {
            return Map.of(
                    TripReplanState.CANDIDATES_KEY, List.<AlternativeCandidate>of(),
                    TripReplanState.CANDIDATE_TRY_KEY, 0,
                    TripReplanState.FAILED_KEY, target.tripPlaceId(),
                    TripReplanState.CURSOR_KEY, state.cursor() + 1
            );
        }
        return Map.of(
                TripReplanState.CANDIDATES_KEY, candidates,
                TripReplanState.CANDIDATE_TRY_KEY, 0
        );
    }

    private String routeConflict(TripReplanState state) {
        return "retry".equals(state.<String>value(TripReplanState.ROUTE_KEY).orElse("")) ? "retry" : "resolved";
    }

    private Map<String, Object> checkConflict(TripReplanState state) {
        int placeIndex = state.targetIndexes().get(state.cursor());
        List<TripReplanState.PlaceSnapshot> places = state.places();
        TripReplanState.PlaceSnapshot originalTarget = places.get(placeIndex);
        List<AlternativeCandidate> candidates = state.candidates();
        int tryIndex = state.candidateTry();
        AlternativeCandidate candidate = candidates.get(tryIndex);

        TripReplanState.PlaceSnapshot prev = placeIndex > 0 ? places.get(placeIndex - 1) : null;
        TripReplanState.PlaceSnapshot next = placeIndex < places.size() - 1 ? places.get(placeIndex + 1) : null;

        boolean conflict = exceedsThreshold(prev, candidate) || exceedsThreshold(next, candidate);

        if (conflict && tryIndex + 1 < candidates.size() && tryIndex + 1 < MAX_CANDIDATE_TRIES) {
            return Map.of(
                    TripReplanState.CANDIDATE_TRY_KEY, tryIndex + 1,
                    TripReplanState.ROUTE_KEY, "retry"
            );
        }

        if (conflict) {
            return Map.of(
                    TripReplanState.FAILED_KEY, originalTarget.tripPlaceId(),
                    TripReplanState.CURSOR_KEY, state.cursor() + 1,
                    TripReplanState.ROUTE_KEY, "resolved"
            );
        }

        List<TripReplanState.PlaceSnapshot> updatedPlaces = new ArrayList<>(places);
        updatedPlaces.set(placeIndex, new TripReplanState.PlaceSnapshot(
                originalTarget.tripPlaceId(), candidate.latitude(), candidate.longitude(), originalTarget.space()));

        return Map.of(
                TripReplanState.PLACES_KEY, updatedPlaces,
                TripReplanState.MATCHES_KEY, new TripReplanState.Match(originalTarget.tripPlaceId(), candidate),
                TripReplanState.CURSOR_KEY, state.cursor() + 1,
                TripReplanState.ROUTE_KEY, "resolved"
        );
    }

    private boolean exceedsThreshold(TripReplanState.PlaceSnapshot neighbor, AlternativeCandidate candidate) {
        if (neighbor == null || neighbor.latitude() == null || neighbor.longitude() == null) {
            return false;
        }
        int minutes = GeoUtils.estimatedWalkMinutes(
                neighbor.latitude(), neighbor.longitude(), candidate.latitude(), candidate.longitude());
        return minutes > CONFLICT_THRESHOLD_MINUTES;
    }
}
```

**참고**: `User`는 상태 스키마에 정식 필드로 선언하지 않고 `"__user__"` 키로
`state.data()`에서 직접 읽는다 — `User`는 그래프 실행 내내 절대 바뀌지 않는 값이라
채널/리듀서가 필요 없고, `TripReplanState`에 `User`용 getter를 추가해 상태 클래스의
표면적을 넓히는 것보다 이 방식이 더 단순하다.

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.replan.TripReplanGraphTest"`
Expected: PASS — 6개 테스트 전부 통과. 특히 첫 번째 테스트(백트래킹)와 세 번째
테스트(부분 실패)가 이 태스크의 핵심 — 실패하면 좌표/거리 계산을 다시 확인할 것
(테스트의 위도 차이 → km 환산이 의도한 충돌/비충돌을 만드는지 `GeoUtilsTest`의
공식과 대조).

- [ ] **Step 5: 전체 빌드**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/trova/backend/replan/TripReplanGraph.java \
        src/test/java/com/trova/backend/replan/TripReplanGraphTest.java
git commit -m "feat: 일정 재구성 LangGraph4j 그래프(TripReplanGraph) 추가"
```

---

### Task 4: `TripReplanController` — API 엔드포인트

**Files:**
- Create: `src/main/java/com/trova/backend/controller/TripReplanController.java`
- Test: `src/test/java/com/trova/backend/controller/TripReplanControllerTest.java`

**Interfaces:**
- Consumes: `TripReplanGraph.run(User, Trip, boolean)`(Task 3) →
  `TripReplanGraph.ReplanOutcome`, `TripController.AlternativeCandidateResponse`(기존,
  package-private `from(AlternativeCandidate)` — 같은 `controller` 패키지라 재사용
  가능), `CurrentUserService.resolve(Authentication)`(기존), `TripRepository`(기존)
- Produces: `POST /api/trips/{tripId}/replan`

- [ ] **Step 1: 실패 테스트부터 작성**

`src/test/java/com/trova/backend/controller/TripReplanControllerTest.java`:

```java
package com.trova.backend.controller;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.recommendation.AlternativeFilter;
import com.trova.backend.recommendation.AlternativeFinderService;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
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
    @Autowired private TripPlaceRepository tripPlaceRepository;

    // 대안 조회 자체(구글 Places/개인화)는 이 테스트 범위 밖 — TripReplanGraphTest가
    // 이미 백트래킹/타겟 선별을 검증했으므로, 여기서는 컨트롤러의 인증/소유권/응답
    // 변환만 검증한다.
    @MockitoBean private AlternativeFinderService alternativeFinderService;

    private User me;
    private Trip trip;

    @BeforeEach
    void setUp() {
        me = userRepository.save(new User("google", "replan1", "재구성유저", null));
        trip = tripRepository.save(new Trip(me, "테스트 여행", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)));
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
    void 남의_여행이면_404() throws Exception {
        User other = userRepository.save(new User("google", "other", "다른유저", null));
        Trip otherTrip = tripRepository.save(new Trip(other, "다른 여행", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1)));

        mockMvc.perform(post("/api/trips/" + otherTrip.getId() + "/replan")
                        .with(loginAs("replan1", "재구성유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"indoorOnly\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 실외_장소가_실내_대안으로_재구성되면_응답에_담긴다() throws Exception {
        Itinerary itinerary = itineraryRepository.save(new Itinerary(trip, 1, LocalDate.of(2026, 1, 1)));
        TripPlace outdoorPlace = tripPlaceRepository.save(new TripPlace(
                itinerary, "야외공원", "서울", "park", 37.50, 127.00, null, null, 1, PlaceSource.NORMAL, null));
        outdoorPlace.applySpace("OUTDOOR");
        tripPlaceRepository.save(outdoorPlace);

        AlternativeCandidate indoorCandidate = new AlternativeCandidate(
                99L, "g-99", "실내카페", "cafe", 4.7, 200, 37.501, 127.001, "서울",
                null, null, false, null, null);
        when(alternativeFinderService.findAlternatives(any(), eq(outdoorPlace.getId()), any(AlternativeFilter.class)))
                .thenReturn(Optional.of(List.of(indoorCandidate)));

        mockMvc.perform(post("/api/trips/" + trip.getId() + "/replan")
                        .with(loginAs("replan1", "재구성유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"indoorOnly\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replaced[0].tripPlaceId").value(outdoorPlace.getId()))
                .andExpect(jsonPath("$.replaced[0].originalName").value("야외공원"))
                .andExpect(jsonPath("$.replaced[0].candidate.placeId").value(99))
                .andExpect(jsonPath("$.failedTripPlaceIds.length()").value(0));
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.controller.TripReplanControllerTest"`
Expected: FAIL — `TripReplanController` 클래스가 없음.

- [ ] **Step 3: 구현**

`src/main/java/com/trova/backend/controller/TripReplanController.java`:

```java
package com.trova.backend.controller;

import com.trova.backend.entity.User;
import com.trova.backend.replan.TripReplanGraph;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TripReplanController {

    private final CurrentUserService currentUserService;
    private final TripRepository tripRepository;
    private final TripReplanGraph tripReplanGraph;

    public TripReplanController(
            CurrentUserService currentUserService,
            TripRepository tripRepository,
            TripReplanGraph tripReplanGraph
    ) {
        this.currentUserService = currentUserService;
        this.tripRepository = tripRepository;
        this.tripReplanGraph = tripReplanGraph;
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

    @PostMapping("/api/trips/{tripId}/replan")
    public ResponseEntity<TripReplanResponse> replan(
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
                .map(trip -> {
                    TripReplanGraph.ReplanOutcome outcome = tripReplanGraph.run(user, trip, true);
                    return ResponseEntity.ok(TripReplanResponse.from(outcome));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.controller.TripReplanControllerTest"`
Expected: PASS — 3개 테스트 전부 통과.

- [ ] **Step 5: 전체 빌드(이 계획의 마지막 태스크 — 리포 전체 테스트 스위트 포함)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — 기존 테스트 전부 포함해서 그린.

- [ ] **Step 6: 실측 검증(스펙의 "비용 관점" 절 — 응답 시간 실측)**

로컬에서 백엔드를 띄우고, 실제 여러 날짜/여러 장소짜리 여행으로
`POST /api/trips/{tripId}/replan`을 실제로 호출해 응답 시간을 잰다. 결과를
`docs/benchmarks/2026-09-16-trip-replan-latency.md`에 기록(이 프로젝트의 기존
벤치마크 문서 관례를 따름) — 타겟 수별 실제 응답 시간, 동기 응답이 감당 가능한
수준인지 판단 근거로 남긴다. 너무 길면(예: 10타겟에 수십 초) 비동기 전환이 필요한지
여기서 결정한다.

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/trova/backend/controller/TripReplanController.java \
        src/test/java/com/trova/backend/controller/TripReplanControllerTest.java \
        docs/benchmarks/2026-09-16-trip-replan-latency.md
git commit -m "feat: 일정 재구성 API 엔드포인트(TripReplanController) 추가"
```

---

## 이 계획 이후

- 앱(trova-app) 쪽 UI("일정 재구성" 버튼, 조건 선택 화면, 결과 화면)는 이 계획
  범위 밖 — 별도 계획에서 다룬다.
- 날씨 알림 기반 자동 재구성 트리거는 이 기능이 배포된 뒤 별도 스펙에서 다룬다.
- v1 실측 결과 응답 시간이 너무 길면, 비동기 처리(기존 `ProcessingJob` 패턴)로
  전환하는 별도 계획을 검토한다.
