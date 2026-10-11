package com.trova.backend.admin;

import com.trova.backend.entity.AdminActionLog;
import com.trova.backend.entity.User;
import com.trova.backend.repository.AdminActionLogRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import com.trova.backend.service.AdminAccessService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 관리자 웹 로그인(ADR 0012, #163): OAuth 로그인 성공 → 관리자면 1회용 코드를 붙여 관리자 웹으로 → 웹이 코드를 1시간 토큰으로 바꾼다.
 * 관리자가 아니면 코드를 만들지 않는다. 성공·거부를 감사 기록에 남긴다(내부 번호만).
 */
@Service
public class AdminWebLoginService {

    public record AdminWebToken(String token, long expiresInSeconds) {
    }

    // 관리자 웹은 정적 사이트(trailingSlash)라 끝에 /를 붙인다 — 붙이지 않으면 호스팅이 /로 다시 보내며 ?code=를 잃을 수 있다(웹 #5 리뷰).
    private static final String CALLBACK_PATH = "/auth/callback/";

    private final AdminAccessService adminAccessService;
    private final AdminExchangeCodeStore codeStore;
    private final AdminActionLogRepository actionLogRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RequestRateLimiter rateLimiter;
    private final String adminWebUrl;

    public AdminWebLoginService(AdminAccessService adminAccessService, AdminExchangeCodeStore codeStore,
                                AdminActionLogRepository actionLogRepository, UserRepository userRepository,
                                JwtService jwtService, RequestRateLimiter rateLimiter,
                                @Value("${app.admin-web-url:}") String adminWebUrl) {
        this.adminAccessService = adminAccessService;
        this.codeStore = codeStore;
        this.actionLogRepository = actionLogRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
        String url = adminWebUrl == null ? "" : adminWebUrl.trim();
        this.adminWebUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 관리자 웹 주소(APP_ADMIN_WEB_URL)가 없으면 관리자 웹 로그인은 꺼진다. */
    public boolean enabled() {
        return !adminWebUrl.isEmpty();
    }

    @Transactional
    public String loginRedirect(User user) {
        if (!adminAccessService.isAdmin(user)) {
            // 거부는 사용자당 1분에 한 번만 남긴다 — 되풀이해도 지우지 않는 감사 기록이 끝없이 쌓이지 않게(리뷰 지적, #163).
            if (rateLimiter.tryAcquire("admin-web-denied-log:" + user.getId(), 1)) {
                actionLogRepository.save(new AdminActionLog(user.getId(), AdminActionLog.ADMIN_WEB_LOGIN_DENIED, null, null, null));
            }
            return adminWebUrl + CALLBACK_PATH + "?error=forbidden";
        }
        actionLogRepository.save(new AdminActionLog(user.getId(), AdminActionLog.ADMIN_WEB_LOGIN, null, null, null));
        return adminWebUrl + CALLBACK_PATH + "?code=" + codeStore.issue(user.getId());
    }

    public String failureRedirect() {
        return adminWebUrl + CALLBACK_PATH + "?error=oauth_failed";
    }

    /** 없는·만료·이미 쓴 코드는 400. 코드를 받은 뒤 관리자 명단에서 빠졌으면 403. */
    @Transactional(readOnly = true)
    public AdminWebToken exchange(String code) {
        Long userId = codeStore.consume(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못되었거나 만료된 코드입니다"));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못되었거나 만료된 코드입니다"));
        if (!adminAccessService.isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return new AdminWebToken(jwtService.issueAdminWeb(user), JwtService.ADMIN_WEB_EXPIRY.toSeconds());
    }
}
