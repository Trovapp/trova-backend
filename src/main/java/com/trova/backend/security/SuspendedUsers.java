package com.trova.backend.security;

import com.trova.backend.repository.UserRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 정지된 계정 번호(ADR 0014). 요청마다 DB를 읽지 않으려고 메모리에 둔다 — 운영 서버가 1대라 충분하다.
 * 서버가 뜰 때 DB에서 채우고, 정지·풀기 조치가 바로 고친다.
 */
@Component
public class SuspendedUsers {

    private final UserRepository userRepository;
    private final Set<Long> ids = ConcurrentHashMap.newKeySet();

    public SuspendedUsers(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reload() {
        ids.clear();
        ids.addAll(userRepository.findSuspendedIds());
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
}
