package com.trova.backend.service;

import com.trova.backend.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관리자 여부. 역할(Role) 체계가 없어서 설정 `app.admin.users`에 "로그인제공자:제공자사용자ID"
 * (예: google:1234, kakao:5678)를 쉼표로 적은 사용자만 관리자로 본다. DB id는 환경마다 달라지므로
 * 쓰지 않는다. 비어 있으면 아무도 관리자가 아니다(안전한 기본값) — 예전엔 로그인만 하면 누구나
 * 관리자 API를 부를 수 있었다(#21).
 */
@Service
public class AdminAccessService {

    private final Set<String> adminKeys;

    public AdminAccessService(@Value("${app.admin.users:}") String adminUsers) {
        this.adminKeys = Arrays.stream(adminUsers.split(","))
                .map(String::trim)
                .filter(key -> !key.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAdmin(User user) {
        return user != null && adminKeys.contains(user.getProvider() + ":" + user.getProviderUserId());
    }
}
