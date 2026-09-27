package com.trova.backend.controller;

import com.trova.backend.entity.Place;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.entity.User;
import com.trova.backend.service.AdminAccessService;
import com.trova.backend.service.ApiCallMetricsService;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Gemini/카카오 호출 지표 요약 등 관리자 전용 API. 설정(app.admin.users)에 등록된 사용자만
 * 쓸 수 있고, 그 외에는 403이다(AdminAccessService).
 */
@RestController
public class AdminMetricsController {

    private final ApiCallMetricsService apiCallMetricsService;
    private final PlaceRepository placeRepository;
    private final PlaceEmbeddingService placeEmbeddingService;
    private final CurrentUserService currentUserService;
    private final AdminAccessService adminAccessService;

    public AdminMetricsController(
            ApiCallMetricsService apiCallMetricsService,
            PlaceRepository placeRepository,
            PlaceEmbeddingService placeEmbeddingService,
            CurrentUserService currentUserService,
            AdminAccessService adminAccessService
    ) {
        this.apiCallMetricsService = apiCallMetricsService;
        this.placeRepository = placeRepository;
        this.placeEmbeddingService = placeEmbeddingService;
        this.currentUserService = currentUserService;
        this.adminAccessService = adminAccessService;
    }

    private void requireAdmin(Authentication authentication) {
        User user = currentUserService.resolve(authentication);
        if (!adminAccessService.isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    @GetMapping("/api/admin/ai-metrics")
    public ApiCallMetricsService.MetricsSummary aiMetrics(
            Authentication authentication, @RequestParam(defaultValue = "7") int days
    ) {
        requireAdmin(authentication);
        return apiCallMetricsService.summarize(days);
    }

    /**
     * 기존 카탈로그 중 아직 임베딩 없는 장소를 한 번에 백필한다(개인화 기능을
     * 새로 붙인 뒤 일회성으로 쓰는 관리자 엔드포인트). 동기 처리라 장소 수만큼
     * 시간이 걸린다 — 개인 프로젝트 규모에서만 쓰는 걸 전제로 한다.
     */
    @PostMapping("/api/admin/backfill-embeddings")
    public Map<String, Object> backfillEmbeddings(Authentication authentication) {
        requireAdmin(authentication);
        List<Long> ids = placeRepository.findAllIdsWithoutEmbedding();
        List<Place> places = placeRepository.findAllById(ids);
        placeEmbeddingService.ensureEmbeddings(places);
        long remaining = placeRepository.findAllIdsWithoutEmbedding().size();
        return Map.of("attempted", places.size(), "remaining", remaining);
    }
}
