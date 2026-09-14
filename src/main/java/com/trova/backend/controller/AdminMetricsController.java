package com.trova.backend.controller;

import com.trova.backend.entity.Place;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.service.ApiCallMetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Gemini/카카오 호출 지표 요약. 지금은 별도 관리자 권한(Role) 체계가 없는 개인
 * 프로젝트라 로그인만 요구한다(SecurityConfig의 기본 인증 규칙) — 여러 사용자를
 * 받게 되면 그때 관리자 권한 체크를 추가해야 한다(0-1: 지금 안 만든 것 명시).
 */
@RestController
public class AdminMetricsController {

    private final ApiCallMetricsService apiCallMetricsService;
    private final PlaceRepository placeRepository;
    private final PlaceEmbeddingService placeEmbeddingService;

    public AdminMetricsController(
            ApiCallMetricsService apiCallMetricsService,
            PlaceRepository placeRepository,
            PlaceEmbeddingService placeEmbeddingService
    ) {
        this.apiCallMetricsService = apiCallMetricsService;
        this.placeRepository = placeRepository;
        this.placeEmbeddingService = placeEmbeddingService;
    }

    @GetMapping("/api/admin/ai-metrics")
    public ApiCallMetricsService.MetricsSummary aiMetrics(@RequestParam(defaultValue = "7") int days) {
        return apiCallMetricsService.summarize(days);
    }

    /**
     * 기존 카탈로그 중 아직 임베딩 없는 장소를 한 번에 백필한다(개인화 기능을
     * 새로 붙인 뒤 일회성으로 쓰는 관리자 엔드포인트). 동기 처리라 장소 수만큼
     * 시간이 걸린다 — 개인 프로젝트 규모에서만 쓰는 걸 전제로 한다.
     */
    @PostMapping("/api/admin/backfill-embeddings")
    public Map<String, Object> backfillEmbeddings() {
        List<Long> ids = placeRepository.findAllIdsWithoutEmbedding();
        List<Place> places = placeRepository.findAllById(ids);
        placeEmbeddingService.ensureEmbeddings(places);
        long remaining = placeRepository.findAllIdsWithoutEmbedding().size();
        return Map.of("attempted", places.size(), "remaining", remaining);
    }
}
