package com.trova.backend.push;

import com.trova.backend.entity.PushSubscription;
import com.trova.backend.repository.PushSubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 보내는 동안 구독이 다른 사람에게 옮겨지거나 지워져도 예전 값으로 되돌리지 않는다(#185 최종 리뷰). 실제 DB(H2)로 본다. */
@SpringBootTest
class PushNotificationServiceConcurrencyTest {

    @Autowired
    private PushSubscriptionRepository repository;

    private final VapidKeys keys = mock(VapidKeys.class);

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    void 보내는_동안_같은_기기에_다른_사람이_로그인하면_그_사람_구독으로_남는다() {
        when(keys.isEnabled()).thenReturn(true);
        repository.save(new PushSubscription(100L, "https://fcm.googleapis.com/fcm/send/race", "pk", "au"));
        WebPushTransport transport = (subscription, payload, topic) -> {
            PushSubscription row = repository.findByEndpoint(subscription.getEndpoint()).orElseThrow();
            row.reassign(200L, "pk2", "au2");
            repository.save(row);
            return 201;
        };
        PushNotificationService service = new PushNotificationService(keys, repository, transport, Duration.ZERO);

        service.sendToUser(100L, "draft", new PushMessage("t", "b", "draft:1", "draft-1"));

        PushSubscription after = repository.findByEndpoint("https://fcm.googleapis.com/fcm/send/race").orElseThrow();
        assertThat(after.getUserId()).isEqualTo(200L);
        assertThat(after.getP256dh()).isEqualTo("pk2");
    }

    @Test
    void 보내는_동안_한_구독이_지워져도_나머지_기기에는_계속_보낸다() {
        when(keys.isEnabled()).thenReturn(true);
        repository.save(new PushSubscription(100L, "https://fcm.googleapis.com/fcm/send/gone", "pk", "au"));
        repository.save(new PushSubscription(100L, "https://fcm.googleapis.com/fcm/send/stay", "pk", "au"));
        List<String> calls = new ArrayList<>();
        WebPushTransport transport = (subscription, payload, topic) -> {
            calls.add(subscription.getEndpoint());
            if (subscription.getEndpoint().endsWith("gone")) {
                repository.deleteByEndpointAndUserId(subscription.getEndpoint(), 100L);
            }
            return 201;
        };
        PushNotificationService service = new PushNotificationService(keys, repository, transport, Duration.ZERO);

        PushNotificationService.Result result =
                service.sendToUser(100L, "draft", new PushMessage("t", "b", "draft:1", "draft-1"));

        assertThat(calls).hasSize(2);
        assertThat(result.sent()).isEqualTo(2);
        assertThat(repository.findByEndpoint("https://fcm.googleapis.com/fcm/send/gone")).isEmpty();
    }
}
