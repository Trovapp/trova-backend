package com.trova.backend.service;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtAuthenticationToken;
import com.trova.backend.security.KnownUsers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentUserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Spy
    private KnownUsers knownUsers = new KnownUsers();

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
                .isInstanceOf(InsufficientAuthenticationException.class);
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
                .isInstanceOf(InsufficientAuthenticationException.class);
    }

    @Test
    void authentication이_null이면_예외를_던진다() {
        assertThatThrownBy(() -> currentUserService.resolve(null))
                .isInstanceOf(InsufficientAuthenticationException.class);
    }

    @Test
    void 사용자_ID_확인은_처음_한번만_DB를_보고_다음부터는_메모리에서_확인한다() {
        when(userRepository.existsById(7L)).thenReturn(true);

        assertThat(currentUserService.resolveUserId(new JwtAuthenticationToken(7L))).isEqualTo(7L);
        assertThat(currentUserService.resolveUserId(new JwtAuthenticationToken(7L))).isEqualTo(7L);

        verify(userRepository, times(1)).existsById(7L);
    }

    @Test
    void 없는_사용자의_ID면_인증_예외를_던지고_기억하지_않는다() {
        when(userRepository.existsById(8L)).thenReturn(false);

        assertThatThrownBy(() -> currentUserService.resolveUserId(new JwtAuthenticationToken(8L)))
                .isInstanceOf(InsufficientAuthenticationException.class);
        assertThatThrownBy(() -> currentUserService.resolveUserId(new JwtAuthenticationToken(8L)))
                .isInstanceOf(InsufficientAuthenticationException.class);

        verify(userRepository, times(2)).existsById(8L);
    }

    @Test
    void 탈퇴로_기억에서_빠진_사용자는_다시_DB로_확인한다() {
        when(userRepository.existsById(9L)).thenReturn(true, false);
        currentUserService.resolveUserId(new JwtAuthenticationToken(9L));

        knownUsers.remove(9L);

        assertThatThrownBy(() -> currentUserService.resolveUserId(new JwtAuthenticationToken(9L)))
                .isInstanceOf(InsufficientAuthenticationException.class);
    }

    @Test
    void 인증이_없으면_사용자_ID_확인도_예외를_던진다() {
        assertThatThrownBy(() -> currentUserService.resolveUserId(null))
                .isInstanceOf(InsufficientAuthenticationException.class);
    }
}
