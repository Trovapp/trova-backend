# 관리자 조치(대시보드 2단계) 백엔드 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관리자가 관리자 웹에서 사용자를 찾아 상세를 보고(가린 값), 여행 삭제·계정 삭제·분석 재실행·자동 초안 숨기기·계정 정지/풀기를 미리보기 → 사유·확인 → 실행으로 처리하고, 모든 조회·조치가 감사 기록에 남게 한다.

**Architecture:** `admin/user`(찾기·상세, 읽기)와 `admin/action`(조치)을 새 기능 패키지로 둔다. 조치는 `AdminActionHandler` 하나당 파일 하나(미리보기·실행), 공통 흐름(재인증 10분·확인 토큰 5분 1회·사유·확인 글자·감사 기록)은 `AdminActionService`가 맡는다. 정지는 `users.suspended_at` + 서버 메모리 정지 목록(`SuspendedUsers`)으로 JWT·단축어 키 필터와 로그인 성공 처리에서 막는다.

**Tech Stack:** Spring Boot(Java 21), JPA(ddl-auto update), jjwt 0.12.6, JUnit5 + MockMvc + H2(테스트).

**Spec:** `docs/superpowers/specs/2026-10-11-admin-dashboard-design.md`(2단계 + "2단계 보강"), `docs/adr/0014-admin-actions-and-suspension.md`

## Global Constraints

- 들여쓰기 4칸, 생성자 주입, Lombok 없음, 요청·응답은 `record`, 와일드카드 import·`var` 금지, 테스트 이름은 한글 문장(CLAUDE.md).
- 모든 `/api/admin/**`는 `AdminGuard.requireAdmin`(관리자 웹 토큰만, 분당 120회). `AdminEndpointsSecurityTest`가 새 경로도 자동으로 훑는다 — 관리자 웹 토큰으로 401·403이 나오면 안 된다.
- 실행(쓰기) 조치는 관리자 웹 토큰 발급(iat) 10분 안만. 지나면 403 + `{"error":"REAUTH_REQUIRED"}`.
- 확인 토큰: 5분, 1회, 관리자·조치·대상에 묶임. 미리보기 없이 실행 불가.
- 사유 필수(공백 아님, 최대 300자). 여행 삭제는 여행 이름, 계정 삭제는 `계정 삭제`를 그대로 다시 입력해야 실행.
- 감사 기록 `admin_action_logs`에 개인정보(닉네임·로그인 고유번호·이메일·검색어) 금지 — 내부 번호·건수만.
- 관리자 계정(`ADMIN_USERS`)은 정지·삭제 불가.
- 닉네임은 첫 글자 + 나머지 `*`(최대 `*` 4개), 로그인 고유번호·프로필 사진은 응답에 넣지 않는다.
- 분석 재실행은 일시 오류(`AI_QUOTA`, `SOURCE_RATE_LIMITED`, `STALE`)로 실패한 작업만, 오늘 Gemini 호출이 `app.admin.dashboard.gemini-alert`(800) 미만일 때만. 기존 작업은 그대로 두고 새 작업을 만든다.
- 커밋 메시지 `타입: 내용`만, 트레일러 없음. 커밋 전 `./gradlew build`.

## Review Focus

1. 정지한 사용자가 이미 들고 있는 앱 토큰(90일)으로 API를 부르면 → 401(다음 요청부터 바로). Task 3 테스트.
2. 같은 확인 토큰을 두 번 보내거나, 다른 대상·다른 조치에 쓰면 → 409, 아무것도 바뀌지 않음. Task 5 테스트.
3. 미리보기 뒤 실행 전에 대상이 사라짐(사용자가 직접 여행 삭제) → 404, 감사 기록에는 실패로 남지 않고 조치도 없음. Task 6 테스트.
4. 사유가 공백·301자 → 400. 확인 글자가 앞뒤 공백만 다름 → 통과(strip 비교), 대소문자·글자 다름 → 400. Task 5 테스트.
5. 관리자 자신·다른 관리자 계정을 정지/삭제 미리보기 → 409(미리보기 단계에서 거부). Task 7 테스트.

---

### Task 1: 토큰 발급 시각(iat)을 인증 정보에 싣고 `AdminGuard.requireRecentLogin` 추가

**Files:**
- Modify: `src/main/java/com/trova/backend/security/JwtService.java` (VerifiedToken에 `issuedAt`)
- Modify: `src/main/java/com/trova/backend/security/JwtAuthenticationToken.java` (`issuedAt` 필드·게터)
- Modify: `src/main/java/com/trova/backend/security/JwtAuthenticationFilter.java`
- Modify: `src/main/java/com/trova/backend/admin/AdminGuard.java`
- Test: `src/test/java/com/trova/backend/admin/AdminGuardRecentLoginTest.java`

**Interfaces:**
- Produces: `JwtService.VerifiedToken(Long userId, boolean adminWeb, Instant expiresAt, Instant issuedAt)`; `JwtAuthenticationToken(Long userId, boolean adminWeb, Instant issuedAt)`(기존 생성자 2개는 issuedAt=null로 유지); `AdminGuard.requireRecentLogin(Authentication, Duration maxAge)` — 오래됐으면 `AdminReauthRequiredException`(403, 본문 `{"error":"REAUTH_REQUIRED"}`).

- [ ] **Step 1: 실패 테스트**

```java
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
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests AdminGuardRecentLoginTest` → 컴파일 실패(생성자·메서드 없음).

- [ ] **Step 3: 구현**

`JwtService`:
```java
    public record VerifiedToken(Long userId, boolean adminWeb, Instant expiresAt, Instant issuedAt) {
    }
    // parse(): claims.getIssuedAt()이 null일 수 있다(예전 토큰).
        Instant issuedAt = claims.getIssuedAt() == null ? null : claims.getIssuedAt().toInstant();
        return new VerifiedToken(Long.valueOf(claims.getSubject()), adminWeb, claims.getExpiration().toInstant(), issuedAt);
```
`issue`·`issueAdminWeb`이 `.issuedAt(now)`를 이미 넣는지 확인하고, 없으면 넣는다.

`JwtAuthenticationToken`:
```java
    private final Instant issuedAt;

    public JwtAuthenticationToken(Long userId) {
        this(userId, false, null);
    }

    public JwtAuthenticationToken(Long userId, boolean adminWeb) {
        this(userId, adminWeb, null);
    }

    /** issuedAt: 토큰 발급 시각 — 관리자 실행 조치의 10분 재인증에 쓴다(#167). */
    public JwtAuthenticationToken(Long userId, boolean adminWeb, Instant issuedAt) {
        super(List.of(new SimpleGrantedAuthority("ROLE_USER")));
        this.userId = userId;
        this.adminWeb = adminWeb;
        this.issuedAt = issuedAt;
        setAuthenticated(true);
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }
```
`JwtAuthenticationFilter`: `new JwtAuthenticationToken(verified.userId(), verified.adminWeb(), verified.issuedAt())`.

`src/main/java/com/trova/backend/admin/AdminReauthRequiredException.java`:
```java
package com.trova.backend.admin;

/** 관리자 실행 조치는 로그인(관리자 웹 토큰 발급) 10분 안에만 — 지나면 화면이 다시 로그인을 안내한다(ADR 0014). */
public class AdminReauthRequiredException extends RuntimeException {

    public AdminReauthRequiredException() {
        super("REAUTH_REQUIRED");
    }
}
```
`src/main/java/com/trova/backend/controller/AdminExceptionHandler.java`:
```java
package com.trova.backend.controller;

import com.trova.backend.admin.AdminReauthRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class AdminExceptionHandler {

    @ExceptionHandler(AdminReauthRequiredException.class)
    public ResponseEntity<Map<String, String>> reauth() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "REAUTH_REQUIRED"));
    }
}
```
`AdminGuard`:
```java
    /** 실행 조치 전용 — requireAdmin 뒤에 부른다. */
    public void requireRecentLogin(Authentication authentication, Duration maxAge) {
        if (!(authentication instanceof JwtAuthenticationToken token) || token.getIssuedAt() == null
                || token.getIssuedAt().isBefore(Instant.now().minus(maxAge))) {
            throw new AdminReauthRequiredException();
        }
    }
```

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests AdminGuardRecentLoginTest --tests '*Jwt*' --tests AdminEndpointsSecurityTest` → PASS.

- [ ] **Step 5: 커밋** — `git commit -m "feat: 관리자 실행 조치용 토큰 발급 시각 확인(10분 재인증)"`

---

### Task 2: 감사 기록에 대상 사용자 칸·새 조치 이름, 기록 서비스

**Files:**
- Modify: `src/main/java/com/trova/backend/entity/AdminActionLog.java`
- Create: `src/main/java/com/trova/backend/admin/action/AdminAuditLog.java`
- Modify: `src/main/java/com/trova/backend/repository/AdminActionLogRepository.java`
- Test: `src/test/java/com/trova/backend/admin/action/AdminAuditLogTest.java`

**Interfaces:**
- Produces: 상수 `ADMIN_USER_SEARCH`, `ADMIN_USER_VIEW`(조회), 조치 이름은 Task 4의 `AdminAction.name()`; 생성자 `AdminActionLog(Long actorUserId, String action, Long targetId, Long targetUserId, String reason, String result)`(기존 5인자 생성자는 targetUserId=null로 위임); `getTargetUserId()`.
- `AdminAuditLog.record(Long actorId, String action, Long targetId, Long targetUserId, String reason, String result)` — `REQUIRES_NEW` 트랜잭션(조치가 실패해 되돌려져도 기록은 남김).
- `AdminActionLogRepository.findTop50ByOrderByIdDesc()`, `findTop50ByTargetUserIdOrderByIdDesc(Long targetUserId)`.

- [ ] **Step 1: 실패 테스트**

```java
package com.trova.backend.admin.action;

import com.trova.backend.entity.AdminActionLog;
import com.trova.backend.repository.AdminActionLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AdminAuditLogTest {

    @Autowired
    private AdminAuditLog auditLog;

    @Autowired
    private AdminActionLogRepository repository;

    @Test
    void 대상_사용자별로_최근_기록을_모은다() {
        auditLog.record(1L, AdminActionLog.ADMIN_USER_VIEW, 900L, 900L, null, null);
        auditLog.record(1L, "DELETE_TRIP", 55L, 900L, "사용자 요청", "여행 1·일차 2·장소 5");
        auditLog.record(1L, AdminActionLog.ADMIN_USER_VIEW, 901L, 901L, null, null);

        List<AdminActionLog> logs = repository.findTop50ByTargetUserIdOrderByIdDesc(900L);
        assertThat(logs).extracting(AdminActionLog::getAction).containsExactly("DELETE_TRIP", AdminActionLog.ADMIN_USER_VIEW);
        assertThat(logs.get(0).getTargetId()).isEqualTo(55L);
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests AdminAuditLogTest` → 컴파일 실패.

- [ ] **Step 3: 구현**

`AdminActionLog`에 추가:
```java
    public static final String ADMIN_USER_SEARCH = "ADMIN_USER_SEARCH";
    public static final String ADMIN_USER_VIEW = "ADMIN_USER_VIEW";

    // 이 기록이 어느 사용자에 대한 것인지(여행·작업이 대상이어도 그 주인) — 사용자 상세의 조치 이력(#167).
    @Column(name = "target_user_id", updatable = false)
    private Long targetUserId;

    public AdminActionLog(Long actorUserId, String action, Long targetId, String reason, String result) {
        this(actorUserId, action, targetId, null, reason, result);
    }

    public AdminActionLog(Long actorUserId, String action, Long targetId, Long targetUserId, String reason, String result) {
        this.createdAt = LocalDateTime.now();
        this.actorUserId = actorUserId;
        this.action = action;
        this.targetId = targetId;
        this.targetUserId = targetUserId;
        this.reason = reason;
        this.result = result;
    }

    public Long getTargetUserId() { return targetUserId; }
```
`@Table` indexes에 `@Index(name = "idx_admin_action_logs_target_user", columnList = "target_user_id")` 추가. 클래스 주석의 "지금(#163)은…" 문장을 "로그인·조회(#167)·조치를 남긴다. 최소 1년 보관(ADR 0014)."으로 바꾼다.

`AdminAuditLog`:
```java
package com.trova.backend.admin.action;

import com.trova.backend.entity.AdminActionLog;
import com.trova.backend.repository.AdminActionLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 조회·조치 감사 기록(ADR 0012·0014). 개인정보 없이 내부 번호·건수만 넘긴다. */
@Service
public class AdminAuditLog {

    private final AdminActionLogRepository repository;

    public AdminAuditLog(AdminActionLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long actorId, String action, Long targetId, Long targetUserId, String reason, String result) {
        repository.save(new AdminActionLog(actorId, action, targetId, targetUserId, reason, result));
    }
}
```
저장소에 두 메서드 추가.

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests AdminAuditLogTest --tests '*AdminWebLogin*'` → PASS.
- [ ] **Step 5: 커밋** — `git commit -m "feat: 관리자 감사 기록에 대상 사용자 칸과 조회 기록 추가"`

---

### Task 3: 계정 정지 상태와 정지 목록, 인증·로그인에서 막기

**Files:**
- Modify: `src/main/java/com/trova/backend/entity/User.java`(`suspended_at`)
- Modify: `src/main/java/com/trova/backend/repository/UserRepository.java`(`findIdsBySuspendedAtIsNotNull`)
- Create: `src/main/java/com/trova/backend/security/SuspendedUsers.java`
- Modify: `JwtAuthenticationFilter.java`, `ShareKeyAuthenticationFilter.java`, `OAuth2LoginSuccessHandler.java`
- Modify: `src/main/java/com/trova/backend/service/WebAppLoginService.java`, `src/main/java/com/trova/backend/admin/AdminWebLoginService.java`(정지 주소 메서드)
- Test: `src/test/java/com/trova/backend/security/SuspendedUserAccessTest.java`

**Interfaces:**
- Produces: `User.suspend()`, `User.unsuspend()`, `User.getSuspendedAt()`, `User.isSuspended()`; `SuspendedUsers.isSuspended(Long userId)`, `SuspendedUsers.add(Long)`, `SuspendedUsers.remove(Long)`(서버 시작 때 DB에서 채움); 로그인 정지 주소 — 앱 `trova://auth?error=suspended`, PWA `<APP_WEB_APP_URL>/auth/callback/?error=suspended`, 그 밖 `<frontendUrl>/login?error=suspended`.

- [ ] **Step 1: 실패 테스트**

```java
package com.trova.backend.security;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.service.ShareKeyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SuspendedUserAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private SuspendedUsers suspendedUsers;

    @Autowired
    private ShareKeyService shareKeyService;

    private User user;

    @AfterEach
    void tearDown() {
        if (user != null) {
            suspendedUsers.remove(user.getId());
        }
    }

    @Test
    void 정지하면_이미_받은_앱_토큰도_다음_요청부터_401() throws Exception {
        user = userRepository.save(new User("google", "suspend-jwt", "정지", null));
        String token = jwtService.issue(user);
        mockMvc.perform(get("/api/places").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        suspendedUsers.add(user.getId());

        mockMvc.perform(get("/api/places").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void 정지하면_단축어_키로도_저장할_수_없다() throws Exception {
        user = userRepository.save(new User("google", "suspend-key", "정지", null));
        String key = shareKeyService.issue(user.getId()).key();
        suspendedUsers.add(user.getId());

        mockMvc.perform(post("/api/shares").header(ShareKeyAuthenticationFilter.HEADER, key)
                        .contentType("application/json").content("{\"url\":\"https://www.youtube.com/shorts/abc\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 서버가_뜰_때_DB의_정지_계정을_목록에_싣는다() {
        user = userRepository.save(new User("google", "suspend-load", "정지", null));
        user.suspend();
        userRepository.save(user);

        suspendedUsers.reload();

        org.assertj.core.api.Assertions.assertThat(suspendedUsers.isSuspended(user.getId())).isTrue();
    }
}
```
(`/api/places`가 로그인 사용자 200을 주는지 기존 테스트에서 확인하고, 다르면 로그인만 필요한 다른 GET으로 바꾼다.)

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests SuspendedUserAccessTest` → 컴파일 실패.

- [ ] **Step 3: 구현**

`User`:
```java
    // 관리자 계정 정지(#167, ADR 0014). null이면 정상. 정지하면 토큰·단축어 키·로그인이 막힌다.
    @Column(name = "suspended_at")
    private LocalDateTime suspendedAt;

    public void suspend() {
        this.suspendedAt = LocalDateTime.now();
    }

    public void unsuspend() {
        this.suspendedAt = null;
    }

    public LocalDateTime getSuspendedAt() {
        return suspendedAt;
    }

    public boolean isSuspended() {
        return suspendedAt != null;
    }
```
`UserRepository`:
```java
    @Query("select u.id from User u where u.suspendedAt is not null")
    List<Long> findSuspendedIds();
```
`SuspendedUsers`:
```java
package com.trova.backend.security;

import com.trova.backend.repository.UserRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 정지된 계정 번호(ADR 0014). 요청마다 DB를 읽지 않으려고 메모리에 둔다 — 운영 서버가 1대라 충분하다.
 * 서버가 뜰 때 DB에서 채우고, 정지·풀기 조치가 바로 고친다.
 */
@Component
public class SuspendedUsers {

    private final UserRepository userRepository;
    private final Set<Long> ids = ConcurrentHashMap.newKeySet();

    public SuspendedUsers(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reload() {
        ids.clear();
        ids.addAll(userRepository.findSuspendedIds());
    }

    public boolean isSuspended(Long userId) {
        return userId != null && ids.contains(userId);
    }

    public void add(Long userId) {
        ids.add(userId);
    }

    public void remove(Long userId) {
        ids.remove(userId);
    }
}
```
`JwtAuthenticationFilter`: 생성자에 `SuspendedUsers` 주입, 인증을 넣는 조건에 `&& !suspendedUsers.isSuspended(verified.userId())` 추가(정지면 인증 없이 다음 필터 → 401). 필터를 `new`로 만드는 설정 클래스가 있으면 함께 고친다.
`ShareKeyAuthenticationFilter`: `key.isPresent() && !suspendedUsers.isSuspended(key.get().getUserId())`일 때만 인증.
`OAuth2LoginSuccessHandler`: 맨 앞에서 정지 확인.
```java
        User loginUser = currentUserService.resolve(authentication);
        if (loginUser.isSuspended()) {
            String redirect = suspendedRedirect(session);
            if (session != null) {
                session.invalidate();
            }
            response.sendRedirect(redirect);
            return;
        }
```
```java
    // 정지 계정은 어느 로그인 흐름이든 토큰·코드를 주지 않고 그 흐름의 실패 주소로 보낸다(#167).
    private String suspendedRedirect(HttpSession session) {
        if (session != null && Boolean.TRUE.equals(session.getAttribute("MOBILE_LOGIN"))) {
            return mobileRedirectScheme + "://auth?error=suspended";
        }
        if (session != null && Boolean.TRUE.equals(session.getAttribute(MobileLoginFlagFilter.WEB_APP_LOGIN))
                && webAppLoginService.enabled()) {
            return webAppLoginService.suspendedRedirect();
        }
        if (session != null && Boolean.TRUE.equals(session.getAttribute(MobileLoginFlagFilter.ADMIN_WEB_LOGIN))
                && adminWebLoginService.enabled()) {
            return adminWebLoginService.failureRedirect();
        }
        return frontendUrl + "/login?error=suspended";
    }
```
`WebAppLoginService.suspendedRedirect()`는 `webAppUrl + CALLBACK_PATH + "?error=suspended"`.

테스트 하나 더(같은 파일): `OAuth2LoginSuccessHandler`를 정지 사용자 인증과 `MOBILE_LOGIN` 세션으로 직접 불러 `trova://auth?error=suspended`로 보내는지(기존 `OAuth2LoginSuccessHandler` 테스트가 있으면 그 방식대로).

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests SuspendedUserAccessTest --tests '*Login*' --tests '*ShareKey*'` → PASS.
- [ ] **Step 5: 커밋** — `git commit -m "feat: 계정 정지 상태와 토큰·단축어 키·로그인 차단"`

---

### Task 4: 조치 공통 틀 — 조치 종류, 미리보기 결과, 확인 토큰 저장소

**Files:**
- Create: `src/main/java/com/trova/backend/admin/action/AdminAction.java`
- Create: `src/main/java/com/trova/backend/admin/action/ActionPreview.java`
- Create: `src/main/java/com/trova/backend/admin/action/AdminActionHandler.java`
- Create: `src/main/java/com/trova/backend/admin/action/ConfirmTokenStore.java`
- Create: `src/main/java/com/trova/backend/admin/action/AdminActionException.java`
- Test: `src/test/java/com/trova/backend/admin/action/ConfirmTokenStoreTest.java`

**Interfaces:**
```java
public enum AdminAction { DELETE_TRIP, DELETE_ACCOUNT, RETRY_JOB, HIDE_DRAFT, SUSPEND_USER, UNSUSPEND_USER }

/** targetUserId: 대상의 주인. impact: 화면에 보일 영향 범위(예 "여행 1·일차 3·장소 12"). confirmText: 다시 입력해야 할 글자(없으면 null). */
public record ActionPreview(AdminAction action, Long targetId, Long targetUserId, String impact, String confirmText) { }

public interface AdminActionHandler {
    AdminAction action();
    /** 대상이 없으면 AdminActionException.notFound, 조건이 안 맞으면 AdminActionException.conflict(사유). */
    ActionPreview preview(Long targetId, Long adminUserId);
    /** 실행하고 결과 한 줄(건수)을 돌려준다. preview와 같은 검사를 다시 한다. */
    String execute(Long targetId, Long adminUserId);
}

public class AdminActionException extends RuntimeException {
    public static AdminActionException notFound() …        // 404
    public static AdminActionException conflict(String message) … // 409, 화면에 보일 문구
    public static AdminActionException badRequest(String message) … // 400
    public HttpStatus status()
}

public class ConfirmTokenStore {
    public static final Duration TTL = Duration.ofMinutes(5);
    public record Pending(Long adminUserId, AdminAction action, Long targetId, Long targetUserId, String confirmText) { }
    public String issue(Pending pending)                  // 5분, 32바이트 랜덤(Base64 URL)
    public Optional<Pending> consume(String token)        // 1회 — 꺼내면 지운다, 만료면 empty
}
```

- [ ] **Step 1: 실패 테스트**

```java
package com.trova.backend.admin.action;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmTokenStoreTest {

    private static final ConfirmTokenStore.Pending PENDING =
            new ConfirmTokenStore.Pending(1L, AdminAction.DELETE_TRIP, 55L, 900L, "제주 여행");

    @Test
    void 한_번만_꺼낼_수_있다() {
        ConfirmTokenStore store = new ConfirmTokenStore(Clock.systemDefaultZone());
        String token = store.issue(PENDING);

        assertThat(store.consume(token)).contains(PENDING);
        assertThat(store.consume(token)).isEmpty();
    }

    @Test
    void 5분이_지나면_꺼낼_수_없다() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-12T00:00:00Z"));
        ConfirmTokenStore store = new ConfirmTokenStore(clock);
        String token = store.issue(PENDING);

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThat(store.consume(token)).isEmpty();
    }

    @Test
    void 모르는_토큰은_비어_있다() {
        assertThat(new ConfirmTokenStore(Clock.systemDefaultZone()).consume("nope")).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
```
(`AdminExchangeCodeStoreTest`에 같은 용도의 시계가 있으면 그것을 쓴다.)

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests ConfirmTokenStoreTest` → 컴파일 실패.

- [ ] **Step 3: 구현** — `ConfirmTokenStore`는 `AdminExchangeCodeStore`와 같은 방식(`ConcurrentHashMap<String, Entry>`, `SecureRandom` 32바이트 → `Base64.getUrlEncoder().withoutPadding()`, 만료 5분, `issue` 때 만료 항목 정리, `consume`은 `map.remove`). `@Component`, 생성자 `ConfirmTokenStore(Clock clock)`(앱에 `Clock` 빈이 있는지 확인 — `AdminExchangeCodeStore`가 `Clock`을 받으므로 있다). `AdminActionException`은 `HttpStatus status` 필드와 정적 생성 메서드 3개. `AdminExceptionHandler`(Task 1)에 추가:

```java
    @ExceptionHandler(AdminActionException.class)
    public ResponseEntity<Map<String, String>> action(AdminActionException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
```

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests ConfirmTokenStoreTest` → PASS.
- [ ] **Step 5: 커밋** — `git commit -m "feat: 관리자 조치 공통 틀(조치 종류·미리보기·확인 토큰)"`

---

### Task 5: 조치 흐름 서비스와 API(미리보기 → 실행)

**Files:**
- Create: `src/main/java/com/trova/backend/admin/action/AdminActionService.java`
- Create: `src/main/java/com/trova/backend/controller/AdminActionsController.java`
- Test: `src/test/java/com/trova/backend/controller/AdminActionsControllerTest.java`

**Interfaces:**
- Consumes: Task 1 `requireRecentLogin`, Task 2 `AdminAuditLog`, Task 4 전부.
- Produces:
  - `POST /api/admin/actions/{action}/preview` 본문 `{"targetId": 55}` → 200 `{"action","targetId","targetUserId","impact","confirmText","confirmToken","expiresInSeconds":300}`
  - `POST /api/admin/actions/{action}/execute` 본문 `{"confirmToken","reason","confirmText"}` → 200 `{"action","targetId","result"}`
  - `GET /api/admin/actions?targetUserId=` → 200 `{"logs":[{"id","createdAt","actorUserId","action","targetId","targetUserId","reason","result"}]}`(최근 50개)
  - `{action}`은 `AdminAction` 이름의 소문자·하이픈(`delete-trip`, `delete-account`, `retry-job`, `hide-draft`, `suspend-user`, `unsuspend-user`), 모르는 값 404.
  - 실행 검사 순서: requireAdmin → requireRecentLogin(10분) → 토큰 consume(없거나 만료·다른 관리자·다른 조치면 409 "확인이 만료됐어요. 미리보기부터 다시 해 주세요.") → 사유(공백·300자 초과 400) → confirmText(필요할 때 strip 비교, 다르면 400) → handler.execute → 감사 기록(성공만, `targetUserId`는 Pending에 담아 둔 값).

- [ ] **Step 1: 실패 테스트** — 시험용 조치가 필요 없도록 Task 7의 `HIDE_DRAFT`보다 먼저 쓸 수 있는 가짜 핸들러를 테스트 설정으로 등록한다.

```java
package com.trova.backend.controller;

import com.trova.backend.admin.action.ActionPreview;
import com.trova.backend.admin.action.AdminAction;
import com.trova.backend.admin.action.AdminActionHandler;
import com.trova.backend.repository.AdminActionLogRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import com.trova.backend.support.AdminWebAuth;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.admin.users=google:act-admin,google:act-admin2")
class AdminActionsControllerTest {

    static final AtomicInteger EXECUTED = new AtomicInteger();

    @TestConfiguration
    static class FakeHandlerConfig {
        @Bean
        AdminActionHandler fakeUnsuspend() {
            return new AdminActionHandler() {
                @Override
                public AdminAction action() {
                    return AdminAction.UNSUSPEND_USER;
                }

                @Override
                public ActionPreview preview(Long targetId, Long adminUserId) {
                    return new ActionPreview(AdminAction.UNSUSPEND_USER, targetId, targetId, "계정 1", "확인");
                }

                @Override
                public String execute(Long targetId, Long adminUserId) {
                    EXECUTED.incrementAndGet();
                    return "풀었음";
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminActionLogRepository logRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private RequestPostProcessor admin() {
        return AdminWebAuth.adminWebToken(jwtService, userRepository, "act-admin");
    }

    private String preview(RequestPostProcessor who) throws Exception {
        String body = mockMvc.perform(post("/api/admin/actions/unsuspend-user/preview").with(who)
                        .contentType("application/json").content("{\"targetId\":77}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.impact").value("계정 1"))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        return node.get("confirmToken").asText();
    }

    private org.springframework.test.web.servlet.ResultActions execute(RequestPostProcessor who, String token,
                                                                      String reason, String confirmText) throws Exception {
        String json = objectMapper.writeValueAsString(java.util.Map.of(
                "confirmToken", token, "reason", reason, "confirmText", confirmText));
        return mockMvc.perform(post("/api/admin/actions/unsuspend-user/execute").with(who)
                .contentType("application/json").content(json));
    }

    @Test
    void 미리보기_뒤_사유와_확인_글자로_실행하고_기록한다() throws Exception {
        int before = EXECUTED.get();
        String token = preview(admin());

        execute(admin(), token, "사용자 요청", " 확인 ").andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("풀었음"));

        assertThat(EXECUTED.get()).isEqualTo(before + 1);
        assertThat(logRepository.findTop50ByTargetUserIdOrderByIdDesc(77L))
                .anySatisfy(log -> {
                    assertThat(log.getAction()).isEqualTo("UNSUSPEND_USER");
                    assertThat(log.getReason()).isEqualTo("사용자 요청");
                    assertThat(log.getResult()).isEqualTo("풀었음");
                });
    }

    @Test
    void 같은_확인_토큰을_두_번_쓰면_409() throws Exception {
        String token = preview(admin());
        execute(admin(), token, "사유", "확인").andExpect(status().isOk());
        int after = EXECUTED.get();

        execute(admin(), token, "사유", "확인").andExpect(status().isConflict());

        assertThat(EXECUTED.get()).isEqualTo(after);
    }

    @Test
    void 다른_관리자의_확인_토큰은_409() throws Exception {
        String token = preview(admin());
        execute(AdminWebAuth.adminWebToken(jwtService, userRepository, "act-admin2"), token, "사유", "확인")
                .andExpect(status().isConflict());
    }

    @Test
    void 사유가_비거나_너무_길면_400() throws Exception {
        execute(admin(), preview(admin()), "   ", "확인").andExpect(status().isBadRequest());
        execute(admin(), preview(admin()), "가".repeat(301), "확인").andExpect(status().isBadRequest());
    }

    @Test
    void 확인_글자가_다르면_400이고_실행하지_않는다() throws Exception {
        int before = EXECUTED.get();
        execute(admin(), preview(admin()), "사유", "확 인").andExpect(status().isBadRequest());
        assertThat(EXECUTED.get()).isEqualTo(before);
    }

    @Test
    void 로그인_10분이_지난_관리자_토큰은_REAUTH_REQUIRED() throws Exception {
        String token = preview(admin());
        RequestPostProcessor old = AdminWebAuth.adminWebTokenIssuedAt(jwtService, userRepository, "act-admin",
                java.time.Instant.now().minusSeconds(700));
        execute(old, token, "사유", "확인").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("REAUTH_REQUIRED"));
    }

    @Test
    void 모르는_조치는_404() throws Exception {
        mockMvc.perform(post("/api/admin/actions/drop-table/preview").with(admin())
                .contentType("application/json").content("{\"targetId\":1}")).andExpect(status().isNotFound());
    }

    @Test
    void 감사_기록_목록을_최근순으로_준다() throws Exception {
        execute(admin(), preview(admin()), "목록 확인", "확인").andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/actions").param("targetUserId", "77").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs[0].action").value("UNSUSPEND_USER"));
    }
}
```
`AdminWebAuth`에 발급 시각을 정해 토큰을 만드는 `adminWebTokenIssuedAt(JwtService, UserRepository, String sub, Instant issuedAt)`를 더한다 — `JwtService`에 테스트에서만 쓰는 `issueAdminWebAt(User, Instant)`(패키지 공개 대신 public, 주석 "테스트·재인증 확인용")을 추가해 부른다.

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests AdminActionsControllerTest` → 컴파일 실패.

- [ ] **Step 3: 구현**

`AdminActionService`:
```java
package com.trova.backend.admin.action;

import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 미리보기 → 확인 토큰 → 사유·확인 글자 → 실행 → 감사 기록(설계 2단계 안전장치 1~5). */
@Service
public class AdminActionService {

    public static final int REASON_MAX = 300;

    public record Previewed(ActionPreview preview, String confirmToken, long expiresInSeconds) { }

    public record Executed(Long targetId, String result) { }

    private final Map<AdminAction, AdminActionHandler> handlers = new EnumMap<>(AdminAction.class);
    private final ConfirmTokenStore confirmTokens;
    private final AdminAuditLog auditLog;

    public AdminActionService(List<AdminActionHandler> handlers, ConfirmTokenStore confirmTokens, AdminAuditLog auditLog) {
        handlers.forEach(h -> this.handlers.put(h.action(), h));
        this.confirmTokens = confirmTokens;
        this.auditLog = auditLog;
    }

    public static Optional<AdminAction> parse(String path) {
        try {
            return Optional.of(AdminAction.valueOf(path.toUpperCase().replace('-', '_')));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public Previewed preview(AdminAction action, Long targetId, Long adminUserId) {
        ActionPreview preview = handler(action).preview(targetId, adminUserId);
        String token = confirmTokens.issue(new ConfirmTokenStore.Pending(
                adminUserId, action, targetId, preview.targetUserId(), preview.confirmText()));
        return new Previewed(preview, token, ConfirmTokenStore.TTL.toSeconds());
    }

    public Executed execute(AdminAction action, String confirmToken, String reason, String confirmText, Long adminUserId) {
        ConfirmTokenStore.Pending pending = confirmTokens.consume(confirmToken)
                .filter(p -> p.adminUserId().equals(adminUserId) && p.action() == action)
                .orElseThrow(() -> AdminActionException.conflict("확인이 만료됐어요. 미리보기부터 다시 해 주세요."));
        String trimmedReason = reason == null ? "" : reason.strip();
        if (trimmedReason.isEmpty() || trimmedReason.length() > REASON_MAX) {
            throw AdminActionException.badRequest("사유를 1~300자로 적어 주세요.");
        }
        if (pending.confirmText() != null
                && (confirmText == null || !pending.confirmText().strip().equals(confirmText.strip()))) {
            throw AdminActionException.badRequest("확인 글자가 달라요: " + pending.confirmText());
        }
        String result = handler(action).execute(pending.targetId(), adminUserId);
        auditLog.record(adminUserId, action.name(), pending.targetId(), pending.targetUserId(), trimmedReason, result);
        return new Executed(pending.targetId(), result);
    }

    private AdminActionHandler handler(AdminAction action) {
        AdminActionHandler handler = handlers.get(action);
        if (handler == null) {
            throw AdminActionException.notFound();
        }
        return handler;
    }
}
```
주의: 확인 토큰은 consume 순간 지워진다 — 사유·확인 글자가 틀려 400이 나면 미리보기를 다시 해야 한다. 화면도 그렇게 안내한다(실수 실행 방지 쪽이 안전).

`AdminActionsController`:
```java
package com.trova.backend.controller;

import com.trova.backend.admin.AdminGuard;
import com.trova.backend.admin.action.ActionPreview;
import com.trova.backend.admin.action.AdminAction;
import com.trova.backend.admin.action.AdminActionException;
import com.trova.backend.admin.action.AdminActionService;
import com.trova.backend.entity.AdminActionLog;
import com.trova.backend.entity.User;
import com.trova.backend.repository.AdminActionLogRepository;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/** 관리자 조치(#167, 설계 2단계). 미리보기는 읽기라 재인증 없이, 실행은 로그인 10분 안만. */
@RestController
public class AdminActionsController {

    private static final Duration REAUTH_WINDOW = Duration.ofMinutes(10);

    public record PreviewRequest(Long targetId) { }

    public record PreviewResponse(AdminAction action, Long targetId, Long targetUserId, String impact,
                                  String confirmText, String confirmToken, long expiresInSeconds) { }

    public record ExecuteRequest(String confirmToken, String reason, String confirmText) { }

    public record ExecuteResponse(AdminAction action, Long targetId, String result) { }

    public record LogItem(Long id, LocalDateTime createdAt, Long actorUserId, String action, Long targetId,
                          Long targetUserId, String reason, String result) { }

    public record LogsResponse(List<LogItem> logs) { }

    private final AdminGuard adminGuard;
    private final AdminActionService actionService;
    private final AdminActionLogRepository logRepository;

    public AdminActionsController(AdminGuard adminGuard, AdminActionService actionService,
                                  AdminActionLogRepository logRepository) {
        this.adminGuard = adminGuard;
        this.actionService = actionService;
        this.logRepository = logRepository;
    }

    @PostMapping("/api/admin/actions/{action}/preview")
    public PreviewResponse preview(Authentication authentication, @PathVariable String action,
                                   @RequestBody PreviewRequest request) {
        User admin = adminGuard.requireAdmin(authentication);
        AdminAction parsed = AdminActionService.parse(action).orElseThrow(AdminActionException::notFound);
        if (request.targetId() == null) {
            throw AdminActionException.badRequest("대상 번호가 없어요.");
        }
        AdminActionService.Previewed previewed = actionService.preview(parsed, request.targetId(), admin.getId());
        ActionPreview p = previewed.preview();
        return new PreviewResponse(p.action(), p.targetId(), p.targetUserId(), p.impact(), p.confirmText(),
                previewed.confirmToken(), previewed.expiresInSeconds());
    }

    @PostMapping("/api/admin/actions/{action}/execute")
    public ExecuteResponse execute(Authentication authentication, @PathVariable String action,
                                   @RequestBody ExecuteRequest request) {
        User admin = adminGuard.requireAdmin(authentication);
        adminGuard.requireRecentLogin(authentication, REAUTH_WINDOW);
        AdminAction parsed = AdminActionService.parse(action).orElseThrow(AdminActionException::notFound);
        AdminActionService.Executed executed = actionService.execute(parsed, request.confirmToken(), request.reason(),
                request.confirmText(), admin.getId());
        return new ExecuteResponse(parsed, executed.targetId(), executed.result());
    }

    @GetMapping("/api/admin/actions")
    public LogsResponse logs(Authentication authentication, @RequestParam(required = false) Long targetUserId) {
        adminGuard.requireAdmin(authentication);
        List<AdminActionLog> logs = targetUserId == null
                ? logRepository.findTop50ByOrderByIdDesc()
                : logRepository.findTop50ByTargetUserIdOrderByIdDesc(targetUserId);
        return new LogsResponse(logs.stream().map(l -> new LogItem(l.getId(), l.getCreatedAt(), l.getActorUserId(),
                l.getAction(), l.getTargetId(), l.getTargetUserId(), l.getReason(), l.getResult())).toList());
    }
}
```
`AdminEndpointsSecurityTest`가 새 POST 경로를 빈 본문으로 부르면 400이 나와야 한다(401·403 아님) — 본문 없는 요청은 Spring이 400을 낸다. 테스트를 돌려 확인.

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests AdminActionsControllerTest --tests AdminEndpointsSecurityTest --tests ConfirmTokenStoreTest` → PASS.
- [ ] **Step 5: 커밋** — `git commit -m "feat: 관리자 조치 미리보기·실행 API와 감사 기록 목록"`

---

### Task 6: 조치 — 여행 삭제·자동 초안 숨기기

**Files:**
- Create: `src/main/java/com/trova/backend/admin/action/DeleteTripAction.java`
- Create: `src/main/java/com/trova/backend/admin/action/HideDraftAction.java`
- Test: `src/test/java/com/trova/backend/admin/action/TripAndDraftActionsTest.java`

**Interfaces:** `AdminActionHandler` 구현 2개. 여행 삭제 `confirmText` = 여행 이름(`trip.getTitle()`), impact `여행 1·일차 N·장소 M`; 초안 숨기기 `confirmText` = null, impact `자동 초안 #id(상태)`, 이미 숨김/자동 아님이면 409.

- [ ] **Step 1: 실패 테스트**

```java
package com.trova.backend.admin.action;

import com.trova.backend.entity.Trip;
import com.trova.backend.entity.User;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.service.TripService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class TripAndDraftActionsTest {

    @Autowired
    private DeleteTripAction deleteTrip;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TripService tripService;

    @Autowired
    private TripRepository tripRepository;

    @Test
    void 여행_삭제_미리보기는_이름을_확인_글자로_영향_범위를_센다() {
        User owner = userRepository.save(new User("google", "trip-owner", "주인", null));
        Trip trip = tripService.createTrip(owner, "제주 2박 3일", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3));

        ActionPreview preview = deleteTrip.preview(trip.getId(), 999L);

        assertThat(preview.confirmText()).isEqualTo("제주 2박 3일");
        assertThat(preview.targetUserId()).isEqualTo(owner.getId());
        assertThat(preview.impact()).startsWith("여행 1·일차 3");
    }

    @Test
    void 여행_삭제_실행은_여행과_딸린_데이터를_지운다() {
        User owner = userRepository.save(new User("google", "trip-owner2", "주인", null));
        Trip trip = tripService.createTrip(owner, "부산", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1));

        String result = deleteTrip.execute(trip.getId(), 999L);

        assertThat(tripRepository.findById(trip.getId())).isEmpty();
        assertThat(result).contains("여행 1");
    }

    @Test
    void 미리보기_뒤_사용자가_먼저_지운_여행은_404() {
        User owner = userRepository.save(new User("google", "trip-owner3", "주인", null));
        Trip trip = tripService.createTrip(owner, "강릉", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1));
        deleteTrip.preview(trip.getId(), 999L);
        tripService.deleteTrip(owner, trip.getId());

        assertThatThrownBy(() -> deleteTrip.execute(trip.getId(), 999L))
                .isInstanceOf(AdminActionException.class)
                .satisfies(e -> assertThat(((AdminActionException) e).status().value()).isEqualTo(404));
    }
}
```
초안 숨기기 테스트 2개를 같은 파일에 더한다: 자동 초안을 저장(기존 `TripDraft` 테스트의 생성 방식을 그대로 — `AutoDraftServiceTest` 등에서 `new TripDraft(...)` 생성자·`autoCreated` 설정법을 찾아 쓴다) → 실행 뒤 `dismissedAt`이 채워짐, 자동이 아닌 초안 미리보기는 409.

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests TripAndDraftActionsTest` → 컴파일 실패.

- [ ] **Step 3: 구현**

```java
package com.trova.backend.admin.action;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Trip;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.service.TripService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 사용자 요청 시 그 사용자의 여행 하나를 연결 데이터까지 삭제 — 앱의 여행 삭제와 같은 경로(TripService.deleteTrip). */
@Component
public class DeleteTripAction implements AdminActionHandler {

    private final TripRepository tripRepository;
    private final ItineraryRepository itineraryRepository;
    private final TripPlaceRepository tripPlaceRepository;
    private final TripService tripService;

    public DeleteTripAction(TripRepository tripRepository, ItineraryRepository itineraryRepository,
                            TripPlaceRepository tripPlaceRepository, TripService tripService) {
        this.tripRepository = tripRepository;
        this.itineraryRepository = itineraryRepository;
        this.tripPlaceRepository = tripPlaceRepository;
        this.tripService = tripService;
    }

    @Override
    public AdminAction action() {
        return AdminAction.DELETE_TRIP;
    }

    @Override
    @Transactional(readOnly = true)
    public ActionPreview preview(Long targetId, Long adminUserId) {
        Trip trip = tripRepository.findById(targetId).orElseThrow(AdminActionException::notFound);
        return new ActionPreview(action(), trip.getId(), trip.getUser().getId(), impact(trip), trip.getTitle());
    }

    @Override
    @Transactional
    public String execute(Long targetId, Long adminUserId) {
        Trip trip = tripRepository.findById(targetId).orElseThrow(AdminActionException::notFound);
        String impact = impact(trip);
        tripService.deleteTrip(trip.getUser(), trip.getId());
        return impact;
    }

    private String impact(Trip trip) {
        List<Itinerary> days = itineraryRepository.findByTripOrderByDay(trip);
        int places = days.stream().mapToInt(d -> tripPlaceRepository.findByItineraryOrderByVisitOrder(d).size()).sum();
        return "여행 1·일차 " + days.size() + "·장소 " + places;
    }
}
```
`HideDraftAction`: `TripDraftRepository.findById` → 없으면 notFound, `!isAutoCreated()`면 conflict("자동으로 만든 초안만 숨길 수 있어요."), 이미 `getDismissedAt() != null`이면 conflict("이미 숨긴 초안이에요."). 실행은 같은 검사 뒤 `draft.dismiss()`, 결과 `"초안 #" + id + " 숨김"`. `TripDraft`에 `isAutoCreated()`·`getDismissedAt()` 게터가 없으면 더한다.

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests TripAndDraftActionsTest` → PASS.
- [ ] **Step 5: 커밋** — `git commit -m "feat: 관리자 조치 여행 삭제·자동 초안 숨기기"`

---

### Task 7: 조치 — 계정 정지·풀기·계정 삭제

**Files:**
- Create: `src/main/java/com/trova/backend/admin/action/SuspendUserAction.java`
- Create: `src/main/java/com/trova/backend/admin/action/UnsuspendUserAction.java`
- Create: `src/main/java/com/trova/backend/admin/action/DeleteAccountAction.java`
- Create: `src/main/java/com/trova/backend/admin/action/UserImpact.java`(영향 범위 세기 공통)
- Test: `src/test/java/com/trova/backend/admin/action/AccountActionsTest.java`

**Interfaces:**
- 정지: confirmText null, impact `계정 1·단축어 키 n`, 이미 정지면 409, 관리자면 409("관리자 계정은 정지할 수 없어요."). 실행: `user.suspend()` + `shareKeyService.revoke(userId)` + `suspendedUsers.add(userId)`, 결과 `정지·단축어 키 n개 삭제`.
- 풀기: 정지 아니면 409. 실행: `user.unsuspend()` + `suspendedUsers.remove`, 결과 `정지 풀기`.
- 계정 삭제: confirmText `계정 삭제`, impact `계정 1·영상 a·장소 b·여행 c·초안 d`, 관리자면 409("관리자 계정은 삭제할 수 없어요."). 실행: `userAccountService.withdraw(user)` + `suspendedUsers.remove`, 결과 = 실행 전 impact.
- 관리자 판정은 `AdminAccessService.isAdmin(User)`(자기 자신 포함).
- `UserImpact.count(User)` → `record Counts(long jobs, long savedPlaces, long trips, long drafts)`; `EntityManager` JPQL count 4개(`select count(j) from ProcessingJob j where j.user = :u` 등).

- [ ] **Step 1: 실패 테스트**

```java
package com.trova.backend.admin.action;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.SuspendedUsers;
import com.trova.backend.service.ShareKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = "app.admin.users=google:acct-admin")
class AccountActionsTest {

    @Autowired
    private SuspendUserAction suspend;

    @Autowired
    private UnsuspendUserAction unsuspend;

    @Autowired
    private DeleteAccountAction deleteAccount;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SuspendedUsers suspendedUsers;

    @Autowired
    private ShareKeyService shareKeyService;

    @Test
    void 정지하면_단축어_키를_지우고_정지_목록에_넣는다() {
        User user = userRepository.save(new User("google", "acct-s1", "정지대상", null));
        shareKeyService.issue(user.getId());

        assertThat(suspend.preview(user.getId(), 1L).impact()).isEqualTo("계정 1·단축어 키 1");
        String result = suspend.execute(user.getId(), 1L);

        assertThat(result).isEqualTo("정지·단축어 키 1개 삭제");
        assertThat(userRepository.findById(user.getId()).orElseThrow().isSuspended()).isTrue();
        assertThat(shareKeyService.status(user.getId()).exists()).isFalse();
        assertThat(suspendedUsers.isSuspended(user.getId())).isTrue();

        unsuspend.execute(user.getId(), 1L);
        assertThat(suspendedUsers.isSuspended(user.getId())).isFalse();
        assertThat(userRepository.findById(user.getId()).orElseThrow().isSuspended()).isFalse();
    }

    @Test
    void 관리자_계정은_정지_삭제_미리보기에서_409() {
        User admin = userRepository.findByProviderAndProviderUserId("google", "acct-admin")
                .orElseGet(() -> userRepository.save(new User("google", "acct-admin", "관리자", null)));

        assertThatThrownBy(() -> suspend.preview(admin.getId(), admin.getId()))
                .satisfies(e -> assertThat(((AdminActionException) e).status().value()).isEqualTo(409));
        assertThatThrownBy(() -> deleteAccount.preview(admin.getId(), admin.getId()))
                .satisfies(e -> assertThat(((AdminActionException) e).status().value()).isEqualTo(409));
    }

    @Test
    void 계정_삭제는_확인_글자를_요구하고_회원을_지운다() {
        User user = userRepository.save(new User("google", "acct-d1", "삭제대상", null));

        ActionPreview preview = deleteAccount.preview(user.getId(), 1L);
        assertThat(preview.confirmText()).isEqualTo("계정 삭제");
        assertThat(preview.impact()).isEqualTo("계정 1·영상 0·장소 0·여행 0·초안 0");

        deleteAccount.execute(user.getId(), 1L);
        assertThat(userRepository.findById(user.getId())).isEmpty();
    }

    @Test
    void 이미_정지된_계정을_또_정지하면_409() {
        User user = userRepository.save(new User("google", "acct-s2", "정지대상", null));
        suspend.execute(user.getId(), 1L);

        assertThatThrownBy(() -> suspend.preview(user.getId(), 1L))
                .satisfies(e -> assertThat(((AdminActionException) e).status().value()).isEqualTo(409));
        unsuspend.execute(user.getId(), 1L);
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests AccountActionsTest` → 컴파일 실패.

- [ ] **Step 3: 구현** — `SuspendUserAction`:
```java
@Component
public class SuspendUserAction implements AdminActionHandler {

    private final UserRepository userRepository;
    private final AdminAccessService adminAccessService;
    private final ShareKeyService shareKeyService;
    private final SuspendedUsers suspendedUsers;

    // 생성자 주입(4개)

    @Override
    public AdminAction action() {
        return AdminAction.SUSPEND_USER;
    }

    @Override
    @Transactional(readOnly = true)
    public ActionPreview preview(Long targetId, Long adminUserId) {
        User user = check(targetId);
        return new ActionPreview(action(), user.getId(), user.getId(), "계정 1·단축어 키 " + keyCount(user), null);
    }

    @Override
    @Transactional
    public String execute(Long targetId, Long adminUserId) {
        User user = check(targetId);
        int keys = keyCount(user);
        user.suspend();
        shareKeyService.revoke(user.getId());
        suspendedUsers.add(user.getId());
        return "정지·단축어 키 " + keys + "개 삭제";
    }

    private User check(Long targetId) {
        User user = userRepository.findById(targetId).orElseThrow(AdminActionException::notFound);
        if (adminAccessService.isAdmin(user)) {
            throw AdminActionException.conflict("관리자 계정은 정지할 수 없어요.");
        }
        if (user.isSuspended()) {
            throw AdminActionException.conflict("이미 정지된 계정이에요.");
        }
        return user;
    }

    private int keyCount(User user) {
        return shareKeyService.status(user.getId()).exists() ? 1 : 0;
    }
}
```
`suspendedUsers.add`는 트랜잭션이 끝나기 전에 불리지만, 실패해 되돌려져도 메모리에만 남는 것은 "다음 요청 401"로 안전한 쪽이다 — 주석으로 남긴다. `UnsuspendUserAction`·`DeleteAccountAction`은 위 Interfaces대로 같은 모양. `DeleteAccountAction.execute`는 `UserImpact`로 센 문자열을 먼저 만들고 `userAccountService.withdraw(user)`.

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests AccountActionsTest --tests SuspendedUserAccessTest` → PASS.
- [ ] **Step 5: 커밋** — `git commit -m "feat: 관리자 조치 계정 정지·풀기·계정 삭제"`

---

### Task 8: 조치 — 실패한 분석 다시 실행

**Files:**
- Create: `src/main/java/com/trova/backend/admin/action/RetryJobAction.java`
- Test: `src/test/java/com/trova/backend/admin/action/RetryJobActionTest.java`

**Interfaces:**
- Consumes: `FailureReason.classify`, `AiDashboardService.geminiCallsSince(LocalDateTime)`, 오늘 시작 계산은 `DashboardSummaryService`와 같은 태평양 자정 기준(공통 메서드로 뺀다: `AiDashboardService.geminiDayStart()` public static), `PlaceExtractionService.process(Long jobId)`(비동기).
- 미리보기 조건: 작업 FAILED이고 실패 이유가 `AI_QUOTA`·`SOURCE_RATE_LIMITED`·`STALE` 중 하나(아니면 409 "일시 오류로 실패한 작업만 다시 실행해요."), 같은 사용자·같은 링크가 처리 중이면 409, 오늘 Gemini ≥ `app.admin.dashboard.gemini-alert`면 409("오늘 Gemini 사용량이 n회라 다시 실행하지 않아요."). impact `영상 1·오늘 Gemini n회`. 실행: 같은 검사 → `new ProcessingJob(user, sourceUrl, sourcePlatform)` 저장 → `placeExtractionService.process(newId)`(거절되면 새 작업 지우고 409 "서버가 바빠요"), 결과 `새 작업 #id`.

- [ ] **Step 1: 실패 테스트**

```java
package com.trova.backend.admin.action;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.service.PlaceExtractionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
class RetryJobActionTest {

    @MockitoBean
    private PlaceExtractionService placeExtractionService;

    @Autowired
    private RetryJobAction retry;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProcessingJobRepository jobRepository;

    private ProcessingJob failedJob(String sub, String message) {
        User user = userRepository.save(new User("google", sub, sub, null));
        ProcessingJob job = new ProcessingJob(user, "https://www.youtube.com/shorts/" + sub, SourcePlatform.YOUTUBE);
        job.markFailed(message);
        return jobRepository.save(job);
    }

    @Test
    void 일시_오류로_실패한_작업은_새_작업을_만들어_다시_돌린다() {
        ProcessingJob job = failedJob("retry-1", ProcessingJob.AI_QUOTA_MESSAGE);

        String result = retry.execute(job.getId(), 1L);

        assertThat(result).startsWith("새 작업 #");
        Long newId = Long.valueOf(result.substring("새 작업 #".length()));
        assertThat(jobRepository.findById(newId).orElseThrow().getSourceUrl()).isEqualTo(job.getSourceUrl());
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getErrorMessage()).isEqualTo(ProcessingJob.AI_QUOTA_MESSAGE);
        verify(placeExtractionService).process(newId);
    }

    @Test
    void 장소가_없어_실패한_작업은_다시_돌리지_않는다() {
        ProcessingJob job = failedJob("retry-2", ProcessingJob.NO_PLACES_MESSAGE);

        assertThatThrownBy(() -> retry.preview(job.getId(), 1L))
                .satisfies(e -> assertThat(((AdminActionException) e).status().value()).isEqualTo(409));
        verify(placeExtractionService, never()).process(anyLong());
    }
}
```
(`SourcePlatform` 값 이름·`ProcessingJob` 생성자 인자 순서는 엔티티에서 확인해 맞춘다.) Gemini 한도 테스트: `@TestPropertySource(properties = "app.admin.dashboard.gemini-alert=0")`를 둔 별도 클래스 `RetryJobQuotaTest`에서 미리보기 409.

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests 'RetryJob*'` → 컴파일 실패.
- [ ] **Step 3: 구현** — 위 Interfaces대로. 실행은 `@Transactional`이 아닌 메서드에서 새 작업 저장(`jobRepository.save`) 뒤 `process` 호출 — `SharesController`처럼 저장이 커밋된 다음 비동기 처리가 작업을 읽게 한다. `TaskRejectedException`이면 새 작업을 지우고 `AdminActionException.conflict("서버가 바빠요. 잠시 뒤 다시 해 주세요.")`.
- [ ] **Step 4: 통과 확인** — `./gradlew test --tests 'RetryJob*'` → PASS.
- [ ] **Step 5: 커밋** — `git commit -m "feat: 관리자 조치 실패한 분석 다시 실행"`

---

### Task 9: 사용자 찾기·상세(가린 값) + 조회 기록

**Files:**
- Create: `src/main/java/com/trova/backend/admin/user/NicknameMask.java`
- Create: `src/main/java/com/trova/backend/admin/user/AdminUserService.java`
- Create: `src/main/java/com/trova/backend/controller/AdminUsersController.java`
- Test: `src/test/java/com/trova/backend/admin/user/NicknameMaskTest.java`, `src/test/java/com/trova/backend/controller/AdminUsersControllerTest.java`

**Interfaces:**
- `NicknameMask.mask(String)` — null/빈 값 → `"(없음)"`, 그 외 첫 글자(코드 포인트) + `*` × min(나머지 길이, 4).
- `GET /api/admin/users?q=` → `{"users":[{"id","provider","nickname"(가림),"createdAt","suspended","jobs","trips"}]}` 최대 20개. q 규칙: 숫자만 → 내부 번호, `http`로 시작 → 그 링크를 제출한 사용자(`processing_jobs.source_url = q`), 그 밖 → 닉네임 부분 일치 또는 여행 이름 부분 일치(대소문자 무시). q가 공백이면 최근 가입 20명. 기록 `ADMIN_USER_SEARCH`, result `결과 n명`(검색어 저장 안 함).
- `GET /api/admin/users/{id}` → `{"id","provider","nickname"(가림),"createdAt","lastActiveAt","suspendedAt","admin","counts":{"jobs","savedPlaces","trips","drafts"},"recentJobs":[{"id","status","failureReason","createdAt","retryable"}](10개),"trips":[{"id","title","startDate","endDate"}](20개),"autoDrafts":[{"id","status","dismissed","createdAt"}](10개)}`. 없으면 404. 기록 `ADMIN_USER_VIEW`(targetId=targetUserId=id). `lastActiveAt` = 그 사용자 작업·여행 중 가장 최근 `updatedAt`(없으면 가입일). `retryable`은 Task 8 조건 중 상태·실패 이유만(Gemini 확인은 미리보기에서).
- 응답에 `providerUserId`·`profileImageUrl` 없음(테스트로 확인).

- [ ] **Step 1: 실패 테스트**

```java
package com.trova.backend.admin.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NicknameMaskTest {

    @Test
    void 첫_글자만_남기고_최대_4개까지_가린다() {
        assertThat(NicknameMask.mask("김태형")).isEqualTo("김**");
        assertThat(NicknameMask.mask("가나다라마바사")).isEqualTo("가****");
        assertThat(NicknameMask.mask("A")).isEqualTo("A");
    }

    @Test
    void 비었거나_없으면_없음() {
        assertThat(NicknameMask.mask(null)).isEqualTo("(없음)");
        assertThat(NicknameMask.mask(" ")).isEqualTo("(없음)");
    }

    @Test
    void 이모지도_한_글자로_센다() {
        assertThat(NicknameMask.mask("😀하하")).isEqualTo("😀**");
    }
}
```
`AdminUsersControllerTest`(MockMvc, `app.admin.users=google:users-admin`): ① 숫자 검색으로 한 명, 응답 문자열에 `providerUserId`·실제 닉네임이 없음 ② 링크 검색이 그 링크를 넣은 사용자를 찾음 ③ 상세가 counts·recentJobs를 주고 `ADMIN_USER_VIEW` 기록이 1건 생김(`findTop50ByTargetUserIdOrderByIdDesc`) ④ 검색 기록 result가 `결과 1명`이고 reason이 null(검색어 없음) ⑤ 없는 번호 404.

```java
    @Test
    void 상세는_가린_닉네임과_건수를_주고_열람을_기록한다() throws Exception {
        User user = userRepository.save(new User("kakao", "9876543210", "김태형", "https://img/p.png"));
        String body = mockMvc.perform(get("/api/admin/users/" + user.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("김**"))
                .andExpect(jsonPath("$.provider").value("kakao"))
                .andExpect(jsonPath("$.counts.trips").value(0))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("9876543210").doesNotContain("김태형").doesNotContain("img/p.png");
        assertThat(logRepository.findTop50ByTargetUserIdOrderByIdDesc(user.getId()))
                .extracting(AdminActionLog::getAction).containsExactly(AdminActionLog.ADMIN_USER_VIEW);
    }
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests NicknameMaskTest --tests AdminUsersControllerTest` → 컴파일 실패.
- [ ] **Step 3: 구현** — `NicknameMask`:
```java
package com.trova.backend.admin.user;

/** 관리자 화면에 닉네임을 그대로 보이지 않는다 — 첫 글자만(ADR 0014). */
public final class NicknameMask {

    private NicknameMask() {
    }

    public static String mask(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return "(없음)";
        }
        String trimmed = nickname.strip();
        int first = trimmed.codePointAt(0);
        int rest = trimmed.codePointCount(0, trimmed.length()) - 1;
        return new String(Character.toChars(first)) + "*".repeat(Math.min(rest, 4));
    }
}
```
`AdminUserService`는 `EntityManager` JPQL로(대시보드 서비스와 같은 방식) 검색·건수·최근 목록을 읽고, 컨트롤러가 `AdminAuditLog.record`를 부른다(조회 기록은 읽기 트랜잭션 밖에서 남도록 `REQUIRES_NEW`가 이미 걸려 있음). 닉네임 검색 JPQL: `select u from User u where lower(u.nickname) like :q` + 여행 이름: `select distinct t.user from Trip t where lower(t.title) like :q`, 합쳐 id 순 20개. `like` 인자에서 `%`·`_`는 이스케이프한다.
- [ ] **Step 4: 통과 확인** — `./gradlew test --tests NicknameMaskTest --tests AdminUsersControllerTest --tests AdminEndpointsSecurityTest` → PASS.
- [ ] **Step 5: 커밋** — `git commit -m "feat: 관리자 사용자 찾기·상세(닉네임 가림)와 열람 기록"`

---

### Task 10: 전체 확인·문서·로컬 실서버 확인

**Files:**
- Modify: `docs/experience-notes.md`(작업 기록 — 문제와 조건 / 선택한 방법과 이유 / 확인한 것(실행 환경 표기) / 확인하지 않은 것)
- Modify: `.env.example`(새 설정 없음 확인 — 있으면 추가)

- [ ] **Step 1:** `./gradlew build` → 전체 통과(테스트 수 기록).
- [ ] **Step 2: 로컬 실서버(Mac, 8082) + 개발 DB** — `SERVER_PORT=8082 ADMIN_USERS=google:dev-qa-1 java -jar build/libs/…jar`, 로컬 `.env`의 서명 키로 관리자 웹 토큰을 만들어(값은 남기지 않음) curl로: 사용자 검색 200·응답에 고유번호 없음, 상세 200, 자동 초안 숨기기 미리보기→실행 200·감사 기록 1줄, 같은 확인 토큰 재사용 409, 11분 전 발급 토큰 실행 403 REAUTH_REQUIRED, 테스트 계정 정지 → 그 계정 앱 토큰 401 → 풀기 → 200. 새 칸(`users.suspended_at`, `admin_action_logs.target_user_id`)이 개발 DB에 자동 추가됐는지. Gemini를 쓰는 재실행은 실행하지 않고 미리보기만.
- [ ] **Step 3:** 작업 기록 작성 후 커밋 `git commit -m "docs: 관리자 조치 작업 기록"`.
- [ ] **Step 4:** 푸시·PR(`Closes #167`), reviewflow 리뷰.
