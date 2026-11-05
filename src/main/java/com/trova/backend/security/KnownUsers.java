package com.trova.backend.security;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DB에서 존재를 확인한 사용자 ID를 기억한다(ADR 0036). 조회 API가 요청마다 사용자 행을 읽느라
 * DB 왕복 2번(BEGIN+조회, COMMIT)을 쓰던 것을 처음 한 번으로 줄인다.
 * 단일 JVM 범위다(ADR 0025·0027과 같은 전제). 사용자 행은 탈퇴로만 지워지므로 탈퇴 커밋 뒤에 뺀다.
 *
 * <p>탈퇴한 ID는 따로 표시해 다시 기억하지 않는다. 요청이 "존재 확인(탈퇴 커밋 전이라 있음) → 기억" 사이에
 * 탈퇴 커밋과 제거가 끼면, 늦게 도착한 기억하기가 지운 ID를 되살려 재시작 전까지 남기 때문이다.
 * 사용자 ID는 시퀀스라 다시 쓰이지 않으므로 표시를 지울 일이 없다. 크기는 이 JVM이 처리한 탈퇴 수만큼이다.
 */
@Component
public class KnownUsers {

    private final Set<Long> ids = ConcurrentHashMap.newKeySet();
    // add·forgetWithdrawn은 이 객체로 동기화한다 — "표시 확인 후 추가"와 "표시 후 제거"가 섞이지 않게.
    private final Set<Long> withdrawn = new HashSet<>();

    public boolean contains(Long userId) {
        return userId != null && ids.contains(userId);
    }

    public synchronized void add(Long userId) {
        if (userId != null && !withdrawn.contains(userId)) {
            ids.add(userId);
        }
    }

    /** 기억만 지운다(다음 요청이 DB로 다시 확인). 탈퇴에는 {@link #removeAfterCommit}을 쓴다. */
    public void remove(Long userId) {
        ids.remove(userId);
    }

    /** 탈퇴한 ID로 표시하고 지운다. 이후 늦게 도착한 {@link #add}도 무시된다. */
    public synchronized void forgetWithdrawn(Long userId) {
        if (userId == null) {
            return;
        }
        withdrawn.add(userId);
        ids.remove(userId);
    }

    /**
     * 트랜잭션 안이면 커밋된 뒤에 탈퇴 표시를 한다. 커밋 전에 지우면 그 사이 요청이 아직 남아 있는 행을 보고
     * 다시 기억하고, 커밋 전에 표시하면 탈퇴가 롤백돼도 그 사용자를 다시는 기억하지 못한다.
     */
    public void removeAfterCommit(Long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            forgetWithdrawn(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                forgetWithdrawn(userId);
            }
        });
    }
}
