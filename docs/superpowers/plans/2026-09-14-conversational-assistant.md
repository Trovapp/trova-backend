# 대화형 비서(Phase 2) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 여행 상세 화면에서 자연어로 조건을 말하면 Gemini function calling으로 기존
`AlternativeFinderService`/`GapRecommendationService`를 대신 호출해주는 세션 한정
대화형 비서 백엔드를 만든다.

**Architecture:** `ConversationController`가 세션 한 턴 요청을 받아 `ConversationService`에
위임한다. `ConversationService`는 신규 `GeminiChatClient`로 Gemini에 tools 파라미터와
함께 메시지를 보내고, `functionCall`이 오면 `ConversationToolExecutor`가 기존 서비스를
실제로 호출한 뒤 결과를 `functionResponse`로 Gemini에 돌려줘 최종 답변을 받는다. 세션
상태(`ConversationState`)는 `ConversationSessionStore`가 인메모리로 보관하며 TTL로
정리한다. 새 DB 테이블은 없다 — `SignalType`에 값 하나(`CHAT_LIKED`)만 추가한다.

**Tech Stack:** Spring Boot(Java 21), `RestClient`(Gemini REST 직접 호출),
`gemini-3.5-flash-lite`(기존 생성 모델과 동일), 인메모리 `ConcurrentHashMap` 세션 저장,
`@Scheduled` 세션 만료 정리(기존 `WeatherCheckScheduler`와 같은 패턴, `@EnableScheduling`은
`TrovaBackendApplication`에 이미 적용돼 있음).

**Spec:** [docs/superpowers/specs/2026-09-14-conversational-assistant-design.md](../specs/2026-09-14-conversational-assistant-design.md)

## Global Constraints

- 이 계획은 백엔드 전용이다 — trova-app(앱) UI는 범위 밖(스펙 "이 스펙 이후" 절).
- 도구는 `find_alternatives`/`get_gap_recommendations`(조회 전용) + `note_preference`
  (좁게 범위를 제한한 쓰기 예외, 세션에서 이미 보여준 placeId만 허용)뿐이다. 그 외
  쓰기 도구를 추가하지 않는다.
- 세션당 최대 턴 수: 10. 자유 입력 메시지 길이 상한: 300자.
- 세션 상태는 새 DB 테이블 없이 서버 인메모리(`ConcurrentHashMap`)로만 보관한다 —
  세션 TTL 30분.
- Gemini 호출 실패/도구 실행 실패는 절대 500으로 노출하지 않는다 — 항상 폴백 문구가
  담긴 200 응답으로 감싼다(기존 `AlternativeFinderService`/`GeminiTextClient`와
  같은 원칙).
- H2 테스트 DB에는 `vector` 타입이 없다 — `PersonalizationService`/`PlaceEmbeddingService`가
  실제로 얽히는 통합 테스트는 전부 `@MockitoBean`으로 격리한다(Task 5).
- 새 외부 호출 지점(`ConversationService`의 Gemini 대화 호출)은
  `ApiCallLogService.record(...)`로 로깅한다 — `provider="gemini",
  operation="conversation-turn"`.
- 새 브랜치/워크트리에서 작업한다 — Phase 1(`feat/personalized-rag-ranking`)과 같은
  관례로 `feat/conversational-assistant` 브랜치를 `main`에서 분기해 사용한다
  (superpowers:using-git-worktrees).
- 커밋 메시지는 `타입: 내용` 형식만 사용한다(CLAUDE.md).

---

## 사전 조사로 확인된 사실 (구현 시 재확인 불필요, 실제 검증 완료)

- `AlternativeFinderService.findAlternatives(User user, Long tripPlaceId,
  AlternativeFilter filter)`는 이미 `AlternativeFilter(String category, Boolean
  indoorOnly, Double maxDistanceKm, Integer maxTravelMinutes, TransportMode
  transportMode)`로 category/indoor 필터를 직접 받는다 — 후처리 필터링 폴백은 필요
  없다.
- `GeminiTextClient`는 짧은 문장 생성 전용(`Optional<String> generate(String
  prompt)`)이라 tools 파라미터를 지원하지 않는다 — 이 계획은 별도
  `GeminiChatClient`를 신규로 만든다(기존 클라이언트는 변경하지 않음).
- Gemini function calling의 실제 멀티턴 요청 형식을 2026-09-14에 실제 API 호출로
  검증했다(`gemini-3.5-flash-lite:generateContent`):
  - 1차 호출 응답의 `functionCall` 파트는 `thoughtSignature`가 같은 파트에 형제
    필드로 온다: `{"functionCall": {"name":..., "args":{...}}, "thoughtSignature": "..."}`.
  - 2차 호출(도구 결과를 돌려줄 때) 형식: `role: "function"`은 **거부됨**(400,
    "Role 'function' is not supported"). 대신 `role: "user"`로 `functionResponse`
    파트를 보내야 한다. 그리고 직전 모델의 `functionCall` 파트를 그대로(정확히 같은
    `thoughtSignature` 값과 함께) `role: "model"`로 다시 보내야 한다 — 안 그러면
    400("missing thought_signature")이 난다.
  - 검증된 정확한 2차 요청 `contents` 형태:
    ```json
    [
      {"role": "user", "parts": [{"text": "<사용자 메시지>"}]},
      {"role": "model", "parts": [{"functionCall": {"name": "...", "args": {...}}, "thoughtSignature": "<1차 응답에서 받은 값 그대로>"}]},
      {"role": "user", "parts": [{"functionResponse": {"name": "...", "response": {...}}}]}
    ]
    ```
    이 형태로 2026-09-14 실제 호출 시 200과 함께 최종 텍스트를 정상 수신 확인함.

---

### Task 1: GeminiChatClient — Gemini function calling REST 클라이언트

**Files:**
- Create: `src/main/java/com/trova/backend/conversation/GeminiChatClient.java`
- Create: `src/main/java/com/trova/backend/conversation/GeminiChatClientImpl.java`
- Test: `src/test/java/com/trova/backend/conversation/GeminiChatClientImplTest.java`

**Interfaces:**
- Produces:
  - `GeminiChatClient.HistoryTurn(String role, String text)`
  - `GeminiChatClient.ToolDeclaration(String name, String description, Map<String, ParamSchema> parameters)`
  - `GeminiChatClient.ParamSchema(String type, String description)`
  - `GeminiChatClient.FunctionCall(String name, Map<String, Object> args, String thoughtSignature)`
  - `GeminiChatClient.ChatResult(FunctionCall functionCall, String text)` — 정확히
    하나만 null이 아님. 실패 시 둘 다 null.
  - `ChatResult sendMessage(List<HistoryTurn> history, String userMessage, List<ToolDeclaration> tools)`
  - `ChatResult sendFunctionResult(List<HistoryTurn> history, String userMessage, FunctionCall functionCall, Map<String, Object> functionResult, List<ToolDeclaration> tools)`

- [ ] **Step 1: 인터페이스 작성**

`src/main/java/com/trova/backend/conversation/GeminiChatClient.java`:

```java
package com.trova.backend.conversation;

import java.util.List;
import java.util.Map;

public interface GeminiChatClient {

    ChatResult sendMessage(List<HistoryTurn> history, String userMessage, List<ToolDeclaration> tools);

    ChatResult sendFunctionResult(
            List<HistoryTurn> history, String userMessage, FunctionCall functionCall,
            Map<String, Object> functionResult, List<ToolDeclaration> tools);

    record HistoryTurn(String role, String text) {
    }

    record ToolDeclaration(String name, String description, Map<String, ParamSchema> parameters) {
    }

    record ParamSchema(String type, String description) {
    }

    record FunctionCall(String name, Map<String, Object> args, String thoughtSignature) {
    }

    /** functionCall과 text 중 정확히 하나만 채워진다. 호출 자체가 실패하면 둘 다 null이다. */
    record ChatResult(FunctionCall functionCall, String text) {
    }
}
```

- [ ] **Step 2: 실패 테스트부터 작성**

`src/test/java/com/trova/backend/conversation/GeminiChatClientImplTest.java`:

```java
package com.trova.backend.conversation;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GeminiChatClientImplTest {

    private static final String URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent";

    @Test
    void 텍스트로만_응답하면_text를_반환한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[{"text":"안녕하세요"}],"role":"model"}}]}
                        """, MediaType.APPLICATION_JSON));

        GeminiChatClientImpl client = new GeminiChatClientImpl("test-key", builder);
        GeminiChatClient.ChatResult result = client.sendMessage(List.of(), "안녕", List.of());

        assertThat(result.text()).isEqualTo("안녕하세요");
        assertThat(result.functionCall()).isNull();
    }

    @Test
    void functionCall이_오면_thoughtSignature와_함께_반환한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[
                            {"functionCall":{"name":"find_alternatives","args":{"category":"카페","indoor":true}},"thoughtSignature":"SIG123"}
                        ],"role":"model"}}]}
                        """, MediaType.APPLICATION_JSON));

        GeminiChatClientImpl client = new GeminiChatClientImpl("test-key", builder);
        var tools = List.of(new GeminiChatClient.ToolDeclaration(
                "find_alternatives", "설명",
                Map.of("category", new GeminiChatClient.ParamSchema("string", "카테고리"))));
        GeminiChatClient.ChatResult result = client.sendMessage(List.of(), "조용한 카페 찾아줘", tools);

        assertThat(result.text()).isNull();
        assertThat(result.functionCall().name()).isEqualTo("find_alternatives");
        assertThat(result.functionCall().args()).containsEntry("category", "카페");
        assertThat(result.functionCall().thoughtSignature()).isEqualTo("SIG123");
    }

    @Test
    void sendFunctionResult은_검증된_요청_형태로_보낸다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL))
                .andExpect(content().json("""
                        {
                          "contents": [
                            {"role":"user","parts":[{"text":"조용한 카페로 바꿔줘"}]},
                            {"role":"model","parts":[{"functionCall":{"name":"find_alternatives","args":{"category":"카페","indoor":true}},"thoughtSignature":"SIG123"}]},
                            {"role":"user","parts":[{"functionResponse":{"name":"find_alternatives","response":{"candidates":[]}}}]}
                          ],
                          "tools": [{"functionDeclarations":[{"name":"find_alternatives","description":"설명","parameters":{"type":"object","properties":{"category":{"type":"string","description":"카테고리"}}}}]}]
                        }
                        """))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[{"text":"커피한약방을 추천해요"}],"role":"model"}}]}
                        """, MediaType.APPLICATION_JSON));

        GeminiChatClientImpl client = new GeminiChatClientImpl("test-key", builder);
        var tools = List.of(new GeminiChatClient.ToolDeclaration(
                "find_alternatives", "설명",
                Map.of("category", new GeminiChatClient.ParamSchema("string", "카테고리"))));
        var functionCall = new GeminiChatClient.FunctionCall(
                "find_alternatives", Map.of("category", "카페", "indoor", true), "SIG123");

        GeminiChatClient.ChatResult result = client.sendFunctionResult(
                List.of(), "조용한 카페로 바꿔줘", functionCall, Map.of("candidates", List.of()), tools);

        assertThat(result.text()).isEqualTo("커피한약방을 추천해요");
    }

    @Test
    void 호출_실패하면_functionCall_text_둘다_null이다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withServerError());

        GeminiChatClientImpl client = new GeminiChatClientImpl("test-key", builder);
        GeminiChatClient.ChatResult result = client.sendMessage(List.of(), "안녕", List.of());

        assertThat(result.text()).isNull();
        assertThat(result.functionCall()).isNull();
    }
}
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.conversation.GeminiChatClientImplTest"`
Expected: FAIL — `GeminiChatClientImpl` 클래스가 없음(컴파일 실패).

- [ ] **Step 4: 구현**

`src/main/java/com/trova/backend/conversation/GeminiChatClientImpl.java`:

```java
package com.trova.backend.conversation;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class GeminiChatClientImpl implements GeminiChatClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiChatClientImpl.class);
    // GeminiTextClientImpl과 동일 모델 — 이 프로젝트가 실제로 쓰는 생성 모델과 통일한다.
    private static final String MODEL = "gemini-3.5-flash-lite";

    private final String apiKey;
    private final RestClient restClient;

    public GeminiChatClientImpl(
            @Value("${app.pipeline.gemini-api-key}") String apiKey,
            RestClient.Builder restClientBuilder
    ) {
        this.apiKey = apiKey;
        this.restClient = restClientBuilder
                .baseUrl("https://generativelanguage.googleapis.com")
                .build();
    }

    // 2026-09-14 실제 호출로 검증한 형태를 그대로 모델링한다 — 위 "사전 조사로 확인된
    // 사실" 절 참고. Part는 text/functionCall/functionResponse/thoughtSignature 중
    // 실제로 쓰는 필드만 담고 나머지는 JsonInclude.NON_NULL로 생략한다.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ReqPart(String text, ReqFunctionCall functionCall, ReqFunctionResponse functionResponse, String thoughtSignature) {
        static ReqPart ofText(String text) {
            return new ReqPart(text, null, null, null);
        }
        static ReqPart ofFunctionCall(String name, Map<String, Object> args, String thoughtSignature) {
            return new ReqPart(null, new ReqFunctionCall(name, args), null, thoughtSignature);
        }
        static ReqPart ofFunctionResponse(String name, Map<String, Object> response) {
            return new ReqPart(null, null, new ReqFunctionResponse(name, response), null);
        }
    }
    private record ReqFunctionCall(String name, Map<String, Object> args) {
    }
    private record ReqFunctionResponse(String name, Map<String, Object> response) {
    }
    private record ReqContent(String role, List<ReqPart> parts) {
    }
    private record ReqTool(List<ReqFunctionDeclaration> functionDeclarations) {
    }
    private record ReqFunctionDeclaration(String name, String description, ReqParameters parameters) {
    }
    private record ReqParameters(String type, Map<String, ReqPropertySchema> properties) {
    }
    private record ReqPropertySchema(String type, String description) {
    }
    private record ChatRequest(List<ReqContent> contents, List<ReqTool> tools) {
    }

    private record RespFunctionCall(String name, Map<String, Object> args) {
    }
    private record RespPart(String text, RespFunctionCall functionCall, String thoughtSignature) {
    }
    private record RespContent(List<RespPart> parts) {
    }
    private record RespCandidate(RespContent content) {
    }
    private record ChatResponse(List<RespCandidate> candidates) {
    }

    @Override
    public ChatResult sendMessage(List<HistoryTurn> history, String userMessage, List<ToolDeclaration> tools) {
        List<ReqContent> contents = buildHistoryContents(history);
        contents.add(new ReqContent("user", List.of(ReqPart.ofText(userMessage))));
        return call(contents, tools);
    }

    @Override
    public ChatResult sendFunctionResult(
            List<HistoryTurn> history, String userMessage, FunctionCall functionCall,
            Map<String, Object> functionResult, List<ToolDeclaration> tools
    ) {
        List<ReqContent> contents = buildHistoryContents(history);
        contents.add(new ReqContent("user", List.of(ReqPart.ofText(userMessage))));
        contents.add(new ReqContent("model", List.of(
                ReqPart.ofFunctionCall(functionCall.name(), functionCall.args(), functionCall.thoughtSignature()))));
        contents.add(new ReqContent("user", List.of(
                ReqPart.ofFunctionResponse(functionCall.name(), functionResult))));
        return call(contents, tools);
    }

    private List<ReqContent> buildHistoryContents(List<HistoryTurn> history) {
        List<ReqContent> contents = new ArrayList<>();
        for (HistoryTurn turn : history) {
            contents.add(new ReqContent(turn.role(), List.of(ReqPart.ofText(turn.text()))));
        }
        return contents;
    }

    private ChatResult call(List<ReqContent> contents, List<ToolDeclaration> tools) {
        try {
            List<ReqFunctionDeclaration> declarations = tools.stream()
                    .map(t -> new ReqFunctionDeclaration(
                            t.name(), t.description(),
                            new ReqParameters("object", t.parameters().entrySet().stream()
                                    .collect(Collectors.toMap(
                                            Map.Entry::getKey,
                                            e -> new ReqPropertySchema(e.getValue().type(), e.getValue().description()))))))
                    .toList();
            ChatRequest request = new ChatRequest(contents, List.of(new ReqTool(declarations)));

            ChatResponse response = restClient.post()
                    .uri("/v1beta/models/{model}:generateContent", MODEL)
                    .header("x-goog-api-key", apiKey)
                    .body(request)
                    .retrieve()
                    .body(ChatResponse.class);

            if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
                return new ChatResult(null, null);
            }
            List<RespPart> parts = response.candidates().get(0).content().parts();
            if (parts == null || parts.isEmpty()) {
                return new ChatResult(null, null);
            }
            RespPart part = parts.get(0);
            if (part.functionCall() != null) {
                return new ChatResult(
                        new FunctionCall(part.functionCall().name(), part.functionCall().args(), part.thoughtSignature()),
                        null);
            }
            return new ChatResult(null, part.text());
        } catch (Exception e) {
            log.warn("Gemini 대화 호출 실패", e);
            return new ChatResult(null, null);
        }
    }
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.conversation.GeminiChatClientImplTest"`
Expected: PASS — 4개 테스트 전부 통과.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/trova/backend/conversation/GeminiChatClient.java \
        src/main/java/com/trova/backend/conversation/GeminiChatClientImpl.java \
        src/test/java/com/trova/backend/conversation/GeminiChatClientImplTest.java
git commit -m "feat: Gemini function calling 대화 클라이언트 추가"
```

---

### Task 2: ConversationState / ConversationSessionStore — 세션 인메모리 저장

**Files:**
- Create: `src/main/java/com/trova/backend/conversation/ConversationState.java`
- Create: `src/main/java/com/trova/backend/conversation/ConversationSessionStore.java`
- Test: `src/test/java/com/trova/backend/conversation/ConversationSessionStoreTest.java`

**Interfaces:**
- Consumes: `GeminiChatClient.HistoryTurn`(Task 1)
- Produces:
  - `ConversationState.ROLE_USER`, `ConversationState.ROLE_MODEL` (String 상수)
  - `ConversationState(Long userId, Long tripId, Long tripPlaceId, Integer day, Long gapBeforePlaceId)`
  - `state.getUserId()/getTripId()/getTripPlaceId()/getDay()/getGapBeforePlaceId()/getHistory()/getShownCandidateIds()/getTurnCount()/getLastActivity()`
  - `state.appendTurn(String role, String text)`, `state.incrementTurnCount()`, `state.addShownCandidateIds(List<Long>)`
  - `ConversationSessionStore.create(String sessionId, Long userId, Long tripId, Long tripPlaceId, Integer day, Long gapBeforePlaceId)` → `ConversationState`
  - `ConversationSessionStore.get(String sessionId)` → `ConversationState` (없으면 null)
  - `ConversationSessionStore.remove(String sessionId)`
  - `ConversationSessionStore.evictExpiredSessions()` (스케줄러가 호출, 테스트에서도 직접 호출 가능)

- [ ] **Step 1: 실패 테스트부터 작성**

`src/test/java/com/trova/backend/conversation/ConversationSessionStoreTest.java`:

```java
package com.trova.backend.conversation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationSessionStoreTest {

    @Test
    void create로_만든_세션을_get으로_다시_찾는다() {
        ConversationSessionStore store = new ConversationSessionStore();
        ConversationState created = store.create("s1", 1L, 10L, 100L, null, null);

        ConversationState found = store.get("s1");

        assertThat(found).isSameAs(created);
        assertThat(found.getUserId()).isEqualTo(1L);
        assertThat(found.getTripId()).isEqualTo(10L);
        assertThat(found.getTripPlaceId()).isEqualTo(100L);
    }

    @Test
    void 없는_세션은_null을_반환한다() {
        ConversationSessionStore store = new ConversationSessionStore();
        assertThat(store.get("없음")).isNull();
    }

    @Test
    void remove하면_다시_get했을때_null이다() {
        ConversationSessionStore store = new ConversationSessionStore();
        store.create("s1", 1L, 10L, 100L, null, null);
        store.remove("s1");
        assertThat(store.get("s1")).isNull();
    }

    @Test
    void appendTurn하면_history와_lastActivity가_갱신된다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        Instant before = state.getLastActivity();

        state.appendTurn(ConversationState.ROLE_USER, "조용한 카페 찾아줘");
        state.appendTurn(ConversationState.ROLE_MODEL, "커피한약방을 추천해요");

        assertThat(state.getHistory()).hasSize(2);
        assertThat(state.getHistory().get(0).role()).isEqualTo("user");
        assertThat(state.getHistory().get(1).text()).isEqualTo("커피한약방을 추천해요");
        assertThat(state.getLastActivity()).isAfterOrEqualTo(before);
    }

    @Test
    void addShownCandidateIds로_추가한_id는_contains로_확인된다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        state.addShownCandidateIds(List.of(5L, 6L));

        assertThat(state.getShownCandidateIds()).contains(5L, 6L);
        assertThat(state.getShownCandidateIds()).doesNotContain(7L);
    }

    @Test
    void 만료된_세션은_evictExpiredSessions로_정리된다() {
        ConversationSessionStore store = new ConversationSessionStore();
        ConversationState state = store.create("old", 1L, 10L, 100L, null, null);
        // lastActivity를 리플렉션으로 과거로 되돌려 TTL 만료 상태를 재현한다.
        try {
            var field = ConversationState.class.getDeclaredField("lastActivity");
            field.setAccessible(true);
            field.set(state, Instant.now().minus(31, ChronoUnit.MINUTES));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        store.create("fresh", 1L, 10L, 200L, null, null);

        store.evictExpiredSessions();

        assertThat(store.get("old")).isNull();
        assertThat(store.get("fresh")).isNotNull();
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.conversation.ConversationSessionStoreTest"`
Expected: FAIL — `ConversationState`/`ConversationSessionStore` 클래스가 없음.

- [ ] **Step 3: 구현**

`src/main/java/com/trova/backend/conversation/ConversationState.java`:

```java
package com.trova.backend.conversation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 대화 세션 하나의 상태. DB에 저장하지 않는다 — "세션 한정" 설계 원칙(스펙 참고).
 * tripPlaceId 또는 (day+gapBeforePlaceId) 중 정확히 하나만 채워진다.
 */
public class ConversationState {

    public static final String ROLE_USER = "user";
    public static final String ROLE_MODEL = "model";

    private final Long userId;
    private final Long tripId;
    private final Long tripPlaceId;
    private final Integer day;
    private final Long gapBeforePlaceId;
    private final List<GeminiChatClient.HistoryTurn> history = new ArrayList<>();
    private final Set<Long> shownCandidateIds = new HashSet<>();
    private int turnCount = 0;
    private volatile Instant lastActivity = Instant.now();

    public ConversationState(Long userId, Long tripId, Long tripPlaceId, Integer day, Long gapBeforePlaceId) {
        this.userId = userId;
        this.tripId = tripId;
        this.tripPlaceId = tripPlaceId;
        this.day = day;
        this.gapBeforePlaceId = gapBeforePlaceId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getTripId() {
        return tripId;
    }

    public Long getTripPlaceId() {
        return tripPlaceId;
    }

    public Integer getDay() {
        return day;
    }

    public Long getGapBeforePlaceId() {
        return gapBeforePlaceId;
    }

    public List<GeminiChatClient.HistoryTurn> getHistory() {
        return history;
    }

    public Set<Long> getShownCandidateIds() {
        return shownCandidateIds;
    }

    public int getTurnCount() {
        return turnCount;
    }

    public Instant getLastActivity() {
        return lastActivity;
    }

    public void appendTurn(String role, String text) {
        history.add(new GeminiChatClient.HistoryTurn(role, text));
        lastActivity = Instant.now();
    }

    public void incrementTurnCount() {
        turnCount++;
    }

    public void addShownCandidateIds(List<Long> placeIds) {
        shownCandidateIds.addAll(placeIds);
    }
}
```

`src/main/java/com/trova/backend/conversation/ConversationSessionStore.java`:

```java
package com.trova.backend.conversation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 대화 세션을 서버 인메모리에만 보관한다(DB 없음). 다중 인스턴스 배포(k3s 다중
 * replica) 시에는 sticky session이 필요하다 — 지금은 단일 인스턴스 배포 목표라
 * 문제되지 않지만, 배포 확장 시 재검토가 필요하다(스펙 "컴포넌트 구조" 절).
 */
@Component
public class ConversationSessionStore {

    private static final Duration SESSION_TTL = Duration.ofMinutes(30);

    private final ConcurrentHashMap<String, ConversationState> sessions = new ConcurrentHashMap<>();

    public ConversationState create(
            String sessionId, Long userId, Long tripId, Long tripPlaceId, Integer day, Long gapBeforePlaceId
    ) {
        ConversationState state = new ConversationState(userId, tripId, tripPlaceId, day, gapBeforePlaceId);
        sessions.put(sessionId, state);
        return state;
    }

    public ConversationState get(String sessionId) {
        return sessions.get(sessionId);
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    @Scheduled(fixedRate = 600_000)
    public void evictExpiredSessions() {
        Instant cutoff = Instant.now().minus(SESSION_TTL);
        sessions.entrySet().removeIf(e -> e.getValue().getLastActivity().isBefore(cutoff));
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.conversation.ConversationSessionStoreTest"`
Expected: PASS — 6개 테스트 전부 통과.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/trova/backend/conversation/ConversationState.java \
        src/main/java/com/trova/backend/conversation/ConversationSessionStore.java \
        src/test/java/com/trova/backend/conversation/ConversationSessionStoreTest.java
git commit -m "feat: 대화 세션 인메모리 저장소 추가"
```

---

### Task 3: SignalType.CHAT_LIKED + ConversationToolExecutor

**Files:**
- Modify: `src/main/java/com/trova/backend/entity/SignalType.java`
- Create: `src/main/java/com/trova/backend/conversation/ConversationToolExecutor.java`
- Test: `src/test/java/com/trova/backend/conversation/ConversationToolExecutorTest.java`

**Interfaces:**
- Consumes: `GeminiChatClient.FunctionCall`(Task 1), `ConversationState`(Task 2),
  `AlternativeFinderService.findAlternatives(User, Long, AlternativeFilter)`,
  `GapRecommendationService.findGaps(User, Long, int)` → `Optional<List<Gap>>`,
  `GapRecommendationService.Gap(Long beforePlaceId, Long afterPlaceId, int gapMinutes, List<AlternativeCandidate> recommendations)`,
  `PlaceRepository.findById(Long)`, `UserPreferenceSignalRepository.save(...)`,
  `PlaceEmbeddingService.ensureEmbeddings(List<Place>)`
- Produces:
  - `ConversationToolExecutor.ToolExecutionResult(List<AlternativeCandidate> candidates, Map<String, Object> responseForGemini)`
  - `ConversationToolExecutor.execute(User user, ConversationState state, GeminiChatClient.FunctionCall call)` → `ToolExecutionResult`

- [ ] **Step 1: SignalType에 값 추가**

`src/main/java/com/trova/backend/entity/SignalType.java` 수정:

```java
package com.trova.backend.entity;

/** 개인화 랭킹에 쓰는 사용자 긍정 신호 종류. v1은 전부 동일 가중치로 취급한다. */
public enum SignalType {
    BOOKMARK,
    TRIP_PLACE_ADDED,
    ALTERNATIVE_REPLACED,
    GAP_INSERTED,
    VIDEO_PLACE_MATCHED,
    // 대화형 비서(Phase 2)에서 특정 후보를 마음에 들어한다고 말했을 때 기록.
    CHAT_LIKED
}
```

- [ ] **Step 2: 실패 테스트부터 작성**

`src/test/java/com/trova/backend/conversation/ConversationToolExecutorTest.java`:

```java
package com.trova.backend.conversation;

import com.trova.backend.entity.Place;
import com.trova.backend.entity.SignalType;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.recommendation.AlternativeFilter;
import com.trova.backend.recommendation.AlternativeFinderService;
import com.trova.backend.recommendation.GapRecommendationService;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.UserPreferenceSignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationToolExecutorTest {

    @Mock private AlternativeFinderService alternativeFinderService;
    @Mock private GapRecommendationService gapRecommendationService;
    @Mock private PlaceRepository placeRepository;
    @Mock private UserPreferenceSignalRepository userPreferenceSignalRepository;
    @Mock private PlaceEmbeddingService placeEmbeddingService;

    private ConversationToolExecutor executor;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        executor = new ConversationToolExecutor(
                alternativeFinderService, gapRecommendationService, placeRepository,
                userPreferenceSignalRepository, placeEmbeddingService);
        user = new User("google", "u1", "테스트유저", null);
        setId(user, 1L);
    }

    private void setId(Object entity, Long id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private AlternativeCandidate candidate(Long placeId, String name) {
        return new AlternativeCandidate(placeId, "g-" + placeId, name, "cafe", 4.5, 100,
                37.5, 127.0, "서울", null, null, false, null, null);
    }

    @Test
    void find_alternatives는_세션의_tripPlaceId로_AlternativeFinderService를_호출한다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        List<AlternativeCandidate> candidates = List.of(candidate(5L, "커피한약방"));
        when(alternativeFinderService.findAlternatives(eq(user), eq(100L), any(AlternativeFilter.class)))
                .thenReturn(Optional.of(candidates));

        var call = new GeminiChatClient.FunctionCall(
                "find_alternatives", Map.of("category", "카페", "indoor", true), "sig");
        ConversationToolExecutor.ToolExecutionResult result = executor.execute(user, state, call);

        ArgumentCaptor<AlternativeFilter> filterCaptor = ArgumentCaptor.forClass(AlternativeFilter.class);
        verify(alternativeFinderService).findAlternatives(eq(user), eq(100L), filterCaptor.capture());
        assertThat(filterCaptor.getValue().category()).isEqualTo("카페");
        assertThat(filterCaptor.getValue().indoorOnly()).isTrue();
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.responseForGemini()).containsKey("candidates");
    }

    @Test
    void get_gap_recommendations는_세션의_gapBeforePlaceId와_일치하는_gap만_반환한다() {
        ConversationState state = new ConversationState(1L, 10L, null, 2, 50L);
        var matchingGap = new GapRecommendationService.Gap(50L, 60L, 40, List.of(candidate(7L, "카페A")));
        var otherGap = new GapRecommendationService.Gap(99L, 100L, 35, List.of(candidate(8L, "카페B")));
        when(gapRecommendationService.findGaps(user, 10L, 2))
                .thenReturn(Optional.of(List.of(otherGap, matchingGap)));

        var call = new GeminiChatClient.FunctionCall("get_gap_recommendations", Map.of(), "sig");
        ConversationToolExecutor.ToolExecutionResult result = executor.execute(user, state, call);

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).placeId()).isEqualTo(7L);
    }

    @Test
    void note_preference는_세션에_이미_보여준_placeId만_허용한다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        state.addShownCandidateIds(List.of(5L));
        Place place = new Place();
        setId(place, 5L);
        when(placeRepository.findById(5L)).thenReturn(Optional.of(place));

        var call = new GeminiChatClient.FunctionCall("note_preference", Map.of("placeId", 5), "sig");
        executor.execute(user, state, call);

        verify(userPreferenceSignalRepository).save(argThat(signal ->
                signal.getSignalType() == SignalType.CHAT_LIKED && signal.getPlace() == place));
        verify(placeEmbeddingService).ensureEmbeddings(List.of(place));
    }

    @Test
    void note_preference는_보여준적_없는_placeId면_거부하고_저장하지_않는다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        // shownCandidateIds가 비어있음 — placeId 9는 이 세션에서 보여준 적 없음.

        var call = new GeminiChatClient.FunctionCall("note_preference", Map.of("placeId", 9), "sig");
        ConversationToolExecutor.ToolExecutionResult result = executor.execute(user, state, call);

        verify(userPreferenceSignalRepository, never()).save(any());
        assertThat(result.responseForGemini()).containsKey("error");
    }

    @Test
    void 알수없는_도구_이름이면_에러_응답을_돌려준다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        var call = new GeminiChatClient.FunctionCall("delete_everything", Map.of(), "sig");

        ConversationToolExecutor.ToolExecutionResult result = executor.execute(user, state, call);

        assertThat(result.responseForGemini()).containsKey("error");
        assertThat(result.candidates()).isNull();
    }

    @Test
    void 도구_실행_중_예외가_나면_500대신_에러_응답으로_폴백한다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        when(alternativeFinderService.findAlternatives(any(), anyLong(), any()))
                .thenThrow(new RuntimeException("DB 오류"));

        var call = new GeminiChatClient.FunctionCall("find_alternatives", Map.of(), "sig");
        ConversationToolExecutor.ToolExecutionResult result = executor.execute(user, state, call);

        assertThat(result.responseForGemini()).containsKey("error");
    }
}
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.conversation.ConversationToolExecutorTest"`
Expected: FAIL — `ConversationToolExecutor` 클래스가 없음.

- [ ] **Step 4: 구현**

`src/main/java/com/trova/backend/conversation/ConversationToolExecutor.java`:

```java
package com.trova.backend.conversation;

import com.trova.backend.entity.SignalType;
import com.trova.backend.entity.User;
import com.trova.backend.entity.UserPreferenceSignal;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.recommendation.AlternativeFilter;
import com.trova.backend.recommendation.AlternativeFinderService;
import com.trova.backend.recommendation.GapRecommendationService;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.UserPreferenceSignalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini의 functionCall을 실제 기존 서비스 호출로 매핑하는 얇은 어댑터. 새 추천
 * 로직은 없다 — find_alternatives/get_gap_recommendations는 조회만, note_preference만
 * 좁게 범위를 제한한 쓰기 예외다(스펙 "쓰기 도구 예외" 절).
 */
@Component
public class ConversationToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ConversationToolExecutor.class);

    private final AlternativeFinderService alternativeFinderService;
    private final GapRecommendationService gapRecommendationService;
    private final PlaceRepository placeRepository;
    private final UserPreferenceSignalRepository userPreferenceSignalRepository;
    private final PlaceEmbeddingService placeEmbeddingService;

    public ConversationToolExecutor(
            AlternativeFinderService alternativeFinderService,
            GapRecommendationService gapRecommendationService,
            PlaceRepository placeRepository,
            UserPreferenceSignalRepository userPreferenceSignalRepository,
            PlaceEmbeddingService placeEmbeddingService
    ) {
        this.alternativeFinderService = alternativeFinderService;
        this.gapRecommendationService = gapRecommendationService;
        this.placeRepository = placeRepository;
        this.userPreferenceSignalRepository = userPreferenceSignalRepository;
        this.placeEmbeddingService = placeEmbeddingService;
    }

    /** candidates는 find_alternatives/get_gap_recommendations일 때만 채워진다(앱에 카드로 보여줄 용도). */
    public record ToolExecutionResult(List<AlternativeCandidate> candidates, Map<String, Object> responseForGemini) {
    }

    public ToolExecutionResult execute(User user, ConversationState state, GeminiChatClient.FunctionCall call) {
        try {
            return switch (call.name()) {
                case "find_alternatives" -> executeFindAlternatives(user, state, call.args());
                case "get_gap_recommendations" -> executeGetGapRecommendations(user, state);
                case "note_preference" -> executeNotePreference(user, state, call.args());
                default -> new ToolExecutionResult(null, Map.of("error", "알 수 없는 도구: " + call.name()));
            };
        } catch (Exception e) {
            log.warn("대화형 비서 도구 실행 실패: {}", call.name(), e);
            return new ToolExecutionResult(null, Map.of("error", "지금 조회할 수 없어요"));
        }
    }

    private ToolExecutionResult executeFindAlternatives(User user, ConversationState state, Map<String, Object> args) {
        if (state.getTripPlaceId() == null) {
            return new ToolExecutionResult(null, Map.of("error", "이 대화는 대안 찾기 대상이 아니에요"));
        }
        String category = (String) args.get("category");
        Boolean indoor = (Boolean) args.get("indoor");
        AlternativeFilter filter = new AlternativeFilter(category, indoor, null, null, null);
        List<AlternativeCandidate> candidates = alternativeFinderService
                .findAlternatives(user, state.getTripPlaceId(), filter)
                .orElse(List.of());
        return new ToolExecutionResult(candidates, toGeminiResponse(candidates));
    }

    private ToolExecutionResult executeGetGapRecommendations(User user, ConversationState state) {
        if (state.getDay() == null || state.getGapBeforePlaceId() == null) {
            return new ToolExecutionResult(null, Map.of("error", "이 대화는 빈 시간 추천 대상이 아니에요"));
        }
        List<AlternativeCandidate> candidates = gapRecommendationService
                .findGaps(user, state.getTripId(), state.getDay())
                .orElse(List.of())
                .stream()
                .filter(gap -> gap.beforePlaceId().equals(state.getGapBeforePlaceId()))
                .findFirst()
                .map(GapRecommendationService.Gap::recommendations)
                .orElse(List.of());
        return new ToolExecutionResult(candidates, toGeminiResponse(candidates));
    }

    private ToolExecutionResult executeNotePreference(User user, ConversationState state, Map<String, Object> args) {
        Object rawPlaceId = args.get("placeId");
        if (rawPlaceId == null) {
            return new ToolExecutionResult(null, Map.of("error", "placeId가 필요해요"));
        }
        Long placeId = ((Number) rawPlaceId).longValue();
        // Gemini가 이 세션에서 보여준 적 없는 placeId를 지어내 기록하는 것을 막는다.
        if (!state.getShownCandidateIds().contains(placeId)) {
            return new ToolExecutionResult(null, Map.of("error", "아직 보여주지 않은 장소예요"));
        }
        return placeRepository.findById(placeId)
                .map(place -> {
                    userPreferenceSignalRepository.save(new UserPreferenceSignal(user, place, SignalType.CHAT_LIKED));
                    placeEmbeddingService.ensureEmbeddings(List.of(place));
                    return new ToolExecutionResult(null, Map.of("acknowledged", true));
                })
                .orElseGet(() -> new ToolExecutionResult(null, Map.of("error", "장소를 찾을 수 없어요")));
    }

    private Map<String, Object> toGeminiResponse(List<AlternativeCandidate> candidates) {
        List<Map<String, Object>> summarized = candidates.stream()
                .map(c -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("placeId", c.placeId());
                    m.put("name", c.name());
                    m.put("category", c.category());
                    m.put("rating", c.rating());
                    m.put("userRatingCount", c.userRatingCount());
                    return m;
                })
                .toList();
        return Map.of("candidates", summarized);
    }
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.conversation.ConversationToolExecutorTest"`
Expected: PASS — 6개 테스트 전부 통과.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/trova/backend/entity/SignalType.java \
        src/main/java/com/trova/backend/conversation/ConversationToolExecutor.java \
        src/test/java/com/trova/backend/conversation/ConversationToolExecutorTest.java
git commit -m "feat: 대화형 비서 도구 실행기(ConversationToolExecutor) 추가"
```

---

### Task 4: ConversationService — 턴 오케스트레이션

**Files:**
- Create: `src/main/java/com/trova/backend/conversation/ConversationService.java`
- Test: `src/test/java/com/trova/backend/conversation/ConversationServiceTest.java`

**Interfaces:**
- Consumes: `GeminiChatClient`(Task 1), `ConversationState`(Task 2),
  `ConversationToolExecutor`(Task 3), `ApiCallLogService.record(String, String, Long,
  long, boolean, String, Integer, Integer, Integer)`
- Produces:
  - `ConversationService.TurnResult(String reply, List<AlternativeCandidate> candidates, int turnCount, boolean turnLimitReached)`
  - `ConversationService.sendMessage(User user, ConversationState state, String message)` → `TurnResult`

- [ ] **Step 1: 실패 테스트부터 작성**

`src/test/java/com/trova/backend/conversation/ConversationServiceTest.java`:

```java
package com.trova.backend.conversation;

import com.trova.backend.entity.User;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock private GeminiChatClient geminiChatClient;
    @Mock private ConversationToolExecutor toolExecutor;
    @Mock private ApiCallLogService apiCallLogService;

    private ConversationService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new ConversationService(geminiChatClient, toolExecutor, apiCallLogService);
        user = new User("google", "u1", "테스트유저", null);
    }

    private AlternativeCandidate candidate(Long placeId) {
        return new AlternativeCandidate(placeId, "g-" + placeId, "카페", "cafe", 4.5, 100,
                37.5, 127.0, "서울", null, null, false, null, null);
    }

    @Test
    void 도구_호출_없이_텍스트만_오면_그대로_답변한다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        when(geminiChatClient.sendMessage(any(), eq("안녕"), any()))
                .thenReturn(new GeminiChatClient.ChatResult(null, "안녕하세요! 뭘 도와드릴까요?"));

        ConversationService.TurnResult result = service.sendMessage(user, state, "안녕");

        assertThat(result.reply()).isEqualTo("안녕하세요! 뭘 도와드릴까요?");
        assertThat(result.candidates()).isNull();
        assertThat(result.turnCount()).isEqualTo(1);
        assertThat(result.turnLimitReached()).isFalse();
        assertThat(state.getHistory()).hasSize(2);
        verify(toolExecutor, never()).execute(any(), any(), any());
    }

    @Test
    void functionCall이_오면_도구를_실행하고_결과를_반영해_재호출한다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        var functionCall = new GeminiChatClient.FunctionCall("find_alternatives", Map.of("category", "카페"), "sig");
        when(geminiChatClient.sendMessage(any(), eq("조용한 카페 찾아줘"), any()))
                .thenReturn(new GeminiChatClient.ChatResult(functionCall, null));

        List<AlternativeCandidate> candidates = List.of(candidate(5L));
        var toolResult = new ConversationToolExecutor.ToolExecutionResult(candidates, Map.of("candidates", List.of()));
        when(toolExecutor.execute(user, state, functionCall)).thenReturn(toolResult);

        when(geminiChatClient.sendFunctionResult(any(), eq("조용한 카페 찾아줘"), eq(functionCall), any(), any()))
                .thenReturn(new GeminiChatClient.ChatResult(null, "커피한약방을 추천해요"));

        ConversationService.TurnResult result = service.sendMessage(user, state, "조용한 카페 찾아줘");

        assertThat(result.reply()).isEqualTo("커피한약방을 추천해요");
        assertThat(result.candidates()).isEqualTo(candidates);
        assertThat(state.getShownCandidateIds()).contains(5L);
    }

    @Test
    void 턴_상한에_도달하면_Gemini를_호출하지_않는다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        for (int i = 0; i < 10; i++) {
            state.incrementTurnCount();
        }

        ConversationService.TurnResult result = service.sendMessage(user, state, "한번더");

        assertThat(result.turnLimitReached()).isTrue();
        verify(geminiChatClient, never()).sendMessage(any(), any(), any());
    }

    @Test
    void Gemini_호출_실패하면_폴백_문구로_응답한다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        when(geminiChatClient.sendMessage(any(), anyString(), any()))
                .thenReturn(new GeminiChatClient.ChatResult(null, null));

        ConversationService.TurnResult result = service.sendMessage(user, state, "안녕");

        assertThat(result.reply()).contains("다시 시도");
        assertThat(result.turnLimitReached()).isFalse();
    }

    @Test
    void 매턴마다_ApiCallLogService에_conversation_turn으로_기록한다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        when(geminiChatClient.sendMessage(any(), anyString(), any()))
                .thenReturn(new GeminiChatClient.ChatResult(null, "답변"));

        service.sendMessage(user, state, "안녕");

        verify(apiCallLogService).record(
                eq("gemini"), eq("conversation-turn"), eq(null), anyLong(), anyBoolean(), any(), any(), any(), any());
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.conversation.ConversationServiceTest"`
Expected: FAIL — `ConversationService` 클래스가 없음.

- [ ] **Step 3: 구현**

`src/main/java/com/trova/backend/conversation/ConversationService.java`:

```java
package com.trova.backend.conversation;

import com.trova.backend.entity.User;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.service.ApiCallLogService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ConversationService {

    private static final int MAX_TURNS = 10;
    private static final String FALLBACK_REPLY = "지금 답변을 가져오지 못했어요, 다시 시도해주세요.";

    private final GeminiChatClient geminiChatClient;
    private final ConversationToolExecutor toolExecutor;
    private final ApiCallLogService apiCallLogService;

    public ConversationService(
            GeminiChatClient geminiChatClient,
            ConversationToolExecutor toolExecutor,
            ApiCallLogService apiCallLogService
    ) {
        this.geminiChatClient = geminiChatClient;
        this.toolExecutor = toolExecutor;
        this.apiCallLogService = apiCallLogService;
    }

    public record TurnResult(String reply, List<AlternativeCandidate> candidates, int turnCount, boolean turnLimitReached) {
    }

    public TurnResult sendMessage(User user, ConversationState state, String message) {
        if (state.getTurnCount() >= MAX_TURNS) {
            return new TurnResult(null, null, state.getTurnCount(), true);
        }

        List<GeminiChatClient.ToolDeclaration> tools = toolsFor(state);
        long start = System.currentTimeMillis();
        GeminiChatClient.ChatResult first = geminiChatClient.sendMessage(state.getHistory(), message, tools);

        String reply;
        List<AlternativeCandidate> candidates = null;

        if (first.functionCall() != null) {
            ConversationToolExecutor.ToolExecutionResult toolResult = toolExecutor.execute(user, state, first.functionCall());
            GeminiChatClient.ChatResult second = geminiChatClient.sendFunctionResult(
                    state.getHistory(), message, first.functionCall(), toolResult.responseForGemini(), tools);
            boolean success = second.text() != null;
            apiCallLogService.record(
                    "gemini", "conversation-turn", null, System.currentTimeMillis() - start,
                    success, success ? null : "generation failed", null, null, null);
            if (success) {
                reply = second.text();
                candidates = toolResult.candidates();
            } else {
                reply = FALLBACK_REPLY;
            }
        } else if (first.text() != null) {
            apiCallLogService.record(
                    "gemini", "conversation-turn", null, System.currentTimeMillis() - start,
                    true, null, null, null, null);
            reply = first.text();
        } else {
            apiCallLogService.record(
                    "gemini", "conversation-turn", null, System.currentTimeMillis() - start,
                    false, "generation failed", null, null, null);
            reply = FALLBACK_REPLY;
        }

        state.appendTurn(ConversationState.ROLE_USER, message);
        state.appendTurn(ConversationState.ROLE_MODEL, reply);
        state.incrementTurnCount();
        if (candidates != null) {
            state.addShownCandidateIds(candidates.stream().map(AlternativeCandidate::placeId).toList());
        }

        return new TurnResult(reply, candidates, state.getTurnCount(), false);
    }

    // 세션이 tripPlaceId(대안 찾기) 또는 day+gapBeforePlaceId(빈 시간 추천) 중 정확히
    // 하나로 시작되므로(ConversationController가 보장), 그에 맞는 조회 도구 하나만
    // 노출한다 — 관계없는 도구를 노출해 Gemini가 잘못된 컨텍스트로 호출할 여지를 없앤다.
    private List<GeminiChatClient.ToolDeclaration> toolsFor(ConversationState state) {
        GeminiChatClient.ToolDeclaration notePreference = new GeminiChatClient.ToolDeclaration(
                "note_preference",
                "사용자가 방금 대화에서 실제로 보여준 특정 후보 장소를 마음에 들어한다고 말하면 호출한다.",
                Map.of("placeId", new GeminiChatClient.ParamSchema("integer", "마음에 들어한 장소의 placeId")));

        if (state.getTripPlaceId() != null) {
            GeminiChatClient.ToolDeclaration findAlternatives = new GeminiChatClient.ToolDeclaration(
                    "find_alternatives",
                    "지금 보고 있는 여행 장소 근처의 대안 후보를 찾는다. 카테고리나 실내 여부로 필터링할 수 있다.",
                    Map.of(
                            "category", new GeminiChatClient.ParamSchema("string", "찾고 싶은 카테고리, 예: 카페"),
                            "indoor", new GeminiChatClient.ParamSchema("boolean", "실내 장소만 찾을지 여부")));
            return List.of(findAlternatives, notePreference);
        }
        GeminiChatClient.ToolDeclaration getGapRecommendations = new GeminiChatClient.ToolDeclaration(
                "get_gap_recommendations",
                "일정 중 비어있는 시간에 갈 만한 근처 장소를 찾는다. 인자가 필요 없다.",
                Map.of());
        return List.of(getGapRecommendations, notePreference);
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.conversation.ConversationServiceTest"`
Expected: PASS — 5개 테스트 전부 통과.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/trova/backend/conversation/ConversationService.java \
        src/test/java/com/trova/backend/conversation/ConversationServiceTest.java
git commit -m "feat: 대화형 비서 턴 오케스트레이션(ConversationService) 추가"
```

---

### Task 5: ConversationController — API 엔드포인트

**Files:**
- Create: `src/main/java/com/trova/backend/controller/ConversationController.java`
- Test: `src/test/java/com/trova/backend/controller/ConversationControllerTest.java`

**Interfaces:**
- Consumes: `ConversationService.sendMessage(User, ConversationState, String)`(Task 4),
  `ConversationSessionStore.get/create/remove`(Task 2), `CurrentUserService.resolve(Authentication)`,
  `TripPlaceRepository.findById(Long)`, `TripRepository.findById(Long)`,
  `ItineraryRepository.findByTripAndDay(Trip, int)`
- Produces: `POST /api/conversations/{sessionId}/messages`,
  `DELETE /api/conversations/{sessionId}`

- [ ] **Step 1: 실패 테스트부터 작성**

`src/test/java/com/trova/backend/controller/ConversationControllerTest.java`:

```java
package com.trova.backend.controller;

import com.trova.backend.conversation.GeminiChatClient;
import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.GooglePlacesApiClient;
import com.trova.backend.recommendation.PersonalizationService;
import com.trova.backend.recommendation.PlaceEmbeddingService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ConversationControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TripRepository tripRepository;
    @Autowired private ItineraryRepository itineraryRepository;
    @Autowired private TripPlaceRepository tripPlaceRepository;

    // embedding 컬럼은 pgvector 타입이라 H2 테스트 DB 스키마에 없다 — 다른 컨트롤러
    // 테스트(TripControllerTest)와 같은 이유로 목 처리한다.
    @MockitoBean private PlaceEmbeddingService placeEmbeddingService;
    @MockitoBean private PersonalizationService personalizationService;
    @MockitoBean private GooglePlacesApiClient googlePlacesApiClient;
    // Gemini 실호출은 테스트 스위트에서 하지 않는다(무료 티어 한도 보존 + 결정성).
    @MockitoBean private GeminiChatClient geminiChatClient;

    private User me;
    private Trip trip;
    private TripPlace place;

    @BeforeEach
    void setUp() {
        me = userRepository.save(new User("google", "conv1", "대화유저", null));
        LocalDate startDate = LocalDate.of(2026, 1, 1);
        trip = tripRepository.save(new Trip(me, "테스트 여행", startDate, startDate));
        Itinerary itinerary = itineraryRepository.save(new Itinerary(trip, 1, startDate));
        place = tripPlaceRepository.save(new TripPlace(
                itinerary, "경복궁", "서울", "landmark", 37.58, 126.97, null, null, 1, PlaceSource.NORMAL, null));
    }

    private ClientRegistration googleRegistration() {
        return ClientRegistration.withRegistrationId("google")
                .clientId("test-client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .build();
    }

    private RequestPostProcessor loginAs(String sub, String name) {
        return oauth2Login()
                .clientRegistration(googleRegistration())
                .attributes(attrs -> {
                    attrs.put("sub", sub);
                    attrs.put("name", name);
                    attrs.put("picture", "https://example.com/p.jpg");
                });
    }

    @Test
    void 첫_메시지로_세션이_생성되고_텍스트만_오면_답변만_온다() throws Exception {
        when(geminiChatClient.sendMessage(any(), any(), any()))
                .thenReturn(new GeminiChatClient.ChatResult(null, "안녕하세요! 뭘 도와드릴까요?"));

        mockMvc.perform(post("/api/conversations/s1/messages")
                        .with(loginAs("conv1", "대화유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"안녕\",\"tripId\":%d,\"tripPlaceId\":%d}"
                                .formatted(trip.getId(), place.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("안녕하세요! 뭘 도와드릴까요?"))
                .andExpect(jsonPath("$.turnCount").value(1))
                .andExpect(jsonPath("$.turnLimitReached").value(false));
    }

    @Test
    void tripPlaceId와_day를_둘다_안주면_400() throws Exception {
        mockMvc.perform(post("/api/conversations/s2/messages")
                        .with(loginAs("conv1", "대화유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"안녕\",\"tripId\":%d}".formatted(trip.getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 소유하지_않은_tripPlaceId면_404() throws Exception {
        User other = userRepository.save(new User("google", "other", "다른유저", null));
        Trip otherTrip = tripRepository.save(new Trip(other, "다른 여행", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1)));
        Itinerary otherItinerary = itineraryRepository.save(new Itinerary(otherTrip, 1, LocalDate.of(2026, 2, 1)));
        TripPlace otherPlace = tripPlaceRepository.save(new TripPlace(
                otherItinerary, "남산타워", "서울", "landmark", 37.55, 126.98, null, null, 1, PlaceSource.NORMAL, null));

        mockMvc.perform(post("/api/conversations/s3/messages")
                        .with(loginAs("conv1", "대화유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"안녕\",\"tripId\":%d,\"tripPlaceId\":%d}"
                                .formatted(otherTrip.getId(), otherPlace.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void 300자_넘는_메시지는_400() throws Exception {
        String longMessage = "가".repeat(301);
        mockMvc.perform(post("/api/conversations/s4/messages")
                        .with(loginAs("conv1", "대화유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"%s\",\"tripId\":%d,\"tripPlaceId\":%d}"
                                .formatted(longMessage, trip.getId(), place.getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 10턴을_넘으면_11번째_요청은_turnLimitReached이다() throws Exception {
        when(geminiChatClient.sendMessage(any(), any(), any()))
                .thenReturn(new GeminiChatClient.ChatResult(null, "답변"));

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/conversations/s5/messages")
                            .with(loginAs("conv1", "대화유저"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"질문%d\",\"tripId\":%d,\"tripPlaceId\":%d}"
                                    .formatted(i, trip.getId(), place.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.turnLimitReached").value(false));
        }

        mockMvc.perform(post("/api/conversations/s5/messages")
                        .with(loginAs("conv1", "대화유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"한번더\",\"tripId\":%d,\"tripPlaceId\":%d}"
                                .formatted(trip.getId(), place.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turnLimitReached").value(true));
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.controller.ConversationControllerTest"`
Expected: FAIL — `ConversationController` 클래스가 없음(404/컴파일 실패).

- [ ] **Step 3: 구현**

`src/main/java/com/trova/backend/controller/ConversationController.java`:

```java
package com.trova.backend.controller;

import com.trova.backend.conversation.ConversationService;
import com.trova.backend.conversation.ConversationSessionStore;
import com.trova.backend.conversation.ConversationState;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class ConversationController {

    private static final int MAX_MESSAGE_LENGTH = 300;

    private final CurrentUserService currentUserService;
    private final ConversationSessionStore sessionStore;
    private final ConversationService conversationService;
    private final TripPlaceRepository tripPlaceRepository;
    private final TripRepository tripRepository;
    private final ItineraryRepository itineraryRepository;

    public ConversationController(
            CurrentUserService currentUserService,
            ConversationSessionStore sessionStore,
            ConversationService conversationService,
            TripPlaceRepository tripPlaceRepository,
            TripRepository tripRepository,
            ItineraryRepository itineraryRepository
    ) {
        this.currentUserService = currentUserService;
        this.sessionStore = sessionStore;
        this.conversationService = conversationService;
        this.tripPlaceRepository = tripPlaceRepository;
        this.tripRepository = tripRepository;
        this.itineraryRepository = itineraryRepository;
    }

    public record ConversationMessageRequest(
            String message, Long tripId, Long tripPlaceId, Integer day, Long gapBeforePlaceId
    ) {
    }

    public record CandidateResponse(
            Long placeId, String googlePlaceId, String name, String category,
            Double rating, Integer userRatingCount, Double latitude, Double longitude, String address,
            Double distanceToNextKm, Integer estimatedTravelMinutes,
            Boolean isCongestionAvailable, String congestionLevel, String recommendationReason
    ) {
        static CandidateResponse from(AlternativeCandidate c) {
            return new CandidateResponse(
                    c.placeId(), c.googlePlaceId(), c.name(), c.category(), c.rating(), c.userRatingCount(),
                    c.latitude(), c.longitude(), c.address(), c.distanceToNextKm(), c.estimatedTravelMinutes(),
                    c.isCongestionAvailable(), c.congestionLevel(), c.recommendationReason());
        }
    }

    public record ConversationMessageResponse(
            String reply, List<CandidateResponse> candidates, int turnCount, boolean turnLimitReached
    ) {
    }

    @PostMapping("/api/conversations/{sessionId}/messages")
    public ResponseEntity<ConversationMessageResponse> sendMessage(
            Authentication authentication, @PathVariable String sessionId,
            @RequestBody ConversationMessageRequest request
    ) {
        User user = currentUserService.resolve(authentication);

        if (request.message() == null || request.message().isBlank()
                || request.message().length() > MAX_MESSAGE_LENGTH) {
            return ResponseEntity.badRequest().build();
        }

        ConversationState state = sessionStore.get(sessionId);
        if (state == null) {
            boolean hasPlaceContext = request.tripPlaceId() != null;
            boolean hasGapContext = request.day() != null && request.gapBeforePlaceId() != null;
            if (hasPlaceContext == hasGapContext) {
                // 세션은 정확히 하나의 컨텍스트(장소 또는 빈 시간 구간)에만 묶인다 —
                // 스펙 "이미 확정된 것" 절.
                return ResponseEntity.badRequest().build();
            }
            state = hasPlaceContext
                    ? createPlaceSession(user, sessionId, request)
                    : createGapSession(user, sessionId, request);
            if (state == null) {
                return ResponseEntity.notFound().build();
            }
        }

        ConversationService.TurnResult result = conversationService.sendMessage(user, state, request.message());
        List<CandidateResponse> candidateResponses = result.candidates() == null
                ? null
                : result.candidates().stream().map(CandidateResponse::from).toList();
        return ResponseEntity.ok(new ConversationMessageResponse(
                result.reply(), candidateResponses, result.turnCount(), result.turnLimitReached()));
    }

    @DeleteMapping("/api/conversations/{sessionId}")
    public ResponseEntity<Void> endSession(Authentication authentication, @PathVariable String sessionId) {
        currentUserService.resolve(authentication);
        sessionStore.remove(sessionId);
        return ResponseEntity.noContent().build();
    }

    private ConversationState createPlaceSession(User user, String sessionId, ConversationMessageRequest request) {
        boolean owned = tripPlaceRepository.findById(request.tripPlaceId())
                .filter(p -> p.getItinerary().getTrip().getUser().getId().equals(user.getId()))
                .isPresent();
        return owned
                ? sessionStore.create(sessionId, user.getId(), request.tripId(), request.tripPlaceId(), null, null)
                : null;
    }

    private ConversationState createGapSession(User user, String sessionId, ConversationMessageRequest request) {
        boolean owned = tripRepository.findById(request.tripId())
                .filter(t -> t.getUser().getId().equals(user.getId()))
                .flatMap(t -> itineraryRepository.findByTripAndDay(t, request.day()))
                .isPresent();
        return owned
                ? sessionStore.create(sessionId, user.getId(), request.tripId(), null, request.day(), request.gapBeforePlaceId())
                : null;
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.controller.ConversationControllerTest"`
Expected: PASS — 5개 테스트 전부 통과.

- [ ] **Step 5: 전체 빌드 확인(커밋 전 필수, CLAUDE.md)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — 기존 테스트 스위트를 포함해 전부 통과.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/trova/backend/controller/ConversationController.java \
        src/test/java/com/trova/backend/controller/ConversationControllerTest.java
git commit -m "feat: 대화형 비서 API 엔드포인트(ConversationController) 추가"
```

---

## 이 계획 이후

- 앱(trova-app) 쪽 UI("💬 비서에게 물어보기" 버튼, 채팅 말풍선 + 후보 카드 렌더링,
  `sessionId` 생성/보관, 시트 닫힐 때 `DELETE /api/conversations/{sessionId}` 호출)는
  이 계획 범위 밖 — 별도 계획에서 다룬다(스펙 "이 스펙 이후" 절).
- 각 태스크 완료 후 스펙의 "관측성" 절(로그)이 실제로 남는지는 로컬에서 실제
  대화 한 턴을 호출해보고 `api_call_logs`에 `operation="conversation-turn"` 행이
  쌓이는지 확인하는 수동 검증을 최종 리뷰 단계에서 한 번 거친다(이번 세션에서
  Phase 1 때 했던 것과 동일한 방식).
