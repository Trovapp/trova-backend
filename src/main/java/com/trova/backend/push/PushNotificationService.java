package com.trova.backend.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.PushSubscription;
import com.trova.backend.repository.PushSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 사용자의 모든 기기에 웹 푸시를 보낸다(ADR 0016). 404·410은 사라진 구독이라 지우고,
 * 429·5xx는 무료 푸시 서버의 요청 제한을 지키려 2초 뒤 한 번만 다시 보낸다. 로그에 endpoint·키는 남기지 않는다.
 */
@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Result(int sent, int removed, int failed) { }

    private final VapidKeys keys;
    private final PushSubscriptionRepository repository;
    private final WebPushTransport transport;
    private final Duration retryDelay;

    @Autowired
    public PushNotificationService(VapidKeys keys, PushSubscriptionRepository repository, WebPushTransport transport) {
        this(keys, repository, transport, Duration.ofSeconds(2));
    }

    PushNotificationService(VapidKeys keys, PushSubscriptionRepository repository, WebPushTransport transport,
                            Duration retryDelay) {
        this.keys = keys;
        this.repository = repository;
        this.transport = transport;
        this.retryDelay = retryDelay;
    }

    public Result sendToUser(Long userId, String kind, PushMessage message) {
        if (!keys.isEnabled()) {
            return new Result(0, 0, 0);
        }
        List<PushSubscription> targets = repository.findByUserId(userId);
        if (targets.isEmpty()) {
            return new Result(0, 0, 0);
        }
        byte[] payload = payload(message);
        int sent = 0;
        int removed = 0;
        int failed = 0;
        for (PushSubscription subscription : targets) {
            int status = sendWithRetry(subscription, payload, message.tag());
            if (status >= 200 && status < 300) {
                subscription.markSuccess();
                repository.save(subscription);
                sent++;
            } else if (status == 404 || status == 410) {
                repository.delete(subscription);
                removed++;
            } else {
                failed++;
            }
        }
        log.info("웹 푸시 {} user={} 구독 {}개 → 성공 {}·삭제 {}·실패 {}",
                kind, userId, targets.size(), sent, removed, failed);
        return new Result(sent, removed, failed);
    }

    private int sendWithRetry(PushSubscription subscription, byte[] payload, String topic) {
        int status = sendOnce(subscription, payload, topic);
        if (status == 429 || status >= 500 || status < 0) {
            sleep();
            status = sendOnce(subscription, payload, topic);
        }
        if (!(status >= 200 && status < 300) && status != 404 && status != 410) {
            log.warn("웹 푸시 실패: subscriptionId={} 상태 {}", subscription.getId(), status);
        }
        return status;
    }

    /** 연결 실패 등 예외는 -1(서버 오류처럼 한 번 다시 보낸다). */
    private int sendOnce(PushSubscription subscription, byte[] payload, String topic) {
        try {
            return transport.send(subscription, payload, topic);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            log.warn("웹 푸시 전송 오류: subscriptionId={} {}", subscription.getId(), e.getClass().getSimpleName());
            return -1;
        }
    }

    private void sleep() {
        if (retryDelay.isZero()) {
            return;
        }
        try {
            Thread.sleep(retryDelay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] payload(PushMessage message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("title", message.title());
        body.put("body", message.body());
        body.put("open", message.open());
        body.put("tag", message.tag());
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new IllegalStateException("웹 푸시 내용 만들기 실패", e);
        }
    }
}
