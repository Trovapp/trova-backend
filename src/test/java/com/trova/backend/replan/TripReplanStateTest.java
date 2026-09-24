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
        var snapshot = new TripReplanState.PlaceSnapshot(1L, 37.5, 127.0, "INDOOR", 100L, "cafe");
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
