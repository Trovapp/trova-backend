package com.trova.backend.conversation;

import com.trova.backend.entity.User;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.service.ApiCallLogService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 대화형 비서 한 턴을 오케스트레이션한다: 턴 상한 확인 → Gemini 호출 → functionCall이면
 * 도구 실행 후 결과를 반영해 재호출 → 히스토리/턴수/보여준 후보 id 갱신 → 호출 로그 기록.
 *
 * v1은 한 턴에 도구 호출을 하나만 지원한다(체이닝 없음) — 도구 실행 후 Gemini의 두 번째
 * 응답이 또 다른 functionCall이면(=text가 null) 실패로 간주해 폴백 문구로 응답한다.
 */
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
        long firstStart = System.currentTimeMillis();
        GeminiChatClient.ChatResult first = geminiChatClient.sendMessage(state.getHistory(), message, tools);
        long firstLatency = System.currentTimeMillis() - firstStart;

        String reply;
        List<AlternativeCandidate> candidates = null;

        if (first.functionCall() != null) {
            // sendMessage와 sendFunctionResult는 각각 별도의 Gemini generateContent
            // 호출이라 각각 따로 기록한다 — 그 사이의 도구 실행 시간은 어느 쪽
            // 지연시간에도 포함시키지 않는다.
            apiCallLogService.record(
                    "gemini", "conversation-turn", null, firstLatency, true, null, null, null, null);

            ConversationToolExecutor.ToolExecutionResult toolResult = toolExecutor.execute(user, state, first.functionCall(), message);

            long secondStart = System.currentTimeMillis();
            GeminiChatClient.ChatResult second = geminiChatClient.sendFunctionResult(
                    state.getHistory(), message, first.functionCall(), toolResult.responseForGemini(), tools);
            long secondLatency = System.currentTimeMillis() - secondStart;
            boolean success = second.text() != null;
            apiCallLogService.record(
                    "gemini", "conversation-turn", null, secondLatency,
                    success, success ? null : "generation failed", null, null, null);
            if (success) {
                reply = second.text();
                candidates = toolResult.candidates();
            } else {
                reply = FALLBACK_REPLY;
            }
        } else if (first.text() != null) {
            apiCallLogService.record(
                    "gemini", "conversation-turn", null, firstLatency,
                    true, null, null, null, null);
            reply = first.text();
        } else {
            apiCallLogService.record(
                    "gemini", "conversation-turn", null, firstLatency,
                    false, "generation failed", null, null, null);
            reply = FALLBACK_REPLY;
        }

        state.appendTurn(ConversationState.ROLE_USER, message);
        state.appendTurn(ConversationState.ROLE_MODEL, reply);
        if (candidates != null && !candidates.isEmpty()) {
            // functionCall/functionResponse 왕복 자체는 히스토리에 남기지 않으므로,
            // 다음 턴에서 Gemini가 note_preference를 부를 수 있게 placeId를 텍스트
            // 턴으로 요약해 남긴다 — 없으면 "그거 좋다" 같은 후속 발화에서 Gemini가
            // 어떤 placeId를 가리키는지 알 방법이 없다.
            String summary = "표시한 후보: " + candidates.stream()
                    .map(c -> c.placeId() + "=" + c.name())
                    .collect(java.util.stream.Collectors.joining(", "));
            state.appendTurn(ConversationState.ROLE_MODEL, summary);
        }
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
