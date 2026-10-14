package com.trova.backend.push;

import com.trova.backend.entity.ExpoPushToken;
import com.trova.backend.repository.ExpoPushTokenRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExpoPushServiceTest {

    private final ExpoPushTokenRepository repository = mock(ExpoPushTokenRepository.class);
    private final FakeClient client = new FakeClient();
    private final ExpoPushService service = new ExpoPushService(repository, client, Duration.ZERO);
    private final PushMessage message = new PushMessage("제목", "내용", "draft:1", "draft-1");

    static class FakeClient implements ExpoPushClient {
        final Deque<Response> answers = new ArrayDeque<>();
        final List<List<Map<String, Object>>> calls = new ArrayList<>();

        @Override
        public Response send(List<Map<String, Object>> messages) {
            calls.add(messages);
            return answers.poll();
        }
    }

    private static ExpoPushToken token(long id, String value) {
        ExpoPushToken t = new ExpoPushToken(7L, value);
        try {
            Field f = ExpoPushToken.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(t, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return t;
    }

    @Test
    void 토큰이_없으면_요청하지_않는다() {
        when(repository.findByUserId(7L)).thenReturn(List.of());
        assertThat(service.sendToUser(7L, "draft", message).sent()).isZero();
        assertThat(client.calls).isEmpty();
    }

    @Test
    void 모두_성공하면_성공_시각을_남기고_내용에_이동과_채널이_들어간다() {
        when(repository.findByUserId(7L)).thenReturn(List.of(token(1, "ExponentPushToken[a1234567890]")));
        client.answers.add(new ExpoPushClient.Response(200, Arrays.asList((String) null)));

        PushNotificationService.Result result = service.sendToUser(7L, "draft", message);

        assertThat(result.sent()).isEqualTo(1);
        verify(repository).markSuccess(eq(1L), eq(7L), any());
        Map<String, Object> sent = client.calls.get(0).get(0);
        assertThat(sent).containsEntry("to", "ExponentPushToken[a1234567890]").containsEntry("title", "제목")
                .containsEntry("body", "내용").containsEntry("channelId", "trova-default")
                .containsEntry("data", Map.of("open", "draft:1"));
    }

    @Test
    void 등록_해제된_기기_토큰만_지우고_나머지는_성공으로_센다() {
        when(repository.findByUserId(7L)).thenReturn(List.of(token(1, "ExponentPushToken[a1234567890]"),
                token(2, "ExponentPushToken[b1234567890]")));
        client.answers.add(new ExpoPushClient.Response(200, Arrays.asList(null, "DeviceNotRegistered")));

        PushNotificationService.Result result = service.sendToUser(7L, "draft", message);

        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.removed()).isEqualTo(1);
        verify(repository).deleteByIdAndUserId(2L, 7L);
        verify(repository, never()).deleteByIdAndUserId(1L, 7L);
    }

    @Test
    void 요청이_많다는_응답이면_한_번만_다시_보낸다() {
        when(repository.findByUserId(7L)).thenReturn(List.of(token(1, "ExponentPushToken[a1234567890]")));
        client.answers.add(new ExpoPushClient.Response(429, List.of()));
        client.answers.add(new ExpoPushClient.Response(200, Arrays.asList((String) null)));

        PushNotificationService.Result result = service.sendToUser(7L, "draft", message);

        assertThat(client.calls).hasSize(2);
        assertThat(result.sent()).isEqualTo(1);
    }

    @Test
    void 서버_오류가_두_번이면_실패로_세고_토큰은_남긴다() {
        when(repository.findByUserId(7L)).thenReturn(List.of(token(1, "ExponentPushToken[a1234567890]")));
        client.answers.add(new ExpoPushClient.Response(500, List.of()));
        client.answers.add(new ExpoPushClient.Response(503, List.of()));

        PushNotificationService.Result result = service.sendToUser(7L, "draft", message);

        assertThat(client.calls).hasSize(2);
        assertThat(result.failed()).isEqualTo(1);
        verify(repository, never()).deleteByIdAndUserId(anyLong(), anyLong());
    }

    @Test
    void 티켓_수가_모자라면_모자란_토큰은_실패로_센다() {
        when(repository.findByUserId(7L)).thenReturn(List.of(token(1, "ExponentPushToken[a1234567890]"),
                token(2, "ExponentPushToken[b1234567890]")));
        client.answers.add(new ExpoPushClient.Response(200, List.of()));

        PushNotificationService.Result result = service.sendToUser(7L, "draft", message);

        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.sent()).isZero();
    }
}
