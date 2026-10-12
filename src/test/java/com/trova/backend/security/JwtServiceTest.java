package com.trova.backend.security;

import com.trova.backend.entity.User;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-jwt-secret-please-do-not-use-in-production-environment";

    private final JwtService jwtService = new JwtService(SECRET);

    @Test
    void 발급한_토큰을_검증하면_같은_유저ID를_돌려준다() {
        User user = new User("google", "42", "테스트", null);
        setId(user, 7L);

        String token = jwtService.issue(user);

        assertThat(jwtService.verify(token)).isEqualTo(7L);
    }

    @Test
    void 서명이_다른_토큰은_검증에_실패한다() {
        JwtService otherService = new JwtService("different-secret-key-that-is-also-long-enough-for-hs256");
        User user = new User("google", "42", "테스트", null);
        setId(user, 7L);
        String token = otherService.issue(user);

        assertThatThrownBy(() -> jwtService.verify(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void 만료된_토큰은_검증에_실패한다() {
        JwtService shortLivedService = new JwtService(SECRET, java.time.Duration.ofMillis(1));
        User user = new User("google", "42", "테스트", null);
        setId(user, 7L);
        String token = shortLivedService.issue(user);

        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThatThrownBy(() -> jwtService.verify(token)).isInstanceOf(JwtException.class);
    }

    private void setId(User user, Long id) {
        try {
            java.lang.reflect.Field field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void 관리자_웹_토큰은_1시간짜리이고_관리자_웹_표시가_있다() {
        User user = new User("google", "42", "테스트", null);
        setId(user, 7L);

        String token = jwtService.issueAdminWeb(user);

        JwtService.VerifiedToken verified = jwtService.parse(token);
        assertThat(verified.userId()).isEqualTo(7L);
        assertThat(verified.adminWeb()).isTrue();
        assertThat(verified.expiresAt()).isBetween(java.time.Instant.now().plusSeconds(3590), java.time.Instant.now().plusSeconds(3601));
    }

    @Test
    void 앱_토큰은_관리자_웹_토큰이_아니다() {
        User user = new User("google", "42", "테스트", null);
        setId(user, 7L);

        assertThat(jwtService.parse(jwtService.issue(user)).adminWeb()).isFalse();
    }
}
