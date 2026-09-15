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
