package com.trova.backend.service;

import com.trova.backend.entity.TravelPass;
import com.trova.backend.entity.User;
import com.trova.backend.repository.TravelPassRepository;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 회원의 구매 기록이 동시에 들어올 때(#130 QA 2026-10-07, 로컬 서버에서 재현):
 * 서로 다른 거래 2개가 둘 다 "지금"부터 시작해 기간이 겹쳤고, 같은 거래 3번은 2번이 500으로 실패했다.
 * 트랜잭션 안에서 실제 DB로 확인해야 해서 테스트 트랜잭션(@Transactional)을 쓰지 않는다.
 */
@SpringBootTest
@TestPropertySource(properties = "app.billing.allow-xcode-transactions=true")
class BillingConcurrencyIntegrationTest {

    @Autowired private BillingService billingService;
    @Autowired private UserRepository userRepository;
    @Autowired private TravelPassRepository travelPassRepository;

    private final List<User> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (User u : created) {
            travelPassRepository.deleteAll(travelPassRepository.findByUser(u));
            userRepository.delete(u);
        }
    }

    private User user(String sub) {
        User u = userRepository.save(new User("google", sub, "동시구매", null));
        created.add(u);
        return u;
    }

    static String jws(User user, String transactionId) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String payload = "{\"transactionId\":\"" + transactionId + "\",\"productId\":\"com.trovapp.trova.travelpass30\","
                + "\"bundleId\":\"com.trovapp.trova\",\"environment\":\"Xcode\",\"appAccountToken\":\""
                + BillingService.appAccountToken(user) + "\"}";
        return enc.encodeToString("{\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + enc.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    private List<Future<TravelPass>> runTogether(User user, String... transactionIds) {
        ExecutorService pool = Executors.newFixedThreadPool(transactionIds.length);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<TravelPass>> futures = new ArrayList<>();
        for (String id : transactionIds) {
            Callable<TravelPass> c = () -> {
                go.await();
                return billingService.recordAppleTransaction(user, jws(user, id));
            };
            futures.add(pool.submit(c));
        }
        go.countDown();
        pool.shutdown();
        return futures;
    }

    @Test
    void 다른_거래_두_개가_동시에_와도_기간이_겹치지_않고_이어진다() throws Exception {
        User me = user("race-1");
        for (Future<TravelPass> f : runTogether(me, "race-a", "race-b")) {
            f.get();
        }
        List<TravelPass> passes = new ArrayList<>(travelPassRepository.findByUser(me));
        passes.sort(Comparator.comparing(TravelPass::getStartsAt));
        assertThat(passes).hasSize(2);
        assertThat(passes.get(1).getStartsAt()).isEqualTo(passes.get(0).getExpiresAt());
        assertThat(Duration.between(passes.get(0).getStartsAt(), passes.get(1).getExpiresAt()).toDays()).isEqualTo(60);
    }

    @Test
    void 같은_거래가_동시에_여러_번_와도_오류_없이_한_장만_생긴다() throws Exception {
        User me = user("race-2");
        for (Future<TravelPass> f : runTogether(me, "race-same", "race-same", "race-same")) {
            assertThat(f.get().getTransactionId()).isEqualTo("race-same");
        }
        assertThat(travelPassRepository.findByUser(me)).hasSize(1);
    }
}
