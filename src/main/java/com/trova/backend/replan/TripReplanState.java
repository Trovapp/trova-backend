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

    /**
     * day+visitOrder 순으로 정렬된 여행 내 장소 하나의 좌표/실내외 스냅샷.
     * dayId는 원본 Itinerary의 id — 여러 날짜를 하나의 리스트로 펼쳐서 순회하는
     * TripReplanGraph가 이웃 장소 충돌 판정 시 날짜 경계를 넘는 비교(예: 1일차
     * 마지막 장소와 2일차 첫 장소)를 걸러내는 데 쓴다.
     */
    public record PlaceSnapshot(Long tripPlaceId, Double latitude, Double longitude, String space, Long dayId) {
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
