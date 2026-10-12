package com.trova.backend.controller;

import com.trova.backend.entity.User;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.ShareKeyService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** iOS 단축어용 공유 전용 키 관리(#165). 로그인한 사용자(앱·웹앱 토큰)만 — 공유 키 자체로는 부를 수 없다. */
@RestController
public class ShareKeyController {

    private final CurrentUserService currentUserService;
    private final ShareKeyService shareKeyService;

    public ShareKeyController(CurrentUserService currentUserService, ShareKeyService shareKeyService) {
        this.currentUserService = currentUserService;
        this.shareKeyService = shareKeyService;
    }

    @GetMapping("/api/share-key")
    public ShareKeyService.ShareKeyStatus status(Authentication authentication) {
        User user = currentUserService.resolve(authentication);
        return shareKeyService.status(user.getId());
    }

    @PostMapping("/api/share-key")
    public ShareKeyService.IssuedShareKey issue(Authentication authentication) {
        User user = currentUserService.resolve(authentication);
        return shareKeyService.issue(user.getId());
    }

    @DeleteMapping("/api/share-key")
    public ResponseEntity<Void> revoke(Authentication authentication) {
        User user = currentUserService.resolve(authentication);
        shareKeyService.revoke(user.getId());
        return ResponseEntity.noContent().build();
    }
}
