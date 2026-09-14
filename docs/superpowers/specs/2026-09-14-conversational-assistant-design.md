# 대화형 비서(Phase 2) — 설계 스펙

## 배경

[개인화 추천(RAG 기반) Phase 1](2026-09-09-personalization-rag-design.md)이 완성되어
배포됐다(PR #3, main 병합 완료). Phase 1은 `PersonalizationService`의 pgvector 검색과
`UserPreferenceSignal` 이력을 만들었고, "대안 찾기"/"빈 시간 추천" 결과를 개인화 점수로
정렬하며, 상위 1~2개 후보에 "추천 이유" 한 줄을 생성(Generation)한다. 다만 이건 전부
**단발성 호출**이다 — 사용자가 버튼을 누르면 한 번 정렬된 결과가 나올 뿐, 되묻거나
조건을 더 구체화할 방법이 없다.

이번 스펙은 Phase 1의 검색/생성 인프라를 그대로 재사용해서, 사용자가 자연어로 조건을
말하면 그 말을 이해해 기존 검색 기능을 대신 호출해주는 **여러 턴 대화형 비서**를
추가한다. 새로운 추천 로직을 만드는 게 아니라, 이미 있는 검색 기능에 자연어로 말을
걸 수 있는 입구를 하나 더 다는 것이 이 스펙의 핵심이다.

## 목표

- 여행 상세 화면에서 "이 조건에 맞는 곳"을 버튼/필터로 표현할 수 없을 때(예: "사람
  적고 조용한 카페"), 자연어로 말해서 기존 대안 찾기/빈 시간 추천을 원하는 조건으로
  실행할 수 있게 한다.
- 대화 중 사용자가 특정 후보를 마음에 들어하면 그것도 개인화 학습 데이터
  (`UserPreferenceSignal`)로 남겨서, 대화를 할수록 이후 추천이 사용자 취향에 더
  맞게 한다.
- 무료 티어 원칙을 지킨다 — 세션당 턴 수 상한, 도구 호출 최소화, 새 인프라(캐싱
  서버, 별도 DB 등) 없이 기존 컴포넌트 재사용으로 비용을 억제한다.
- 비서는 절대 스스로 일정을 바꾸지 않는다 — 쓰기(장소 교체/삽입)는 항상 사용자가
  카드의 기존 확정 버튼을 눌러야만 일어난다.

## 범위 밖(Out of scope)

- 완전 자유 텍스트 채팅 UI(제안 버튼 위주 + 짧은 자유 입력으로 제한, "이미 확정된
  것" 절 참고) — 실제 여행 AI 앱(Mindtrip, Stardrift 등) 리서치 결과 구조화된
  상호작용이 공통 패턴이었다.
- 장소가 없는 추상적 취향 표현("나는 조용한 곳을 좋아해" 같은 일반론)의 별도 저장 —
  v1은 Phase 1과 동일하게 "구체적 장소에 대한 긍정 신호"만 다룬다(YAGNI).
- 부정 신호(비서가 제안했지만 싫다고 한 것) — Phase 1과 동일하게 v1은 긍정 신호만.
- 여러 기기/세션에 걸친 대화 영속화 — 대화 이력은 세션(시트가 열려있는 동안) 한정,
  서버 재시작이나 앱 재시작 시 사라지는 것을 허용한다.
- Gemini Managed Agents/Interactions API, 서버사이드 컨텍스트 캐싱
  (`previous_interaction_id`) — 후자는 유료 결제 계정 전용 기능으로 확인되어 "비용
  0원 유지" 원칙과 충돌, 전자는 이 프로젝트 규모 대비 통합 복잡도가 안 맞아 기각.
- Gemini 3.6 Flash로 모델 전환 — 파악 결과 유료 전용 모델. 기존
  `gemini-3.5-flash-lite`를 계속 사용한다.

## 이미 확정된 것

- **진입점**: 여행 상세 화면의 각 장소 카드에 "💬 비서에게 물어보기" 버튼(기존
  `AlternativeFinderSheet` 트리거와 같은 자리). 세션은 항상 특정 `tripId` +
  `tripPlaceId`(또는 빈 시간 추천이면 `day`) 컨텍스트에 묶여 시작된다 — 어떤
  장소/구간 얘기인지 Gemini가 추측할 필요가 없다.
- **액션 실행 방식**: 비서는 조회만 하고, 실제 일정 변경(교체/삽입)은 기존
  `AlternativeFinderSheet` 카드의 확정 버튼을 사용자가 직접 눌러야 일어난다 — 비서
  응답의 후보 카드도 같은 컴포넌트/같은 확정 흐름을 재사용한다.
- **대화 이력 저장**: 세션 한정(새 DB 엔티티 없음, 서버 인메모리). 단, 대화 중
  구체적 장소에 대한 긍정 표현은 기존 `UserPreferenceSignal` 인프라에 새
  `SignalType.CHAT_LIKED`로 기록되어 세션이 끝나도 남는다.
- **턴 상한**: 세션(시트 열림~닫힘)당 최대 10턴.
- **도구 범위**: 조회 도구(`find_alternatives`, `get_gap_recommendations`)만
  Gemini가 자유롭게 호출 가능. 유일한 예외는 `note_preference` — 아래 "쓰기 도구
  예외" 절 참고.

## 컴포넌트 구조

```
Controller: ConversationController (신규)
  POST /api/conversations/{sessionId}/messages   ← 사용자 메시지 한 턴 보냄
  DELETE /api/conversations/{sessionId}           ← 시트 닫을 때 세션 정리(선택)

Service: ConversationService (신규)
  - ConcurrentHashMap<String sessionId, ConversationState>로 세션 보관
  - 턴마다: 이력 로드 → Gemini 호출(tools 포함) → functionCall이면 도구 실행 →
    functionResponse로 재호출 → 최종 응답 파싱 → 이력에 추가 → 반환
  - 기존 GeminiTextClient를 확장(또는 나란히 GeminiChatClient 신규)해서
    tools 파라미터 + functionCall/functionResponse 왕복 지원 추가
  - 만료 세션 정리용 @Scheduled 작업 하나(예: 마지막 활동 후 30분 미사용 시 제거)

Tool 실행 계층: ConversationToolExecutor (신규, 얇은 어댑터)
  - functionCall.name/args → 기존 서비스 메서드 호출로 매핑
  - 새 추천 로직 없음 — 순수 어댑터 + 검증(아래 "쓰기 도구 예외" 참고)

기존 서비스 재사용(변경 없음):
  - AlternativeFinderService, GapRecommendationService, PersonalizationService
  - UserPreferenceSignalRepository, PlaceEmbeddingService
```

`ConversationState`: `{ sessionId, tripId, userId, tripPlaceId 또는 day,
List<Turn> history, Set<Long> shownCandidateIds, int turnCount, Instant lastActivity }`.
`Turn`은 `{ role: user|model|tool, content }`.

멀티 인스턴스 배포(k3s 다중 replica) 시 인메모리 세션은 sticky session이 필요하다 —
지금은 단일 인스턴스 배포 목표라 문제가 되지 않지만, 배포 확장 시 재검토가 필요한
항목으로 기록해둔다.

## 요청/응답 흐름 (한 턴)

1. 앱 → `POST /api/conversations/{sessionId}/messages` `{ "message": "조용한 카페로 바꿔줘" }`.
2. 세션이 없으면 첫 턴으로 새로 생성(이때 앱이 `tripId`/`tripPlaceId` 또는 `day`를
   함께 넘긴다). `turnCount >= 10`이면 Gemini를 호출하지 않고 즉시
   `{ turnLimitReached: true }`로 응답(비용 방어).
3. 이력 + 새 메시지로 `gemini-3.5-flash-lite:generateContent`를 `tools` 파라미터와
   함께 호출. `functionCall`이 오면 `ConversationToolExecutor`가 실제 서비스를
   호출하고, 결과를 `functionResponse` 파트로 담아 같은 요청 흐름 안에서 재호출 →
   최종 텍스트를 받는다.
4. 후보를 반환하는 도구를 호출했다면, 그 candidate id들을 `shownCandidateIds`에
   추가한다(이후 `note_preference` 검증에 쓰임).
5. 응답: `{ "reply": "...", "candidates": AlternativeCandidate[] | null, "turnCount": N, "turnLimitReached": false }`.
   `candidates`는 Phase 1이 이미 쓰는 `AlternativeCandidate` DTO를 그대로 재사용한다
   (신규 타입 없음).
6. 앱은 `reply`를 말풍선으로, `candidates`가 있으면 기존
   `AlternativeFinderSheet`의 카드 컴포넌트로 렌더링한다. 각 카드의 확정 버튼은
   기존 대안 교체/삽입 API를 그대로 호출한다 — 비서 경로를 거치지 않는다.

## 도구(function calling) 스키마

세션이 이미 `tripId`/`tripPlaceId`/`day`를 쥐고 있으므로, Gemini에 넘기는 함수는
최소한의 필터 인자만 받는다 — ID는 항상 서버가 세션 상태에서 채운다(Gemini가 ID를
추측하게 하면 환각 위험).

```
find_alternatives(category?: string, indoor?: boolean)
  → ConversationToolExecutor가 세션의 tripPlaceId로 AlternativeFinderService를 호출.
  → 카테고리/실내여부 필터를 기존 서비스가 인자로 직접 받는지는 구현 계획 단계에서
    실제 시그니처를 확인해 정한다 — 못 받으면 결과를 받은 뒤 서버에서 후처리
    필터링하는 방식으로 폴백한다.

get_gap_recommendations()
  → 세션의 tripId+day로 GapRecommendationService.findGaps 호출. 인자 없음(이미
    시간 비는 구간을 스캔하는 로직이라 필터가 필요 없다).
```

두 도구 다 읽기 전용이다.

### 쓰기 도구 예외: `note_preference`

대화 중 사용자가 특정 후보를 마음에 들어하면 개인화 학습 데이터로 남기고 싶다는
목표(이 스펙의 "목표" 절)와, "도구는 조회만" 원칙이 충돌한다. 이 신호 기록은
일정을 바꾸지 않고 사용자에게 보이는 변화도 없는 **백그라운드 학습 신호**라서 —
이미 북마크/여행 담기 등에서 자동으로 기록되는 것과 같은 성격 — 조회 전용 원칙의
의도(비서가 일정을 마음대로 바꾸지 못하게 하는 것)를 해치지 않는다고 판단해,
좁게 범위를 제한한 전용 쓰기 도구 하나만 예외로 둔다:

```
note_preference(placeId: number)
  → 검증: placeId가 이 세션의 shownCandidateIds에 없으면 거부(조용히 무시,
    Gemini가 안 보여준 장소 ID를 지어내 기록하는 것을 차단).
  → UserPreferenceSignalRepository.save(new UserPreferenceSignal(user, place,
    SignalType.CHAT_LIKED))
  → placeEmbeddingService.ensureEmbeddings(List.of(place)) — 오늘 세션에서 고친
    "신호가 임베딩 없는 장소를 가리키는" 버그와 같은 이유로, 신호는 항상 임베딩된
    장소를 가리켜야 한다.
  → 사용자에게 보여줄 응답 데이터 없음 — 카드도, 일정 변경도 없다. "미리보기 후
    확정" 원칙은 일정을 바꾸는 액션에만 적용되므로 이 도구는 그 대상이 아니다.
```

`SignalType`에 `CHAT_LIKED` 값을 추가한다(기존 5개: `BOOKMARK`,
`TRIP_PLACE_ADDED`, `ALTERNATIVE_REPLACED`, `GAP_INSERTED`,
`VIDEO_PLACE_MATCHED`와 동일 가중치 1.0으로 취급, Phase 1 스펙의 "v1에서는 모두
동일 가중치" 원칙을 그대로 따른다).

## 에러/폴백 처리

- **턴 상한(10턴) 도달**: Gemini를 호출하지 않고 즉시 `turnLimitReached: true`
  반환 → 앱은 "새로 시작하기" 버튼 노출.
- **자유 입력 길이 상한**: 서버에서 메시지 글자 수 캡(300자)을 검증, 초과 시 400 —
  Gemini 호출 전에 걸러 비용을 방어한다.
- **Gemini 호출 실패(타임아웃/5xx)**: `AlternativeFinderService`의 기존 "검색
  실패를 500으로 흘려보내지 않는다" 원칙을 그대로 따른다 — 예외를 잡아
  `{ reply: "지금 답변을 가져오지 못했어요, 다시 시도해주세요" }`로 감싼 200
  응답을 반환하고 `ApiCallLogService`에 실패를 기록한다.
- **도구 실행 실패**(DB 오류 등): `ConversationToolExecutor`가 예외를 잡아
  `functionResponse`에 에러 내용을 담아 Gemini에 돌려준다 → Gemini가 자연스럽게
  "지금 조회가 안 돼요"라고 답하게 하고, 턴 전체를 실패시키지 않는다.
- **세션 만료/서버 재시작으로 sessionId를 못 찾음**: 에러를 내지 않고 새 세션으로
  취급(이력 없이 시작) — "세션 한정" 원칙과 일치, 별도 에러 UI가 필요 없다.
- **레이트리밋**: 기존 `GeminiTextClient`의 백오프/재시도 로직을 그대로 상속받는다
  (새 로직 없음).

## 관측성

`ConversationService`의 Gemini 생성 호출과 `ConversationToolExecutor`가 실행하는
도구 호출 모두 `ApiCallLogService.record(...)`로 로깅한다 —
`provider="gemini", operation="conversation-turn"`. 이번 세션 최종 리뷰에서
확립된 "새 외부 호출 지점은 전부 로깅" 규칙을 그대로 적용한다.

## 테스트 전략

- `ConversationService`: 턴 상한 도달 시 Gemini를 호출하지 않는지(Mockito
  `verifyNoInteractions`), 세션 미존재 시 새 세션으로 취급하는지 검증.
- `ConversationToolExecutor`: 각 도구 이름 → 올바른 기존 서비스 메서드 호출로
  매핑되는지, `note_preference`가 `shownCandidateIds`에 없는 placeId를 거부하는지
  검증.
- `ConversationController` 통합 테스트: Gemini 클라이언트를 목으로 대체해
  functionCall → functionResponse 왕복 흐름 전체(도구 호출 1회 포함)가 최종
  `reply`/`candidates`로 이어지는지 검증. Phase 1과 같은 이유로
  `PlaceEmbeddingService`/`PersonalizationService`는 `@MockitoBean`으로
  격리한다(H2에는 `vector` 타입이 없음).
- `SignalType.CHAT_LIKED` 추가로 인한 기존 `UserPreferenceSignal` 관련 테스트
  영향은 없음(신규 enum 값 추가만, 기존 로직 변경 없음) — 회귀 확인만 한다.

## 이 스펙 이후

앱(trova-app) 쪽 UI 구현("💬 비서에게 물어보기" 버튼, 채팅 말풍선 + 후보 카드
렌더링)은 이 스펙 범위 밖이며, 구현 계획 단계에서 백엔드와 별도 태스크로 다룬다.
