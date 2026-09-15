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
