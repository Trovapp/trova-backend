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
