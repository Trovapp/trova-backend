package com.trova.backend.controller;

import com.trova.backend.admin.AdminGuard;
import com.trova.backend.entity.Place;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.service.ApiCallMetricsService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Gemini/카카오 호출 지표 요약 등 관리자 전용 API. 설정(app.admin.users)에 등록된 사용자만
 * 쓸 수 있고, 그 외에는 403이다. 확인은 다른 관리자 API와 같은 AdminGuard로 한다 — 이 컨트롤러만 따로 확인하다
 * 관리자 웹 토큰 조건이 빠질 뻔했다(#163, 전수 권한 테스트가 잡음).
 */
@RestController
public class AdminMetricsController {

    private final ApiCallMetricsService apiCallMetricsService;
    private final PlaceRepository placeRepository;
    private final PlaceEmbeddingService placeEmbeddingService;
    private final AdminGuard adminGuard;

    public AdminMetricsController(
            ApiCallMetricsService apiCallMetricsService,
            PlaceRepository placeRepository,
            PlaceEmbeddingService placeEmbeddingService,
            AdminGuard adminGuard
    ) {
        this.apiCallMetricsService = apiCallMetricsService;
        this.placeRepository = placeRepository;
        this.placeEmbeddingService = placeEmbeddingService;
        this.adminGuard = adminGuard;
    }

    @GetMapping("/api/admin/ai-metrics")
    public ApiCallMetricsService.MetricsSummary aiMetrics(
            Authentication authentication, @RequestParam(defaultValue = "7") int days
    ) {
        adminGuard.requireAdmin(authentication);
        return apiCallMetricsService.summarize(days);
    }

    /**
     * 기존 카탈로그 중 아직 임베딩 없는 장소를 한 번에 백필한다(개인화 기능을
     * 새로 붙인 뒤 일회성으로 쓰는 관리자 엔드포인트). 동기 처리라 장소 수만큼
     * 시간이 걸린다 — 개인 프로젝트 규모에서만 쓰는 걸 전제로 한다.
     */
    @PostMapping("/api/admin/backfill-embeddings")
    public Map<String, Object> backfillEmbeddings(Authentication authentication) {
        adminGuard.requireAdmin(authentication);
        List<Long> ids = placeRepository.findAllIdsWithoutEmbedding();
        List<Place> places = placeRepository.findAllById(ids);
        placeEmbeddingService.ensureEmbeddings(places);
        long remaining = placeRepository.findAllIdsWithoutEmbedding().size();
        return Map.of("attempted", places.size(), "remaining", remaining);
    }
}
