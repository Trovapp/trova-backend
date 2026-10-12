package com.trova.backend.security;

import com.trova.backend.repository.UserRepository;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 정지된 계정 번호(ADR 0014). 요청마다 DB를 읽지 않으려고 메모리에 둔다 — 운영 서버가 1대라 충분하다.
 * 서버가 뜰 때 DB에서 채우고, 정지·풀기 조치가 바로 고친다.
 * 빈 초기화 단계(afterSingletonsInstantiated)에서 채운다 — 웹 서버는 그 뒤에 요청을 받기 시작하므로, 정지 계정이
 * 빈 목록을 틈타 통과하는 순간이 없다(ApplicationReadyEvent는 요청을 받기 시작한 뒤라 짧은 틈이 있었다).
 */
@Component
public class SuspendedUsers implements SmartInitializingSingleton {

    private final UserRepository userRepository;
    private volatile Set<Long> ids = ConcurrentHashMap.newKeySet();

    public SuspendedUsers(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void afterSingletonsInstantiated() {
        reload();
    }

    /** 새 목록을 다 만든 뒤 한 번에 바꾼다 — 비웠다가 채우는 사이에 정지 계정이 통과하지 않게. */
    public void reload() {
        Set<Long> loaded = ConcurrentHashMap.newKeySet();
        loaded.addAll(userRepository.findSuspendedIds());
        ids = loaded;
    }

    public boolean isSuspended(Long userId) {
        return userId != null && ids.contains(userId);
    }

    public void add(Long userId) {
        ids.add(userId);
    }

    public void remove(Long userId) {
        ids.remove(userId);
    }

    /**
     * 트랜잭션 안이면 커밋된 뒤에만 뺀다. 먼저 빼고 커밋이 실패하면 DB는 정지인데 메모리에서는 빠져
     * 정지된 사용자의 앱 토큰이 서버를 다시 띄울 때까지 통한다. 트랜잭션 밖이면 바로 뺀다.
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
