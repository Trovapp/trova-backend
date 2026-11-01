package com.trova.backend.queue;

import com.trova.backend.service.PlaceExtractionService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ShareJobListenerTest {
    private final PlaceExtractionService extraction = mock(PlaceExtractionService.class);
    private final ShareJobListener listener = new ShareJobListener(extraction);

    @Test
    void 상태확인을_분석서비스에_위임하고_동기_호출한다() {
        listener.onMessage("42");
        verify(extraction).processNow(42L, QueuePath.KAFKA);
    }

    @Test
    void 잘못된_메시지는_분석을_시작하지_않는다() {
        assertThatThrownBy(() -> listener.onMessage("invalid")).isInstanceOf(NumberFormatException.class);
        verifyNoInteractions(extraction);
    }

    @Test
    void 서비스_예외를_리스너_오류처리로_전파한다() {
        doThrow(new IllegalStateException("fixture")).when(extraction).processNow(42L,QueuePath.KAFKA);
        assertThatThrownBy(()->listener.onMessage("42")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 분석이_끝나기_전에_리스너가_돌아가지_않는다() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(inv->{entered.countDown();assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();return null;})
                .when(extraction).processNow(42L,QueuePath.KAFKA);
        Thread thread=new Thread(()->listener.onMessage("42"));thread.start();
        try {assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();assertThat(thread.isAlive()).isTrue();}
        finally {release.countDown();thread.join(3000);}
        assertThat(thread.isAlive()).isFalse();
    }
}
