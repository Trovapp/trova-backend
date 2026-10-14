package com.trova.backend.push;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** 초안·알림 저장이 롤백되면 웹 푸시를 보내지 않는다(#185). 트랜잭션 밖 저장은 바로 보낸다. */
@SpringBootTest
class PushEventRollbackTest {

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactions;

    @MockitoBean
    private PushNotificationService pushNotificationService;

    @Test
    void 트랜잭션이_롤백되면_보내지_않는다() {
        transactions.executeWithoutResult(status -> {
            events.publishEvent(new TripDraftReadyEvent(7L, 1L, 3, 1));
            status.setRollbackOnly();
        });
        verify(pushNotificationService, after(500).never()).sendToUser(anyLong(), anyString(), any());
    }

    @Test
    void 트랜잭션_밖에서_발행하면_바로_보낸다() {
        events.publishEvent(new TripDraftReadyEvent(7L, 2L, 3, 1));
        verify(pushNotificationService, timeout(2000)).sendToUser(anyLong(), anyString(), any());
    }
}
