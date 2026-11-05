package com.trova.backend.security;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DB에서 존재를 확인한 사용자 ID를 기억한다(ADR 0036). 조회 API가 요청마다 사용자 행을 읽느라
 * DB 왕복 2번(BEGIN+조회, COMMIT)을 쓰던 것을 처음 한 번으로 줄인다.
 * 단일 JVM 범위다(ADR 0025·0027과 같은 전제). 사용자 행은 탈퇴로만 지워지므로 탈퇴 커밋 뒤에 뺀다.
 */
@Component
public class KnownUsers {

    private final Set<Long> ids = ConcurrentHashMap.newKeySet();

    public boolean contains(Long userId) {
        return userId != null && ids.contains(userId);
    }

    public void add(Long userId) {
        if (userId != null) {
            ids.add(userId);
        }
    }

    public void remove(Long userId) {
        ids.remove(userId);
    }

    /**
     * 트랜잭션 안이면 커밋된 뒤에 뺀다. 커밋 전에 빼면 그 사이 요청이 아직 남아 있는 행을 보고
     * 다시 기억해, 탈퇴한 사용자의 토큰이 서버를 다시 띄울 때까지 통할 수 있다.
     */
    public void removeAfterCommit(Long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            remove(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                remove(userId);
            }
        });
    }
}
