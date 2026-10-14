package com.trova.backend.push;

import com.trova.backend.entity.PushSubscription;
import com.trova.backend.repository.PushSubscriptionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 웹 푸시 구독 등록(#185). 같은 기기(endpoint)면 덮어쓴다 — 다른 사람이 로그인했을 수도 있어 사용자까지.
 * 앱을 열 때의 재등록과 [알림 받기]가 같은 구독을 거의 동시에 보내면 둘 다 "없음"을 보고 저장하다 하나가
 * unique 제약에 걸린다(2026-10-14 로컬 500). 그때는 먼저 저장된 행을 덮어쓴다. 트랜잭션으로 묶지 않아야
 * 실패한 저장 뒤에도 다시 읽고 고칠 수 있다(리포지토리 호출마다 짧은 트랜잭션).
 */
@Service
public class PushSubscriptionService {

    private final PushSubscriptionRepository repository;

    public PushSubscriptionService(PushSubscriptionRepository repository) {
        this.repository = repository;
    }

    public void upsert(Long userId, String endpoint, String p256dh, String auth) {
        PushSubscription existing = repository.findByEndpoint(endpoint).orElse(null);
        if (existing != null) {
            existing.reassign(userId, p256dh, auth);
            repository.save(existing);
            return;
        }
        try {
            repository.saveAndFlush(new PushSubscription(userId, endpoint, p256dh, auth));
        } catch (DataIntegrityViolationException e) {
            repository.findByEndpoint(endpoint).ifPresent(raced -> {
                raced.reassign(userId, p256dh, auth);
                repository.save(raced);
            });
        }
    }
}
