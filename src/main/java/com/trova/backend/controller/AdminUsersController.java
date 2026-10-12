package com.trova.backend.controller;

import com.trova.backend.admin.AdminGuard;
import com.trova.backend.admin.action.AdminAuditLog;
import com.trova.backend.admin.user.AdminUserService;
import com.trova.backend.entity.AdminActionLog;
import com.trova.backend.entity.User;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 관리자 사용자 찾기·상세(#167, ADR 0014). 읽기 전용이라 재인증은 필요 없지만 열람은 모두 기록한다.
 * 검색어는 기록·로그 어디에도 남기지 않는다 — 결과 수만.
 */
@RestController
public class AdminUsersController {

    public record SearchResponse(List<AdminUserService.UserSummary> users) { }

    private final AdminGuard adminGuard;
    private final AdminUserService userService;
    private final AdminAuditLog auditLog;

    public AdminUsersController(AdminGuard adminGuard, AdminUserService userService, AdminAuditLog auditLog) {
        this.adminGuard = adminGuard;
        this.userService = userService;
        this.auditLog = auditLog;
    }

    @GetMapping("/api/admin/users")
    public SearchResponse search(Authentication authentication, @RequestParam(required = false) String q) {
        User admin = adminGuard.requireAdmin(authentication);
        List<AdminUserService.UserSummary> users = userService.search(q);
        auditLog.record(admin.getId(), AdminActionLog.ADMIN_USER_SEARCH, null, null, null,
                "결과 " + users.size() + "명");
        return new SearchResponse(users);
    }

    @GetMapping("/api/admin/users/{id}")
    public AdminUserService.UserDetail detail(Authentication authentication, @PathVariable Long id) {
        User admin = adminGuard.requireAdmin(authentication);
        AdminUserService.UserDetail detail = userService.detail(id);
        auditLog.record(admin.getId(), AdminActionLog.ADMIN_USER_VIEW, id, id, null, null);
        return detail;
    }
}
