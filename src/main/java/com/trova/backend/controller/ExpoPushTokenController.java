package com.trova.backend.controller;

import com.trova.backend.entity.User;
import com.trova.backend.push.ExpoPushTokenService;
import com.trova.backend.repository.ExpoPushTokenRepository;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

/** 안드로이드 앱(Expo) 알림 토큰(#190, ADR 0017). 앱은 열 때마다 토큰을 다시 보내 끊긴 등록을 되살린다. */
@RestController
public class ExpoPushTokenController {

    // Expo가 주는 토큰 모양만 받는다 — 이 값은 Expo 서버로만 보내지만, 아무 문자열이나 쌓이지 않게.
    private static final Pattern TOKEN = Pattern.compile("^Expo(nent)?PushToken\\[[A-Za-z0-9_\\-]{10,180}\\]$");

    public record TokenRequest(String token) { }

    private final ExpoPushTokenService tokenService;
    private final ExpoPushTokenRepository tokens;
    private final CurrentUserService currentUserService;

    public ExpoPushTokenController(ExpoPushTokenService tokenService, ExpoPushTokenRepository tokens,
                                   CurrentUserService currentUserService) {
        this.tokenService = tokenService;
        this.tokens = tokens;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/api/push/expo-tokens")
    public ResponseEntity<Void> register(Authentication authentication, @RequestBody TokenRequest request) {
        User user = currentUserService.resolve(authentication);
        if (request == null || request.token() == null || !TOKEN.matcher(request.token()).matches()) {
            return ResponseEntity.badRequest().build();
        }
        tokenService.upsert(user.getId(), request.token());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/push/expo-tokens")
    public ResponseEntity<Void> unregister(Authentication authentication, @RequestBody TokenRequest request) {
        User user = currentUserService.resolve(authentication);
        if (request != null && request.token() != null) {
            tokens.deleteByTokenAndUserId(request.token(), user.getId());
        }
        return ResponseEntity.noContent().build();
    }
}
