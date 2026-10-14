package com.trova.backend.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 저장이 커밋된 뒤에만 보낸다(롤백된 초안·알림은 알리지 않는다). 초안·날씨 저장은 트랜잭션 밖(리포지토리 save)에서도
 * 일어나므로 fallbackExecution으로 그때는 바로 보낸다. 푸시 서버 호출은 별도 풀에서.
 * 웹(PWA, ADR 0016)과 안드로이드 앱(Expo, ADR 0017)에 같은 알림을 보내고, 한쪽 오류가 다른 쪽을 막지 않게 따로 감싼다.
 */
@Component
public class PushEventListener {

    private static final Logger log = LoggerFactory.getLogger(PushEventListener.class);

    private final PushNotificationService pushNotificationService;
    private final ExpoPushService expoPushService;

    public PushEventListener(PushNotificationService pushNotificationService, ExpoPushService expoPushService) {
        this.pushNotificationService = pushNotificationService;
        this.expoPushService = expoPushService;
    }

    @Async("pushTaskExecutor")
    @TransactionalEventListener(fallbackExecution = true)
    public void onDraftReady(TripDraftReadyEvent event) {
        sendBoth(event.userId(), "draft", PushMessages.draftReady(event.draftId(), event.placeCount(), event.dayCount()));
    }

    @Async("pushTaskExecutor")
    @TransactionalEventListener(fallbackExecution = true)
    public void onRain(RainNotificationCreatedEvent event) {
        sendBoth(event.userId(), "rain", PushMessages.rain(event.tripTitle(), event.date(), event.pop(),
                event.placeName(), event.tripId(), event.tripPlaceId(), event.itineraryId()));
    }

    private void sendBoth(Long userId, String kind, PushMessage message) {
        try {
            pushNotificationService.sendToUser(userId, kind, message);
        } catch (RuntimeException e) {
            log.warn("웹 푸시 {} 실패: {}", kind, e.getClass().getSimpleName());
        }
        try {
            expoPushService.sendToUser(userId, kind, message);
        } catch (RuntimeException e) {
            log.warn("앱 푸시 {} 실패: {}", kind, e.getClass().getSimpleName());
        }
    }
}
