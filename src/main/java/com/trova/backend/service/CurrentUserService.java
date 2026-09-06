package com.trova.backend.service;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtAuthenticationToken;
import com.trova.backend.security.OAuth2UserInfo;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {

    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User resolve(Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken oauth2Token) {
            OAuth2UserInfo info = OAuth2UserInfo.of(
                    oauth2Token.getAuthorizedClientRegistrationId(),
                    oauth2Token.getPrincipal().getAttributes()
            );
            return userRepository.findByProviderAndProviderUserId(info.provider(), info.providerUserId())
                    .orElseThrow(() -> new IllegalStateException(
                            "인증된 사용자를 찾을 수 없습니다: " + info.provider() + " " + info.providerUserId()));
        }
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            return userRepository.findById(jwtToken.getUserId())
                    .orElseThrow(() -> new IllegalStateException(
                            "인증된 사용자를 찾을 수 없습니다: userId=" + jwtToken.getUserId()));
        }
        throw new IllegalStateException("지원하지 않는 인증 타입: " + authentication.getClass());
    }
}
