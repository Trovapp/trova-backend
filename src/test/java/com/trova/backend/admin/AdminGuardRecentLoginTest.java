package com.trova.backend.admin;

import com.trova.backend.security.JwtAuthenticationToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = "app.admin.users=google:recent-admin")
class AdminGuardRecentLoginTest {

    @Autowired
    private AdminGuard adminGuard;

    @Test
    void 발급_10분_안이면_통과한다() {
        JwtAuthenticationToken token = new JwtAuthenticationToken(1L, true, Instant.now().minusSeconds(60));
        assertThatCode(() -> adminGuard.requireRecentLogin(token, Duration.ofMinutes(10))).doesNotThrowAnyException();
    }

    @Test
    void 발급_10분이_지나면_재로그인_예외() {
        JwtAuthenticationToken token = new JwtAuthenticationToken(1L, true, Instant.now().minusSeconds(601));
        assertThatThrownBy(() -> adminGuard.requireRecentLogin(token, Duration.ofMinutes(10)))
                .isInstanceOf(AdminReauthRequiredException.class);
    }

    @Test
    void 발급_시각이_없으면_재로그인_예외() {
        JwtAuthenticationToken token = new JwtAuthenticationToken(1L, true, null);
        assertThatThrownBy(() -> adminGuard.requireRecentLogin(token, Duration.ofMinutes(10)))
                .isInstanceOf(AdminReauthRequiredException.class);
    }
}
