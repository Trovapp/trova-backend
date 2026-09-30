package com.trova.backend.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 영상 분석 중 파이프라인이 먼저 알려준 장소 이름을 작업이 끝날 때까지만 들고 있는다(#51).
 * 대기 화면 연출용이라 DB에 저장하지 않는다 — 서버가 재시작되면 사라져도 되고, 최종 장소는 따로 저장된다.
 */
@Component
public class FoundPlaceNameStore {

    private final Map<Long, List<String>> namesByJobId = new ConcurrentHashMap<>();

    public void put(Long jobId, List<String> names) {
        namesByJobId.put(jobId, List.copyOf(names));
    }

    public List<String> get(Long jobId) {
        return namesByJobId.getOrDefault(jobId, List.of());
    }

    public void clear(Long jobId) {
        namesByJobId.remove(jobId);
    }
}
