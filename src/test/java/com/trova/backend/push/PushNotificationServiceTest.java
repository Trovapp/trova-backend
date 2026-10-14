package com.trova.backend.push;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.PushSubscription;
import com.trova.backend.repository.PushSubscriptionRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushNotificationServiceTest {

    private final PushSubscriptionRepository repository = mock(PushSubscriptionRepository.class);
    private final VapidKeys keys = mock(VapidKeys.class);
    private final FakeTransport transport = new FakeTransport();
    private final PushNotificationService service =
            new PushNotificationService(keys, repository, transport, Duration.ZERO);
    private final PushMessage message = new PushMessage("제목", "내용", "draft:1", "draft-1");

    /** endpoint마다 돌려줄 상태 코드를 순서대로 둔다. */
    static class FakeTransport implements WebPushTransport {
        final Map<String, Deque<Integer>> codes = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        byte[] lastPayload;

        void answer(String endpoint, Integer... statuses) {
            codes.put(endpoint, new ArrayDeque<>(List.of(statuses)));
        }

        @Override
        public int send(PushSubscription subscription, byte[] payload, String topic) {
            calls.add(subscription.getEndpoint());
            lastPayload = payload;
            return codes.get(subscription.getEndpoint()).poll();
        }
    }

    private PushSubscription sub(String endpoint) {
        return new PushSubscription(7L, endpoint, "pk", "au");
    }

    @Test
    void 키가_없으면_아무것도_보내지_않는다() {
        when(keys.isEnabled()).thenReturn(false);
        PushNotificationService.Result result = service.sendToUser(7L, "draft", message);
        assertThat(result.sent()).isZero();
        verify(repository, never()).findByUserId(7L);
    }

    @Test
    void 성공하면_마지막_성공_시각을_남기고_내용은_JSON이다() throws Exception {
        when(keys.isEnabled()).thenReturn(true);
        PushSubscription a = sub("https://p/a");
        when(repository.findByUserId(7L)).thenReturn(List.of(a));
        transport.answer("https://p/a", 201);

        PushNotificationService.Result result = service.sendToUser(7L, "draft", message);

        assertThat(result.sent()).isEqualTo(1);
        verify(repository).markSuccess(eq(a.getId()), eq(7L), any());
        Map<String, Object> json = new ObjectMapper().readValue(transport.lastPayload, new TypeReference<Map<String, Object>>() { });
        assertThat(json).containsEntry("title", "제목").containsEntry("open", "draft:1").containsEntry("tag", "draft-1");
    }

    @Test
    void 사라진_구독_하나만_지우고_나머지는_계속_보낸다() {
        when(keys.isEnabled()).thenReturn(true);
        PushSubscription gone = sub("https://p/gone");
        PushSubscription ok = sub("https://p/ok");
        when(repository.findByUserId(7L)).thenReturn(List.of(gone, ok));
        transport.answer("https://p/gone", 410);
        transport.answer("https://p/ok", 201);

        PushNotificationService.Result result = service.sendToUser(7L, "draft", message);

        assertThat(result.removed()).isEqualTo(1);
        assertThat(result.sent()).isEqualTo(1);
        verify(repository).deleteByIdAndUserId(gone.getId(), 7L);
    }

    @Test
    void 요청이_많다는_응답이면_한_번만_다시_보낸다() {
        when(keys.isEnabled()).thenReturn(true);
        when(repository.findByUserId(7L)).thenReturn(List.of(sub("https://p/a")));
        transport.answer("https://p/a", 429, 201);

        PushNotificationService.Result result = service.sendToUser(7L, "draft", message);

        assertThat(transport.calls).hasSize(2);
        assertThat(result.sent()).isEqualTo(1);
    }

    @Test
    void 서버_오류가_두_번이면_실패로_세고_구독은_남긴다() {
        when(keys.isEnabled()).thenReturn(true);
        PushSubscription a = sub("https://p/a");
        when(repository.findByUserId(7L)).thenReturn(List.of(a));
        transport.answer("https://p/a", 500, 503);

        PushNotificationService.Result result = service.sendToUser(7L, "draft", message);

        assertThat(transport.calls).hasSize(2);
        assertThat(result.failed()).isEqualTo(1);
        verify(repository, never()).deleteByIdAndUserId(a.getId(), 7L);
    }
}
