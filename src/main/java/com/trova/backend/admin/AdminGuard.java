package com.trova.backend.admin;

import com.trova.backend.entity.User;
import com.trova.backend.service.AdminAccessService;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 관리자 API 공통 권한 확인. 로그인하지 않은 요청은 보안 필터가 먼저 401로 막는다. */
@Service
public class AdminGuard {

    private final CurrentUserService currentUserService;
    private final AdminAccessService adminAccessService;

    public AdminGuard(CurrentUserService currentUserService, AdminAccessService adminAccessService) {
        this.currentUserService = currentUserService;
        this.adminAccessService = adminAccessService;
    }

    public User requireAdmin(Authentication authentication) {
        User user = currentUserService.resolve(authentication);
        if (!adminAccessService.isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return user;
    }
}
