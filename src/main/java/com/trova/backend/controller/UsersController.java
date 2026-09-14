package com.trova.backend.controller;

import com.trova.backend.entity.User;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.UserAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UsersController {

    private final CurrentUserService currentUserService;
    private final UserAccountService userAccountService;

    public UsersController(CurrentUserService currentUserService, UserAccountService userAccountService) {
        this.currentUserService = currentUserService;
        this.userAccountService = userAccountService;
    }

    public record MeResponse(Long id, String nickname, String profileImageUrl, String provider, String createdAt) {
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(Authentication authentication) {
        User user = currentUserService.resolve(authentication);
        return ResponseEntity.ok(new MeResponse(
                user.getId(), user.getNickname(), user.getProfileImageUrl(),
                user.getProvider(), user.getCreatedAt().toString()
        ));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        User user = currentUserService.resolve(authentication);
        userAccountService.withdraw(user);
        // 탈퇴한 유저로 인증된 세션이 남아있으면 다음 요청이 존재하지 않는 유저를 참조하게
        // 되므로, 삭제 직후 로그아웃 처리(세션 무효화)까지 함께 한다.
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        return ResponseEntity.noContent().build();
    }
}
