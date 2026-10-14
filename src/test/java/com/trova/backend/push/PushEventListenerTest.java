package com.trova.backend.push;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushEventListenerTest {

    private final PushNotificationService service = mock(PushNotificationService.class);
    private final ExpoPushService expo = mock(ExpoPushService.class);
    private final PushEventListener listener = new PushEventListener(service, expo);

    @Test
    void 초안_준비_이벤트는_초안_문구로_보낸다() {
        listener.onDraftReady(new TripDraftReadyEvent(7L, 12L, 5, 1));
        verify(service).sendToUser(eq(7L), eq("draft"), eq(PushMessages.draftReady(12L, 5, 1)));
        verify(expo).sendToUser(eq(7L), eq("draft"), eq(PushMessages.draftReady(12L, 5, 1)));
    }

    @Test
    void 웹_발송이_실패해도_앱_발송은_한다() {
        when(service.sendToUser(eq(7L), eq("draft"), any()))
                .thenThrow(new IllegalStateException("web down"));
        listener.onDraftReady(new TripDraftReadyEvent(7L, 12L, 5, 1));
        verify(expo).sendToUser(eq(7L), eq("draft"), eq(PushMessages.draftReady(12L, 5, 1)));
    }

    @Test
    void 비_알림_이벤트는_비_문구로_보낸다() {
        LocalDate date = LocalDate.of(2026, 10, 19);
        listener.onRain(new RainNotificationCreatedEvent(7L, 45L, 90L, 678L, "제주", date, 0.7, "성산일출봉"));
        verify(service).sendToUser(eq(7L), eq("rain"), eq(PushMessages.rain("제주", date, 0.7, "성산일출봉", 45L, 678L, 90L)));
        verify(expo).sendToUser(eq(7L), eq("rain"), eq(PushMessages.rain("제주", date, 0.7, "성산일출봉", 45L, 678L, 90L)));
    }
}
