package com.trova.backend.push;

import com.trova.backend.entity.PushSubscription;
import com.trova.backend.repository.PushSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushSubscriptionServiceTest {

    private final PushSubscriptionRepository repository = mock(PushSubscriptionRepository.class);
    private final PushSubscriptionService service = new PushSubscriptionService(repository);

    @Test
    void 같은_구독이_동시에_들어와_먼저_저장됐으면_오류_대신_덮어쓴다() {
        // 앱을 열 때의 재등록과 [알림 받기]가 같은 구독을 거의 동시에 보내 하나가 500이 났다(2026-10-14 로컬 확인).
        PushSubscription saved = new PushSubscription(1L, "https://p/a", "old", "old");
        when(repository.findByEndpoint("https://p/a")).thenReturn(Optional.empty(), Optional.of(saved));
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        service.upsert(2L, "https://p/a", "pk", "au");

        assertThat(saved.getUserId()).isEqualTo(2L);
        assertThat(saved.getP256dh()).isEqualTo("pk");
        verify(repository).save(saved);
    }
}
