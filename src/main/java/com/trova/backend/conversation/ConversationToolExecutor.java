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
