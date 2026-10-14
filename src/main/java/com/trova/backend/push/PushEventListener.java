package com.trova.backend.push;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 저장이 커밋된 뒤에만 보낸다(롤백된 초안·알림은 알리지 않는다). 초안·날씨 저장은 트랜잭션 밖(리포지토리 save)에서도
 * 일어나므로 fallbackExecution으로 그때는 바로 보낸다. 푸시 서버 호출은 별도 풀에서.
 */
@Component
public class PushEventListener {

    private final PushNotificationService pushNotificationService;

    public PushEventListener(PushNotificationService pushNotificationService) {
        this.pushNotificationService = pushNotificationService;
    }

    @Async("pushTaskExecutor")
    @TransactionalEventListener(fallbackExecution = true)
    public void onDraftReady(TripDraftReadyEvent event) {
        pushNotificationService.sendToUser(event.userId(), "draft",
                PushMessages.draftReady(event.draftId(), event.placeCount(), event.dayCount()));
    }

    @Async("pushTaskExecutor")
    @TransactionalEventListener(fallbackExecution = true)
    public void onRain(RainNotificationCreatedEvent event) {
        pushNotificationService.sendToUser(event.userId(), "rain",
                PushMessages.rain(event.tripTitle(), event.date(), event.pop(), event.placeName(),
                        event.tripId(), event.tripPlaceId(), event.itineraryId()));
    }
}
