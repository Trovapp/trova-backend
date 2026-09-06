package com.trova.backend.service;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtAuthenticationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentUserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CurrentUserService currentUserService;

    private OAuth2AuthenticationToken tokenFor(String sub) {
        OAuth2User principal = new DefaultOAuth2User(
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")),
                Map.of("sub", sub, "name", "테스트", "picture", "https://example.com/p.jpg"),
                "sub"
        );
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
    }

    @Test
    void 등록된_사용자를_찾아서_반환한다() {
        User user = new User("google", "42", "테스트", null);
        when(userRepository.findByProviderAndProviderUserId("google", "42"))
                .thenReturn(Optional.of(user));

        User resolved = currentUserService.resolve(tokenFor("42"));

        assertThat(resolved).isEqualTo(user);
    }

    @Test
    void 등록되지_않은_사용자면_예외를_던진다() {
        when(userRepository.findByProviderAndProviderUserId("google", "99"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> currentUserService.resolve(tokenFor("99")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void JWT_토큰이면_userId로_사용자를_찾아서_반환한다() {
        User user = new User("google", "42", "테스트", null);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        User resolved = currentUserService.resolve(new JwtAuthenticationToken(7L));

        assertThat(resolved).isEqualTo(user);
    }

    @Test
    void JWT_토큰인데_사용자가_없으면_예외를_던진다() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> currentUserService.resolve(new JwtAuthenticationToken(99L)))
                .isInstanceOf(IllegalStateException.class);
    }
}
