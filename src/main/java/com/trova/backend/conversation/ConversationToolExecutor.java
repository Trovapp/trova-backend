package com.trova.backend.conversation;

import com.trova.backend.entity.SignalType;
import com.trova.backend.entity.User;
import com.trova.backend.entity.UserPreferenceSignal;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.recommendation.AlternativeFilter;
import com.trova.backend.recommendation.AlternativeFinderService;
import com.trova.backend.recommendation.GapRecommendationService;
import com.trova.backend.recommendation.PersonalizationService;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.UserPreferenceSignalRepository;
import com.trova.backend.service.ApiCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
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
    // 대화 카드 UI가 너무 길어지지 않게 자른다. rerankByQuerySimilarity로 "이번 요청
    // 문장" 유사도까지 반영해 재정렬한 뒤 이 개수로 자른다.
    private static final int MAX_CANDIDATES_IN_CHAT = 7;
    // 기존 순위(= baseScore+과거 신호 개인화가 이미 반영된 순서, 0~1로 정규화한
    // rankPercentile)와 이번 요청 문장 유사도(0~1)를 같은 스케일로 맞춰 가중합한다.
    // 곱셈 부스트(순위 위치 * (1+유사도*가중치))는 순위 폭이 큰 목록(예: 7개 중 1위와
    // 7위)에서 유사도가 아무리 높아도 상위권을 못 뒤집는 문제가 실측(테스트)에서
    // 드러나서, 정규화된 두 값을 직접 섞는 방식으로 바꿨다. 0.5는 "이번 요청"과
    // "기존 순위(인기도+과거 이력)"를 동등하게 취급한다는 뜻 — 극단적 이상치 하나가
    // 전체를 뒤엎지 않으면서도 실제로 순위를 바꿀 수 있는 수준으로 잡았다.
    private static final double QUERY_BLEND_WEIGHT = 0.5;

    private final AlternativeFinderService alternativeFinderService;
    private final GapRecommendationService gapRecommendationService;
    private final PersonalizationService personalizationService;
    private final PlaceRepository placeRepository;
    private final UserPreferenceSignalRepository userPreferenceSignalRepository;
    private final PlaceEmbeddingService placeEmbeddingService;
    private final ApiCallLogService apiCallLogService;

    public ConversationToolExecutor(
            AlternativeFinderService alternativeFinderService,
            GapRecommendationService gapRecommendationService,
            PersonalizationService personalizationService,
            PlaceRepository placeRepository,
            UserPreferenceSignalRepository userPreferenceSignalRepository,
            PlaceEmbeddingService placeEmbeddingService,
            ApiCallLogService apiCallLogService
    ) {
        this.alternativeFinderService = alternativeFinderService;
        this.gapRecommendationService = gapRecommendationService;
        this.personalizationService = personalizationService;
        this.placeRepository = placeRepository;
        this.userPreferenceSignalRepository = userPreferenceSignalRepository;
        this.placeEmbeddingService = placeEmbeddingService;
        this.apiCallLogService = apiCallLogService;
    }

    /** candidates는 find_alternatives/get_gap_recommendations일 때만 채워진다(앱에 카드로 보여줄 용도). */
    public record ToolExecutionResult(List<AlternativeCandidate> candidates, Map<String, Object> responseForGemini) {
    }

    public ToolExecutionResult execute(User user, ConversationState state, GeminiChatClient.FunctionCall call, String message) {
        long start = System.currentTimeMillis();
        ToolExecutionResult result;
        try {
            result = switch (call.name()) {
                case "find_alternatives" -> executeFindAlternatives(user, state, call.args(), message);
                case "get_gap_recommendations" -> executeGetGapRecommendations(user, state, message);
                case "note_preference" -> executeNotePreference(user, state, call.args());
                default -> new ToolExecutionResult(null, Map.of("error", "알 수 없는 도구: " + call.name()));
            };
        } catch (Exception e) {
            log.warn("대화형 비서 도구 실행 실패: {}", call.name(), e);
            result = new ToolExecutionResult(null, Map.of("error", "지금 조회할 수 없어요"));
        }
        long latencyMs = System.currentTimeMillis() - start;
        boolean success = !result.responseForGemini().containsKey("error");
        apiCallLogService.record(
                "internal", "conversation-tool-" + call.name(), null, latencyMs, success,
                success ? null : String.valueOf(result.responseForGemini().get("error")), null, null, null);
        return result;
    }

    private ToolExecutionResult executeFindAlternatives(User user, ConversationState state, Map<String, Object> args, String message) {
        if (state.getTripPlaceId() == null) {
            return new ToolExecutionResult(null, Map.of("error", "이 대화는 대안 찾기 대상이 아니에요"));
        }
        String category = (String) args.get("category");
        Boolean indoor = (Boolean) args.get("indoor");
        AlternativeFilter filter = new AlternativeFilter(category, indoor, null, null, null);
        List<AlternativeCandidate> candidates = alternativeFinderService
                .findAlternatives(user, state.getTripPlaceId(), filter)
                .orElse(List.of());
        candidates = rerankByQuerySimilarity(message, candidates).stream().limit(MAX_CANDIDATES_IN_CHAT).toList();
        return new ToolExecutionResult(candidates, toGeminiResponse(candidates));
    }

    private ToolExecutionResult executeGetGapRecommendations(User user, ConversationState state, String message) {
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
        candidates = rerankByQuerySimilarity(message, candidates).stream().limit(MAX_CANDIDATES_IN_CHAT).toList();
        return new ToolExecutionResult(candidates, toGeminiResponse(candidates));
    }

    /**
     * 기존 순서(= baseScore + 과거 신호 개인화가 이미 반영된 순위)와 "이번 턴 요청
     * 문장"의 의미적 유사도를 함께 반영해 재정렬한다. AlternativeCandidate에는 원래
     * 점수가 안 담겨 있어서(DTO라 점수 비공개) 순위 위치를 0~1로 정규화한 값
     * (rankPercentile)을 기존 점수의 프록시로 쓰고, 이걸 요청 유사도와 같은 스케일로
     * 가중합한다 — 정확한 원 점수를 재요청하지 않고도 두 신호를 함께 반영할 수 있다.
     */
    private List<AlternativeCandidate> rerankByQuerySimilarity(String message, List<AlternativeCandidate> candidates) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        List<Long> ids = candidates.stream().map(AlternativeCandidate::placeId).toList();
        Map<Long, Double> queryScores = personalizationService.queryScores(message, ids);
        if (queryScores.isEmpty()) {
            // 임베딩/조회 실패 — 재정렬 없이 원래 순서(장기 개인화 기준) 그대로 둔다.
            return candidates;
        }
        int size = candidates.size();
        Map<Long, Double> rankScoreById = new HashMap<>();
        for (int i = 0; i < size; i++) {
            Long placeId = candidates.get(i).placeId();
            double rankPercentile = (double) (size - i) / size;
            double querySimilarity = queryScores.getOrDefault(placeId, 0.0);
            rankScoreById.put(placeId, (1 - QUERY_BLEND_WEIGHT) * rankPercentile + QUERY_BLEND_WEIGHT * querySimilarity);
        }
        return candidates.stream()
                .sorted(Comparator.comparingDouble((AlternativeCandidate c) -> rankScoreById.get(c.placeId())).reversed())
                .toList();
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
