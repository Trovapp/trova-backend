package com.trova.backend.service;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtAuthenticationToken;
import com.trova.backend.security.OAuth2UserInfo;
import org.springframework.security.authentication.InsufficientAuthenticationException;
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
        if (authentication == null) {
            throw new InsufficientAuthenticationException("인증 정보가 없습니다");
        }
        if (authentication instanceof OAuth2AuthenticationToken oauth2Token) {
            OAuth2UserInfo info = OAuth2UserInfo.of(
                    oauth2Token.getAuthorizedClientRegistrationId(),
                    oauth2Token.getPrincipal().getAttributes()
            );
            return userRepository.findByProviderAndProviderUserId(info.provider(), info.providerUserId())
                    .orElseThrow(() -> new InsufficientAuthenticationException(
                            "인증된 사용자를 찾을 수 없습니다: " + info.provider() + " " + info.providerUserId()));
        }
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            // 탈퇴 등으로 User row가 삭제된 뒤에도 JWT 자체는 만료 전까지 서명 검증에는
            // 계속 통과한다. 이 경우 "인증은 됐지만 사용자를 찾을 수 없음"을 Spring
            // Security의 AuthenticationException으로 던져서, 이 예외가 어디서 발생하든
            // (컨트롤러 내부 포함) ExceptionTranslationFilter가 401로 변환하게 한다.
            // IllegalStateException이었다면 매핑하는 곳이 없어 500으로 노출됐다.
            return userRepository.findById(jwtToken.getUserId())
                    .orElseThrow(() -> new InsufficientAuthenticationException(
                            "인증된 사용자를 찾을 수 없습니다: userId=" + jwtToken.getUserId()));
        }
        throw new InsufficientAuthenticationException("지원하지 않는 인증 타입: " + authentication.getClass());
    }
}
