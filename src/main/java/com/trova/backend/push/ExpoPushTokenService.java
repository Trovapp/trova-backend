package com.trova.backend.push;

import com.trova.backend.entity.ExpoPushToken;
import com.trova.backend.repository.ExpoPushTokenRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 안드로이드 앱 알림 토큰 등록(#190). 웹 구독(PushSubscriptionService)과 같은 규칙 — 같은 토큰이면 사용자까지 덮어쓰고,
 * 동시 등록의 unique 충돌은 먼저 저장된 행을 덮어쓰며, 사람당 10개를 넘으면 가장 오래 안 쓴 것부터 지운다.
 */
@Service
public class ExpoPushTokenService {

    static final int MAX_PER_USER = 10;

    private final ExpoPushTokenRepository repository;

    public ExpoPushTokenService(ExpoPushTokenRepository repository) {
        this.repository = repository;
    }

    public void upsert(Long userId, String token) {
        ExpoPushToken existing = repository.findByToken(token).orElse(null);
        if (existing != null) {
            existing.reassign(userId);
            repository.save(existing);
            enforceCap(userId);
            return;
        }
        try {
            repository.saveAndFlush(new ExpoPushToken(userId, token));
        } catch (DataIntegrityViolationException e) {
            repository.findByToken(token).ifPresent(raced -> {
                raced.reassign(userId);
                repository.save(raced);
            });
            enforceCap(userId);
            return;
        }
        enforceCap(userId);
    }

    /** 사람당 10개 — 새로 넣을 때뿐 아니라 다른 사람에게서 옮겨 올 때도(#200). 가장 오래 안 쓴 것부터 지운다. */
    private void enforceCap(Long userId) {
        List<ExpoPushToken> mine = repository.findByUserIdOrderByLastSeenAtDescIdDesc(userId);
        if (mine.size() > MAX_PER_USER) {
            repository.deleteAll(mine.subList(MAX_PER_USER, mine.size()));
        }
    }
}
