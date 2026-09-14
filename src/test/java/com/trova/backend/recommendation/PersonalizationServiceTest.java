package com.trova.backend.recommendation;

import com.trova.backend.embedding.GeminiTextClient;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.User;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.UserPreferenceSignalRepository;
import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PersonalizationServiceTest {

    @Mock private PlaceRepository placeRepository;
    @Mock private UserPreferenceSignalRepository userPreferenceSignalRepository;
    @Mock private GeminiTextClient geminiTextClient;
    @Mock private ApiCallLogService apiCallLogService;
    @InjectMocks private PersonalizationService personalizationService;

    private void setId(Object entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private User user(Long id) {
        User u = new User("google", "u" + id, "테스트", null);
        setId(u, id);
        return u;
    }

    private Place place(Long id) {
        Place p = new Place("gp" + id, "장소" + id, "cafe", 4.0, 5, null, 37.5, 127.0, "서울");
        setId(p, id);
        return p;
    }

    private UserPreferenceSignalRepository.SimilarSignal similarSignal(String name, String category, String mood, double similarity) {
        return new UserPreferenceSignalRepository.SimilarSignal() {
            public String getName() { return name; }
            public String getCategory() { return category; }
            public String getMood() { return mood; }
            public double getSimilarity() { return similarity; }
        };
    }

    @Test
    void 후보에_임베딩이_없으면_0점이다() {
        User user = user(1L);
        Place candidate = place(10L);
        when(placeRepository.findEmbeddingText(10L)).thenReturn(Optional.empty());

        double score = personalizationService.personalizationScore(user, candidate);

        assertThat(score).isEqualTo(0.0);
        verifyNoInteractions(userPreferenceSignalRepository);
    }

    @Test
    void 유사_신호가_없으면_0점이다_콜드스타트() {
        User user = user(1L);
        Place candidate = place(10L);
        when(placeRepository.findEmbeddingText(10L)).thenReturn(Optional.of("[0.1,0.2]"));
        when(userPreferenceSignalRepository.findTopSimilarSignals(1L, "[0.1,0.2]")).thenReturn(List.of());

        double score = personalizationService.personalizationScore(user, candidate);

        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void 유사_신호가_있으면_평균_유사도를_반환한다() {
        User user = user(1L);
        Place candidate = place(10L);
        when(placeRepository.findEmbeddingText(10L)).thenReturn(Optional.of("[0.1,0.2]"));
        when(userPreferenceSignalRepository.findTopSimilarSignals(1L, "[0.1,0.2]")).thenReturn(List.of(
                similarSignal("카페A", "cafe", "차분한", 0.9),
                similarSignal("카페B", "cafe", "차분한", 0.7)
        ));

        double score = personalizationService.personalizationScore(user, candidate);

        assertThat(score).isCloseTo(0.8, within(0.001));
    }

    @Test
    void 콜드스타트면_추천_이유를_생성하지_않는다() {
        User user = user(1L);
        Place candidate = place(10L);
        when(placeRepository.findEmbeddingText(10L)).thenReturn(Optional.of("[0.1,0.2]"));
        when(userPreferenceSignalRepository.findTopSimilarSignals(1L, "[0.1,0.2]")).thenReturn(List.of());

        Optional<String> reason = personalizationService.explainRecommendation(user, candidate);

        assertThat(reason).isEmpty();
        verifyNoInteractions(geminiTextClient);
    }

    @Test
    void 유사_신호_있으면_추천_이유를_생성한다() {
        User user = user(1L);
        Place candidate = place(10L);
        when(placeRepository.findEmbeddingText(10L)).thenReturn(Optional.of("[0.1,0.2]"));
        when(userPreferenceSignalRepository.findTopSimilarSignals(1L, "[0.1,0.2]")).thenReturn(List.of(
                similarSignal("카페A", "cafe", "차분한", 0.9)
        ));
        when(geminiTextClient.generate(anyString())).thenReturn(Optional.of("전에 좋아하신 카페A와 비슷해요"));

        Optional<String> reason = personalizationService.explainRecommendation(user, candidate);

        assertThat(reason).contains("전에 좋아하신 카페A와 비슷해요");
    }

    @Test
    void 생성_실패하면_빈값을_반환한다() {
        User user = user(1L);
        Place candidate = place(10L);
        when(placeRepository.findEmbeddingText(10L)).thenReturn(Optional.of("[0.1,0.2]"));
        when(userPreferenceSignalRepository.findTopSimilarSignals(1L, "[0.1,0.2]")).thenReturn(List.of(
                similarSignal("카페A", "cafe", "차분한", 0.9)
        ));
        when(geminiTextClient.generate(anyString())).thenReturn(Optional.empty());

        Optional<String> reason = personalizationService.explainRecommendation(user, candidate);

        assertThat(reason).isEmpty();
    }

    @Test
    void retrieveAndScore는_점수와_유사신호를_함께_반환한다() {
        User user = user(1L);
        Place candidate = place(10L);
        when(placeRepository.findEmbeddingText(10L)).thenReturn(Optional.of("[0.1,0.2]"));
        var signal = similarSignal("카페A", "cafe", "차분한", 0.9);
        when(userPreferenceSignalRepository.findTopSimilarSignals(1L, "[0.1,0.2]")).thenReturn(List.of(signal));

        PersonalizationService.PersonalizationResult result = personalizationService.retrieveAndScore(user, candidate);

        assertThat(result.score()).isCloseTo(0.9, within(0.001));
        assertThat(result.similarSignals()).containsExactly(signal);
    }

    @Test
    void explainFromSignals는_빈_리스트면_생성을_호출하지_않고_빈값을_반환한다() {
        Place candidate = place(10L);

        Optional<String> reason = personalizationService.explainFromSignals(candidate, List.of());

        assertThat(reason).isEmpty();
        verifyNoInteractions(geminiTextClient);
    }

    @Test
    void explainFromSignals는_신호가_있으면_전달받은_신호로_생성을_호출한다() {
        Place candidate = place(10L);
        var signal = similarSignal("카페A", "cafe", "차분한", 0.9);
        when(geminiTextClient.generate(anyString())).thenReturn(Optional.of("전에 좋아하신 카페A와 비슷해요"));

        Optional<String> reason = personalizationService.explainFromSignals(candidate, List.of(signal));

        assertThat(reason).contains("전에 좋아하신 카페A와 비슷해요");
        // 이 메서드는 similarSignals를 인자로 받으므로 findSimilarSignals가 의존하는
        // placeRepository/userPreferenceSignalRepository를 다시 조회하면 안 된다 —
        // "검색을 두 번 하지 않는다" 원칙 검증.
        verifyNoInteractions(placeRepository);
        verifyNoInteractions(userPreferenceSignalRepository);
    }
}
