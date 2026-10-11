package com.trova.backend.controller;

import com.trova.backend.admin.AdminGuard;
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

    public AdminDashboardController(AdminGuard adminGuard, UsageDashboardService usageDashboardService) {
        this.adminGuard = adminGuard;
        this.usageDashboardService = usageDashboardService;
    }

    @GetMapping("/api/admin/dashboard/usage")
    public UsageDashboardService.UsageDashboard usage(Authentication authentication,
                                                      @RequestParam(defaultValue = "14") int days) {
        adminGuard.requireAdmin(authentication);
        return usageDashboardService.usage(days);
    }
}
