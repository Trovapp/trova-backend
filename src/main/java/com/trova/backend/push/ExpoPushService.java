package com.trova.backend.push;

import com.trova.backend.entity.ExpoPushToken;
import com.trova.backend.repository.ExpoPushTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 안드로이드 앱 알림(Expo 푸시, ADR 0017). 사람당 토큰은 10개라 한 번 요청에 다 보낸다.
 * 티켓 오류 DeviceNotRegistered는 앱이 지워졌거나 토큰이 바뀐 것이라 토큰을 지우고, 429·5xx는 2초 뒤 한 번만 다시 보낸다.
 * 로그에 토큰 값은 남기지 않는다. 웹 VAPID 키와 상관없이 동작한다.
 */
@Service
public class ExpoPushService {

    private static final Logger log = LoggerFactory.getLogger(ExpoPushService.class);
    static final String CHANNEL = "trova-default";

    private final ExpoPushTokenRepository repository;
    private final ExpoPushClient client;
    private final Duration retryDelay;

    @Autowired
    public ExpoPushService(ExpoPushTokenRepository repository, ExpoPushClient client) {
        this(repository, client, Duration.ofSeconds(2));
    }

    ExpoPushService(ExpoPushTokenRepository repository, ExpoPushClient client, Duration retryDelay) {
        this.repository = repository;
        this.client = client;
        this.retryDelay = retryDelay;
    }

    public PushNotificationService.Result sendToUser(Long userId, String kind, PushMessage message) {
        List<ExpoPushToken> targets = repository.findByUserId(userId);
        if (targets.isEmpty()) {
            return new PushNotificationService.Result(0, 0, 0);
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ExpoPushToken token : targets) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("to", token.getToken());
            m.put("title", message.title());
            m.put("body", message.body());
            m.put("data", Map.of("open", message.open()));
            m.put("channelId", CHANNEL);
            m.put("sound", "default");
            messages.add(m);
        }
        ExpoPushClient.Response response = sendWithRetry(messages);
        int sent = 0;
        int removed = 0;
        int failed = 0;
        if (response.status() != 200) {
            failed = targets.size();
            log.warn("앱 푸시 실패: user={} 상태 {}", userId, response.status());
        } else {
            for (int i = 0; i < targets.size(); i++) {
                ExpoPushToken token = targets.get(i);
                String error = i < response.ticketErrors().size() ? response.ticketErrors().get(i) : "missing";
                try {
                    if (error == null) {
                        repository.markSuccess(token.getId(), userId, LocalDateTime.now());
                        sent++;
                    } else if ("DeviceNotRegistered".equals(error)) {
                        repository.deleteByIdAndUserId(token.getId(), userId);
                        removed++;
                    } else {
                        log.warn("앱 푸시 티켓 오류: tokenId={} {}", token.getId(), error);
                        failed++;
                    }
                } catch (RuntimeException e) {
                    log.warn("앱 푸시 처리 오류: tokenId={} {}", token.getId(), e.getClass().getSimpleName());
                    failed++;
                }
            }
        }
        log.info("앱 푸시 {} user={} 토큰 {}개 → 성공 {}·삭제 {}·실패 {}", kind, userId, targets.size(), sent, removed, failed);
        return new PushNotificationService.Result(sent, removed, failed);
    }

    private ExpoPushClient.Response sendWithRetry(List<Map<String, Object>> messages) {
        ExpoPushClient.Response response = sendOnce(messages);
        if (response.status() == 429 || response.status() >= 500 || response.status() < 0) {
            sleep();
            response = sendOnce(messages);
        }
        return response;
    }

    private ExpoPushClient.Response sendOnce(List<Map<String, Object>> messages) {
        try {
            return client.send(messages);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExpoPushClient.Response(-1, List.of());
        } catch (Exception e) {
            log.warn("앱 푸시 전송 오류: {}", e.getClass().getSimpleName());
            return new ExpoPushClient.Response(-1, List.of());
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
}
