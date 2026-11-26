package com.trova.backend.service;

import com.trova.backend.entity.User;
import com.trova.backend.repository.AnalyticsConsentRepository;
import com.trova.backend.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"app.analytics.notice-ready=true", "app.analytics.posthog-personal-api-key="})
class AnalyticsConsentConcurrencyTest {
    @Autowired private AnalyticsConsentService service;
    @Autowired private UserRepository users;
    @Autowired private AnalyticsConsentRepository consents;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private AnalyticsConsentService.Selection selection(boolean transfer) {
        return new AnalyticsConsentService.Selection(AnalyticsConsentService.VERSION, 0L, true, transfer);
    }

    @Test
    void 같은_revision의_서로_다른_선택은_한_요청만_저장된다() throws Exception {
        User user = users.save(new User("google", UUID.randomUUID().toString(), "경쟁", null));
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> first = executor.submit(() -> update(start, user, true));
            Future<Integer> second = executor.submit(() -> update(start, user, false));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
            assertThat(service.get(user.getId()).revision()).isEqualTo(1);
        } finally {
            consents.deleteById(user.getId());
            users.deleteById(user.getId());
        }
    }

    private int update(CountDownLatch start, User user, boolean transfer) throws InterruptedException {
        start.await();
        try {
            service.put(user.getId(), selection(transfer));
            return 200;
        } catch (ResponseStatusException exception) {
            return exception.getStatusCode().value();
        }
    }

    @Test
    void 철회_롤백은_UUID와_삭제큐를_함께_되돌리고_키가_없으면_대기한다() {
        User user = users.save(new User("google", UUID.randomUUID().toString(), "롤백", null));
        UUID id = service.put(user.getId(), selection(true)).analyticsId();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                service.revoke(user.getId());
                transaction.setRollbackOnly();
            });
            assertThat(service.get(user.getId()).analyticsId()).isEqualTo(id);
            assertThat(jdbc.queryForObject("select count(*) from analytics_deletion_jobs where analytics_id = ?", Long.class, id)).isZero();
            service.revoke(user.getId());
            assertThat(service.get(user.getId()).analyticsId()).isNull();
            // 외부 호출 worker가 없는 큐 전용 구현. 키 누락 시 성공으로 꾸미거나 작업을 버리지 않는다.
            assertThat(jdbc.queryForObject("select status from analytics_deletion_jobs where analytics_id = ?", String.class, id))
                    .isEqualTo("PENDING");
        } finally {
            consents.deleteById(user.getId());
            users.deleteById(user.getId());
            jdbc.update("delete from analytics_deletion_jobs where analytics_id = ?", id);
        }
    }
}
