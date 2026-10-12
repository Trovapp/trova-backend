package com.trova.backend.admin;

import com.trova.backend.entity.AdminActionLog;
import com.trova.backend.entity.User;
import com.trova.backend.repository.AdminActionLogRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {"app.admin.users=google:web-admin", "app.admin-web-url=https://admin.example.dev"})
class AdminWebLoginServiceTest {

    @Autowired
    private AdminWebLoginService service;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminActionLogRepository actionLogRepository;

    @Autowired
    private JwtService jwtService;

    private List<AdminActionLog> logsOf(User user) {
        return actionLogRepository.findAll().stream().filter(l -> user.getId().equals(l.getActorUserId())).toList();
    }

    @Test
    void 관리자면_교환_코드를_붙여_관리자_웹으로_보내고_로그인을_기록한다() {
        User admin = userRepository.save(new User("google", "web-admin", "관리자", null));

        String redirect = service.loginRedirect(admin);

        assertThat(redirect).startsWith("https://admin.example.dev/auth/callback/?code=");
        assertThat(logsOf(admin)).extracting(AdminActionLog::getAction).containsExactly("ADMIN_WEB_LOGIN");
    }

    @Test
    void 관리자가_아니면_코드_없이_거부로_보내고_거부를_기록한다() {
        User normal = userRepository.save(new User("google", "web-normal", "일반", null));

        String redirect = service.loginRedirect(normal);

        assertThat(redirect).isEqualTo("https://admin.example.dev/auth/callback/?error=forbidden");
        assertThat(logsOf(normal)).extracting(AdminActionLog::getAction).containsExactly("ADMIN_WEB_LOGIN_DENIED");
    }

    @Test
    void 교환_코드로_1시간짜리_관리자_웹_토큰을_받고_같은_코드는_다시_못_쓴다() {
        User admin = userRepository.save(new User("google", "web-admin", "관리자", null));
        String code = service.loginRedirect(admin).substring("https://admin.example.dev/auth/callback/?code=".length());

        AdminWebLoginService.AdminWebToken token = service.exchange(code);

        assertThat(token.expiresInSeconds()).isEqualTo(3600);
        JwtService.VerifiedToken verified = jwtService.parse(token.token());
        assertThat(verified.userId()).isEqualTo(admin.getId());
        assertThat(verified.adminWeb()).isTrue();
        assertThatThrownBy(() -> service.exchange(code))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void 잘못된_코드는_400이다() {
        assertThatThrownBy(() -> service.exchange("wrong"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void 실패한_로그인은_관리자_웹의_오류_화면으로_보낸다() {
        assertThat(service.failureRedirect()).isEqualTo("https://admin.example.dev/auth/callback/?error=oauth_failed");
        assertThat(service.enabled()).isTrue();
    }

    @Autowired
    private AdminExchangeCodeStore codeStore;

    @Test
    void 코드를_받은_뒤_관리자에서_빠지면_교환은_403이다() {
        User removed = userRepository.save(new User("google", "web-removed", "빠진 관리자", null));
        String code = codeStore.issue(removed.getId());

        assertThatThrownBy(() -> service.exchange(code))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void 관리자가_아닌_사용자의_거부는_1분에_한_번만_기록한다() {
        // 관리자가 아닌 사용자가 로그인을 되풀이해도 지우지 않는 감사 기록이 끝없이 쌓이지 않게(리뷰 지적, #163).
        User normal = userRepository.save(new User("google", "web-repeat", "일반", null));

        service.loginRedirect(normal);
        service.loginRedirect(normal);
        service.loginRedirect(normal);

        assertThat(logsOf(normal)).hasSize(1);
    }
}
