package com.trova.backend.repository;

import com.trova.backend.entity.PushSubscription;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class PushSubscriptionRepositoryTest {

    @Autowired
    private PushSubscriptionRepository repository;

    @Test
    void 사용자별로_찾고_endpoint로_찾는다() {
        repository.save(new PushSubscription(1L, "https://push.example/a", "p1", "a1"));
        repository.save(new PushSubscription(1L, "https://push.example/b", "p2", "a2"));
        repository.save(new PushSubscription(2L, "https://push.example/c", "p3", "a3"));

        assertThat(repository.findByUserId(1L)).hasSize(2);
        assertThat(repository.findByEndpoint("https://push.example/c")).get()
                .extracting(PushSubscription::getUserId).isEqualTo(2L);
    }

    @Test
    void 남의_endpoint는_지우지_않는다() {
        repository.save(new PushSubscription(2L, "https://push.example/c", "p3", "a3"));
        repository.deleteByEndpointAndUserId("https://push.example/c", 1L);
        assertThat(repository.findByEndpoint("https://push.example/c")).isPresent();
    }
}
