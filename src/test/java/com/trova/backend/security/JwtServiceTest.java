package com.trova.backend.security;

import com.trova.backend.entity.User;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

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
    void 같은_파서는_유효했던_토큰도_만료_뒤에는_거부한다() {
        JwtService shortLivedService = new JwtService(SECRET, java.time.Duration.ofSeconds(3));
        User user = new User("google", "42", "테스트", null);
        setId(user, 7L);
        String token = shortLivedService.issue(user);

        assertThat(shortLivedService.verify(token)).isEqualTo(7L);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(6)).untilAsserted(() ->
                assertThatThrownBy(() -> shortLivedService.verify(token)).isInstanceOf(ExpiredJwtException.class));
    }

    @Test
    void 같은_서명키의_검증_파서는_한_번만_만든다() {
        User user = new User("google", "42", "테스트", null);
        setId(user, 7L);
        String app = jwtService.issue(user);
        String admin = jwtService.issueAdminWeb(user);

        try (MockedStatic<Jwts> factories = mockStatic(Jwts.class, CALLS_REAL_METHODS)) {
            JwtService verifier = new JwtService(SECRET);
            assertThat(verifier.parse(app).adminWeb()).isFalse();
            assertThat(verifier.parse(admin).adminWeb()).isTrue();
            factories.verify(Jwts::parser);
        }
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
