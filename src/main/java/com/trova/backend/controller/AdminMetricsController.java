package com.trova.backend.controller;

import com.trova.backend.service.ApiCallMetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gemini/카카오 호출 지표 요약. 지금은 별도 관리자 권한(Role) 체계가 없는 개인
 * 프로젝트라 로그인만 요구한다(SecurityConfig의 기본 인증 규칙) — 여러 사용자를
 * 받게 되면 그때 관리자 권한 체크를 추가해야 한다(0-1: 지금 안 만든 것 명시).
 */
@RestController
public class AdminMetricsController {

    private final ApiCallMetricsService apiCallMetricsService;

    public AdminMetricsController(ApiCallMetricsService apiCallMetricsService) {
        this.apiCallMetricsService = apiCallMetricsService;
    }

    @GetMapping("/api/admin/ai-metrics")
    public ApiCallMetricsService.MetricsSummary aiMetrics(@RequestParam(defaultValue = "7") int days) {
        return apiCallMetricsService.summarize(days);
    }
}
