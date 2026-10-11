package com.trova.backend.controller;

import com.trova.backend.admin.AdminGuard;
import com.trova.backend.admin.AiDashboardService;
import com.trova.backend.admin.DashboardSummaryService;
import com.trova.backend.admin.PipelineDashboardService;
import com.trova.backend.admin.ServerDashboardService;
import com.trova.backend.admin.UsageDashboardService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 운영 대시보드 집계 API(#161, 설계 2026-10-11-admin-dashboard-design.md). 관리자만. */
@RestController
public class AdminDashboardController {

    private final AdminGuard adminGuard;
    private final UsageDashboardService usageDashboardService;
    private final PipelineDashboardService pipelineDashboardService;
    private final AiDashboardService aiDashboardService;
    private final ServerDashboardService serverDashboardService;
    private final DashboardSummaryService dashboardSummaryService;

    public AdminDashboardController(AdminGuard adminGuard, UsageDashboardService usageDashboardService,
                                    PipelineDashboardService pipelineDashboardService, AiDashboardService aiDashboardService,
                                    ServerDashboardService serverDashboardService, DashboardSummaryService dashboardSummaryService) {
        this.adminGuard = adminGuard;
        this.usageDashboardService = usageDashboardService;
        this.pipelineDashboardService = pipelineDashboardService;
        this.aiDashboardService = aiDashboardService;
        this.serverDashboardService = serverDashboardService;
        this.dashboardSummaryService = dashboardSummaryService;
    }

    @GetMapping("/api/admin/dashboard/usage")
    public UsageDashboardService.UsageDashboard usage(Authentication authentication,
                                                      @RequestParam(defaultValue = "14") int days) {
        adminGuard.requireAdmin(authentication);
        return usageDashboardService.usage(days);
    }

    @GetMapping("/api/admin/dashboard/pipeline")
    public PipelineDashboardService.PipelineDashboard pipeline(Authentication authentication,
                                                               @RequestParam(defaultValue = "14") int days) {
        adminGuard.requireAdmin(authentication);
        return pipelineDashboardService.pipeline(days);
    }

    @GetMapping("/api/admin/dashboard/ai")
    public AiDashboardService.AiDashboard ai(Authentication authentication, @RequestParam(defaultValue = "14") int days) {
        adminGuard.requireAdmin(authentication);
        return aiDashboardService.ai(days);
    }

    @GetMapping("/api/admin/dashboard/server")
    public ServerDashboardService.ServerDashboard server(Authentication authentication,
                                                         @RequestParam(defaultValue = "14") int days) {
        adminGuard.requireAdmin(authentication);
        return serverDashboardService.server(days);
    }

    @GetMapping("/api/admin/dashboard/summary")
    public DashboardSummaryService.DashboardSummary summary(Authentication authentication) {
        adminGuard.requireAdmin(authentication);
        return dashboardSummaryService.summary();
    }
}
