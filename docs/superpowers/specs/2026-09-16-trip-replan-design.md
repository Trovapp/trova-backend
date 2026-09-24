# 전체 일정 재구성 — 설계 스펙

## 배경

"대화형 비서(Phase 2)"는 장소 **하나**에 대한 대안 찾기(단일 도구 호출)만 지원한다.
이번 스펙은 여행 **전체**(여러 날짜, 여러 장소)를 한 번에 훑어서 조건에 안 맞는
장소들을 동시에 재구성하는 기능을 추가한다 — "이번 여행 다 실내 위주로 바꿔줘" 같은
요청.

이 기능은 여러 장소를 순회하면서, 새로 고른 대안끼리 동선(이동 시간) 충돌이 있으면
다른 후보로 되돌아가 재시도(백트래킹)해야 한다 — 이 반복+분기+되돌아가기가 이
프로젝트에 **LangGraph4j**(Java용 그래프 오케스트레이션 라이브러리)를 처음 도입하는
근거다. 단일 도구 호출로 끝나는 기존 대화형 비서와 달리, 이 기능만이 그래프형
오케스트레이션이 실제로 필요한 지점이라고 판단해 선택했다(대안 찾기 하나짜리
기능에는 프레임워크가 과함).

## 목표

- 사용자가 여행 상세 화면에서 조건(v1: 실내/실외)을 선택하면, 그 조건에 안 맞는
  장소들을 한 번에 재구성 후보로 제시한다.
- 새로 고른 대안들이 이웃 장소와 이동시간 충돌이 없도록, 후보를 순서대로 시도하며
  백트래킹한다.
- 절대 자동으로 일정을 바꾸지 않는다 — 재구성 결과는 미리보기이고, 장소별 확정은
  사용자가 직접 눌러야 한다(기존 원칙 유지).
- 무료 티어 비용을 지킨다 — 후보 조회는 장소당 정확히 1회만 한다(백트래킹은 이미
  받아온 후보 목록 안에서만 이루어짐, API 재호출 없음). 처리 대상 장소 수에 상한을
  둔다.

## 범위 밖(v1)

- 예산 조건("예산 안에서 다시 짜줘") — `AlternativeCandidate`에 가격 데이터가 없어서
  추가 작업이 필요하다. v1은 실내/실외 조건만 지원한다.
- 날씨 알림 기반 자동 트리거(비 소식 감지 시 자동으로 이 기능을 호출하는 것) — 이
  기능이 먼저 완성된 뒤, 별도 스펙에서 다룬다.
- 온디맨드 실내외 태깅(space가 null인 장소를 Gemini로 즉석 태깅) — v1은 태그 없는
  장소를 보수적으로 재구성 대상에서 제외한다.
- 비동기 처리(작업 생성 후 폴링) — v1은 동기 응답으로 시작하고, 실제 응답 시간을
  실측한 뒤 필요하면 비동기로 전환한다.

## 아키텍처

새 패키지 `com.trova.backend.replan`. 새 추천 로직은 만들지 않고, 기존
`AlternativeFinderService`를 그래프의 한 노드 안에서 그대로 호출한다.

```
TripReplanController (POST /api/trips/{tripId}/replan)
        │
        ▼
TripReplanGraph (LangGraph4j StateGraph, TripReplanState를 들고 순회)
        │  각 노드가 호출
        ▼
AlternativeFinderService (기존, 변경 없음) ─ 후보 조회 + 개인화 정렬
```

**LangGraph4j 선택 근거**: `org.bsc.langgraph4j:langgraph4j-core`(Maven Central,
1.8.13)는 LangChain4j/Spring AI가 선택적 통합일 뿐 하드 의존성이 아니다 — 기존
`GeminiChatClient`(직접 REST 호출) 코드를 바꿀 필요 없이, 그래프의 노드 함수 안에서
기존 서비스를 그대로 호출하면 된다. `addConditionalEdges`로 조건부 분기와 사이클을
코드 레벨에서 직접 표현할 수 있어, 백트래킹 요구사항에 정확히 맞는다. Java 17+
요구(이 프로젝트는 21이라 문제없음).

## 진입점

여행 상세 화면에 "일정 재구성" 버튼(신규, 대화형 비서와 분리된 별도 화면) →
조건(실내/실외) 선택 → `POST /api/trips/{tripId}/replan` 호출 → 결과 화면(원래 장소
vs 대안 장소 나란히, 장소별 "이걸로 바꾸기" 버튼 — 기존 `replacePlace` API를 그대로
호출, 새 확정 API를 만들지 않는다).

## 데이터 모델

```java
// com.trova.backend.replan.TripReplanRequest
public record TripReplanRequest(Boolean indoorOnly) {}

// com.trova.backend.replan.TripReplanResponse
public record TripReplanResponse(
    List<ReplanResult> replaced,   // 성공: 원래 장소 + 새 대안
    List<Long> failedTripPlaceIds  // 실패: 조건에 맞는 대안을 못 찾은 원래 장소 id
) {}

public record ReplanResult(
    Long tripPlaceId,              // 원래 장소(TripPlace.id)
    String originalName,
    AlternativeCandidate candidate // 확정된 대안 (기존 DTO 재사용)
) {}
```

새 테이블/컬럼 없음 — 이 기능은 순수 조회+제안이고, 확정은 기존 `replacePlace`
경로를 그대로 타므로 영속화할 새 상태가 없다.

## 그래프 설계

**`TripReplanState`** — 그래프 실행 동안 들고 다니는 상태:
- `targets: List<TripPlace>` — 조건에 안 맞는 장소들, day+visitOrder 순으로 정렬,
  최대 10개로 자름(비용 상한)
- `currentIndex: int` — 지금 처리 중인 타겟의 인덱스
- `currentCandidates: List<AlternativeCandidate>` — 현재 타겟에 대해 **1회 조회한**
  후보 목록(정렬된 상태 그대로)
- `candidateTryIndex: int` — 현재 타겟에서 몇 번째 후보를 시도 중인지(최대 3)
- `workingPlaces: Map<Long, PlaceLocation>` — 확정된 대안까지 반영한 "작업 중" 일정의
  좌표 스냅샷(충돌 체크가 최신 상태를 보도록)
- `results: List<ReplanResult>`, `failedIds: List<Long>`

**노드**:

1. **`identify_targets`**: `TripPlaceRepository`로 여행의 모든 장소를 day+visitOrder
   순으로 조회. `space`가 요청 조건과 안 맞는 것만(예: `indoorOnly=true`인데
   `space="OUTDOOR"`) 타겟으로 추출. `space == null`인 장소는 판단 근거가 없으므로
   제외(v1 범위 밖 절 참고). 최대 10개로 자름. 타겟이 0개면 바로 `finalize`로.

2. **`fetch_candidates`**: 현재 타겟(`targets[currentIndex]`)에 대해
   `AlternativeFinderService.findAlternatives`를 **정확히 1회** 호출, 결과를
   `currentCandidates`에 저장, `candidateTryIndex = 0`. 후보가 빈 리스트면(API 실패
   포함, 기존 서비스가 이미 빈 리스트로 폴백함) 즉시 이 타겟을 `failedIds`에 넣고
   다음 타겟으로(재시도 없음 — API가 이미 안 되는 상황에서 또 부르는 건 무의미).

3. **`check_conflict`**: `currentCandidates[candidateTryIndex]`와
   `workingPlaces`상의 이전/다음 이웃 장소 사이 이동시간을 계산(기존
   `AlternativeFinderService`의 haversine 기반 이동시간 추정 로직과 같은 방식).
   30분 초과면 충돌(`GapRecommendationService.GAP_THRESHOLD`와 같은 기준 재사용).
   - **충돌 없음** → `workingPlaces` 갱신, `results`에 추가, 다음 타겟으로
     (`currentIndex++`, `fetch_candidates`로)
   - **충돌 있고 `candidateTryIndex < 2`(0-indexed, 최대 3개 후보까지)** →
     `candidateTryIndex++`, **같은 노드로 재진입**(사이클) — API 재호출 없이 다음
     순위 후보만 검사
   - **충돌 있고 후보 소진** → `failedIds`에 추가, 다음 타겟으로

4. **`finalize`**: `results`/`failedIds`를 `TripReplanResponse`로 조립.

`identify_targets` → (타겟 있으면) `fetch_candidates` → `check_conflict` →
(사이클 또는 다음 타겟) → 모든 타겟 처리 완료 시 `finalize` → END.

## 에러 처리

- **후보 조회 실패**: 위 3번 참고, 재시도 없이 즉시 실패 처리.
- **`space == null`인 장소**: 재구성 대상에서 보수적으로 제외(온디맨드 태깅은 v1
  범위 밖).
- **그래프 실행 중 예상 밖 예외**: 그 시점까지의 부분 결과(`results`/`failedIds`)를
  버리지 않고 응답한다 — 전체를 500으로 날리지 않는다. 처리하지 못한 나머지 타겟은
  `failedIds`에 포함시켜 사용자에게 알린다.
- **소유권 검증**: `trip.getUser().getId().equals(user.getId())`, 아니면 404(기존
  패턴과 동일).

## 비용 관점

한 요청당 최악의 경우: 구글 Places 검색 10회(타겟당 1회), pgvector 개인화 조회
10회(`AlternativeFinderService`가 후보마다 호출), Gemini 추천 이유 생성 최대
20회(`AlternativeFinderService`의 `EXPLANATION_TOP_N=2` 제한이 타겟마다 적용됨).
무료 티어 한도 안에서 감당 가능한 수준으로 설계했다.

**응답 시간 경고**: 최대 10개 타겟을 동기로 순회하면 기존 "장소 하나" 대안 찾기보다
훨씬 오래 걸릴 수 있다(최악의 경우 수십 초). v1은 동기 응답으로 시작하고, 구현 후
실제 응답 시간을 실측해서 벤치마크 문서에 기록한다 — 너무 길면 비동기(기존
`ProcessingJob` 패턴)로 전환을 검토한다(추측 대신 실측 원칙).

## 테스트 전략

**`TripReplanGraphTest`** (Mockito, `AlternativeFinderService`를 목으로):
- 1등 후보가 이웃과 40분 거리(충돌), 2등이 10분 거리 → 최종 결과가 2등 후보로
  확정되는지 검증(백트래킹이 실제로 작동함을 증명하는 핵심 테스트).
- 3개 후보 전부 충돌 → 해당 타겟이 `failedIds`에 들어가고 원래 장소는 안 바뀌며,
  **다음 타겟은 계속 처리됨**을 검증.
- `verify(alternativeFinderService, times(1)).findAlternatives(...)` — 백트래킹
  중에도 후보 조회가 타겟당 1회만 일어남을 검증(비용 설계 고정).
- `space=INDOOR`인 장소는 `indoorOnly=true` 요청에서 타겟이 안 됨, `space=null`인
  장소는 제외됨을 검증.
- 조건에 안 맞는 장소가 15개여도 최대 10개만 처리됨을 검증.

**`TripReplanControllerTest`** (`@SpringBootTest`, 기존 패턴):
- 남의 여행 tripId → 404.
- 실제 요청 → 응답 JSON에 원래 장소/대안 쌍이 올바르게 실림.
- H2에 `vector` 타입이 없으므로 `PersonalizationService`, `PlaceEmbeddingService`,
  `GooglePlacesApiClient`는 `@MockitoBean`으로 격리(이번 세션에서 확립된 패턴).

**실측 검증**: 구현 완료 후 실제 여행 데이터로 한 번 실행해 실제 응답 시간을 재고
`docs/benchmarks/`에 기록 — 동기/비동기 전환 여부를 이 실측으로 판단한다.

## 이 스펙 이후

- 앱(trova-app) 쪽 UI(재구성 버튼, 조건 선택 화면, 결과 화면)는 이 스펙 범위 밖 —
  별도 계획에서 다룬다.
- 날씨 알림 기반 자동 재구성 트리거는 이 기능이 먼저 완성된 뒤 별도 스펙에서 다룬다.
