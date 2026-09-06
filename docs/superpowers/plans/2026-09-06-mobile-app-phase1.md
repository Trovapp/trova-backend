# React Native 모바일 앱 1단계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 백엔드(trova-backend)에 모바일 전용 JWT 인증을 추가하고,
새 Expo(React Native) 앱(`trova-app`)에서 로그인 → 링크 공유 처리 →
저장한 장소 목록/상세/지도까지의 핵심 루프를 동작시킨다.

**Architecture:** 백엔드는 기존 세션 쿠키 로그인(웹)을 그대로 두고
JWT 발급/검증 경로를 병행 추가한다(OAuth 콜백에서 모바일 요청만
구분해 딥링크로 토큰을 돌려줌). 앱은 Expo managed workflow +
TypeScript로, React Navigation(네이티브 스택) + TanStack Query +
SecureStore 토큰 저장 + Kakao맵을 WebView로 감싼 지도 화면으로
구성한다.

**Tech Stack:** Spring Boot(기존) + `io.jsonwebtoken:jjwt` 0.12.6 /
Expo(TypeScript) + React Navigation + TanStack Query +
`expo-secure-store` + `expo-web-browser` + `expo-linking` +
`react-native-webview` + `@expo-google-fonts/ibm-plex-mono`

**Spec:** `docs/superpowers/specs/2026-09-06-mobile-app-phase1-design.md`

## Global Constraints

- 커밋 메시지는 두 레포 모두 `타입: 내용` 형식만 사용, AI 서명/트레일러
  절대 금지 (trova-backend CLAUDE.md, trova-frontend에도 동일하게
  적용해온 관례)
- 백엔드 커밋 전 반드시 `./gradlew build` 실행 (CLAUDE.md)
- **기존 웹 로그인(세션 쿠키)은 절대 회귀시키지 않는다** — 이 계획의
  모든 백엔드 태스크는 새 JWT 경로 테스트뿐 아니라 기존
  `oauth2Login()` 세션 흐름이 여전히 동작하는지도 함께 검증해야 한다
- 새 의존성 `io.jsonwebtoken:jjwt-*`는 MIT 라이선스, 무료 — 비용 원칙
  위반 아님 (유료 API 아님, 라이브러리임)
- 카카오맵 JS SDK는 기존 웹과 동일한 무료 키를 그대로 재사용한다.
  새 지도 API(Google Maps 등)는 도입하지 않는다
- 2/3단계 범위(내 여행/일정, 추천/찜/마이페이지)는 이 계획에
  포함하지 않는다
- 앱의 텍스트는 웹과 동일하게 IBM Plex Mono(400/500)를 사용해
  플랫폼 기본 폰트(San Francisco/Roboto)에 의존하지 않는다
- iOS/Android 기본 동작이 다른 지점(헤더 타이틀 정렬, 키보드 회피,
  카드 그림자)은 각 화면 태스크에서 명시적으로 통일한다

---

## 백엔드 태스크 (trova-backend, 이 워크트리에서 작업)

### Task 1: JWT 의존성 추가 + JwtService

**Files:**
- Modify: `build.gradle`
- Create: `src/main/java/com/trova/backend/security/JwtService.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application.yml`
- Test: `src/test/java/com/trova/backend/security/JwtServiceTest.java`

**Interfaces:**
- Produces: `JwtService.issue(User user) -> String`,
  `JwtService.verify(String token) -> Long`(userId, 검증 실패 시
  `io.jsonwebtoken.JwtException` 또는 `IllegalArgumentException`을
  그대로 던짐 — 별도 커스텀 예외 안 만듦)

- [ ] **Step 1: build.gradle에 JWT 의존성 추가**

`dependencies { ... }` 블록의 `runtimeOnly 'org.postgresql:postgresql'`
줄 바로 아래에 추가:

```groovy
	implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
	runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.6'
	runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.6'
```

- [ ] **Step 2: application.yml / 테스트 application.yml에 jwt-secret 추가**

`src/main/resources/application.yml`의 `app:` 블록 맨 아래에 추가
(다른 키와 같은 들여쓰기):

```yaml
  jwt-secret: ${JWT_SECRET}
```

기본값을 주지 않는다 — 운영에서 `JWT_SECRET`이 없으면 앱 컨텍스트
시작 자체가 실패해야 한다(스펙의 "fail fast" 결정).

`src/test/resources/application.yml`의 `app:` 블록 맨 아래에 추가:

```yaml
  jwt-secret: test-jwt-secret-please-do-not-use-in-production-environment
```

(HS256 서명에 필요한 최소 256비트=32바이트를 넘는 길이여야 함 — 위
문자열은 60자 이상이라 충분함)

- [ ] **Step 3: 로컬 .env에 JWT_SECRET 추가**

`.env` 파일에 아래 줄 추가 (사용자에게 실제로 이 파일에 값이 있는지
확인 요청 — 없으면 아래처럼 무작위 값을 생성해서 채워도 됨):

```bash
JWT_SECRET=$(openssl rand -base64 48)
```

- [ ] **Step 4: 실패하는 테스트 작성**

```java
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
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.security.JwtServiceTest"`
Expected: FAIL (컴파일 에러 — `JwtService` 클래스가 아직 없음)

- [ ] **Step 3: JwtService 구현**

```java
package com.trova.backend.security;

import com.trova.backend.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

@Component
public class JwtService {

    private static final Duration DEFAULT_EXPIRY = Duration.ofDays(90);

    private final SecretKey key;
    private final Duration expiry;

    public JwtService(@Value("${app.jwt-secret}") String secret) {
        this(secret, DEFAULT_EXPIRY);
    }

    JwtService(String secret, Duration expiry) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiry = expiry;
    }

    public String issue(User user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiry.toMillis()))
                .signWith(key)
                .compact();
    }

    public Long verify(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return Long.valueOf(claims.getSubject());
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.security.JwtServiceTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: 전체 빌드 확인 (기존 테스트 회귀 없는지)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — 기존 OAuth2 세션 로그인 테스트
(`AuthControllerTest`, `UsersControllerTest` 등)도 그대로 통과해야 함

- [ ] **Step 6: 커밋**

```bash
git add build.gradle src/main/java/com/trova/backend/security/JwtService.java \
  src/main/resources/application.yml src/test/resources/application.yml \
  src/test/java/com/trova/backend/security/JwtServiceTest.java
git commit -m "feat: 모바일 앱용 JWT 발급/검증 서비스 추가"
```

---

### Task 2: JwtAuthenticationToken + JwtAuthenticationFilter

**Files:**
- Create: `src/main/java/com/trova/backend/security/JwtAuthenticationToken.java`
- Create: `src/main/java/com/trova/backend/security/JwtAuthenticationFilter.java`
- Modify: `src/main/java/com/trova/backend/config/SecurityConfig.java`
- Test: `src/test/java/com/trova/backend/security/JwtAuthenticationFilterTest.java`

**Interfaces:**
- Consumes: `JwtService.verify(String) -> Long` (Task 1)
- Produces: `JwtAuthenticationToken(Long userId)` — `getPrincipal()`이
  `Long userId`를 반환. `JwtAuthenticationFilter`는 헤더가 없거나
  검증 실패 시 예외 없이 다음 필터로 그냥 넘어감(기존 세션 인증과
  공존)

- [ ] **Step 1: JwtAuthenticationToken 구현 (테스트 없이 바로 — 순수
  데이터 홀더라 필터 테스트로 간접 검증)**

```java
package com.trova.backend.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

public class JwtAuthenticationToken extends AbstractAuthenticationToken {

    private final Long userId;

    public JwtAuthenticationToken(Long userId) {
        super(List.of(new SimpleGrantedAuthority("ROLE_USER")));
        this.userId = userId;
        setAuthenticated(true);
    }

    public Long getUserId() {
        return userId;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return userId;
    }
}
```

- [ ] **Step 2: 실패하는 필터 테스트 작성**

```java
package com.trova.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-jwt-secret-please-do-not-use-in-production-environment";

    private final JwtService jwtService = new JwtService(SECRET);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService);

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain chain;

    @Test
    void 유효한_Bearer_토큰이면_SecurityContext에_인증정보를_세팅한다() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer " + tokenFor(7L));

        filter.doFilterInternal(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isInstanceOf(JwtAuthenticationToken.class);
        assertThat(((JwtAuthenticationToken) auth).getUserId()).isEqualTo(7L);
        verify(chain).doFilter(request, response);
        SecurityContextHolder.clearContext();
    }

    @Test
    void Authorization_헤더가_없으면_그냥_다음_필터로_넘어간다() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void 위조된_토큰이면_인증정보를_세팅하지_않고_다음_필터로_넘어간다() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer garbage-token");

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    private String tokenFor(Long userId) {
        com.trova.backend.entity.User user = new com.trova.backend.entity.User("google", "42", "테스트", null);
        try {
            java.lang.reflect.Field field = user.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, userId);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return jwtService.issue(user);
    }
}
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.security.JwtAuthenticationFilterTest"`
Expected: FAIL (컴파일 에러 — `JwtAuthenticationFilter`가 아직 없음)

- [ ] **Step 4: JwtAuthenticationFilter 구현**

```java
package com.trova.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                Long userId = jwtService.verify(token);
                SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(userId));
            } catch (RuntimeException ignored) {
                // 검증 실패 시 그냥 미인증 상태로 다음 필터로 넘긴다 —
                // 기존 세션 인증 경로가 있으면 그쪽에서 처리되고,
                // 둘 다 없으면 결국 anyRequest().authenticated()에서 401.
            }
        }
        filterChain.doFilter(request, response);
    }
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.security.JwtAuthenticationFilterTest"`
Expected: PASS (3 tests)

- [ ] **Step 6: SecurityConfig에 필터 등록**

`src/main/java/com/trova/backend/config/SecurityConfig.java`에서
import 추가:

```java
import com.trova.backend.security.JwtAuthenticationFilter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationFilter;
```

생성자에 `JwtAuthenticationFilter` 주입 추가:

```java
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(CustomOAuth2UserService customOAuth2UserService,
                           OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler,
                           OAuth2LoginFailureHandler oAuth2LoginFailureHandler,
                           JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.customOAuth2UserService = customOAuth2UserService;
        this.oAuth2LoginSuccessHandler = oAuth2LoginSuccessHandler;
        this.oAuth2LoginFailureHandler = oAuth2LoginFailureHandler;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }
```

`securityFilterChain` 메서드의 `http` 체인 마지막(`.exceptionHandling(...)`
다음)에 필터 등록 추가:

```java
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
```

(기존에 `return http.build();`만 있던 부분을 위처럼 필터 등록 후
build하도록 수정 — `.exceptionHandling(...)` 블록의 닫는 괄호 뒤에
세미콜론 대신 `.addFilterBefore(...)`를 체이닝하고 그 다음에
세미콜론)

- [ ] **Step 7: 전체 빌드 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — 기존 세션 기반 인증 테스트들도 그대로
통과 (JwtAuthenticationFilter는 헤더 없으면 아무것도 안 하므로 웹
요청 흐름에 영향 없음)

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/trova/backend/security/JwtAuthenticationToken.java \
  src/main/java/com/trova/backend/security/JwtAuthenticationFilter.java \
  src/main/java/com/trova/backend/config/SecurityConfig.java \
  src/test/java/com/trova/backend/security/JwtAuthenticationFilterTest.java
git commit -m "feat: Authorization 헤더의 JWT를 검증하는 인증 필터 추가"
```

---

### Task 3: MobileLoginFlagFilter

**Files:**
- Create: `src/main/java/com/trova/backend/security/MobileLoginFlagFilter.java`
- Modify: `src/main/java/com/trova/backend/config/SecurityConfig.java`
- Test: `src/test/java/com/trova/backend/security/MobileLoginFlagFilterTest.java`

**Interfaces:**
- Produces: `HttpSession` 속성 `"MOBILE_LOGIN" -> Boolean.TRUE`,
  `/oauth2/authorization/**` 요청에 `mobile=true` 쿼리 파라미터가
  있을 때만 세팅. Task 4의 `OAuth2LoginSuccessHandler`/
  `OAuth2LoginFailureHandler`가 이 세션 속성을 읽어서 분기함

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.trova.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MobileLoginFlagFilterTest {

    private final MobileLoginFlagFilter filter = new MobileLoginFlagFilter();

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain chain;
    @Mock
    private HttpSession session;

    @Test
    void mobile_파라미터가_true면_세션에_플래그를_저장한다() throws Exception {
        when(request.getParameter("mobile")).thenReturn("true");
        when(request.getSession(true)).thenReturn(session);

        filter.doFilterInternal(request, response, chain);

        verify(session).setAttribute("MOBILE_LOGIN", Boolean.TRUE);
        verify(chain).doFilter(request, response);
    }

    @Test
    void mobile_파라미터가_없으면_세션을_건드리지_않는다() throws Exception {
        when(request.getParameter("mobile")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(session);
        verify(chain).doFilter(request, response);
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.security.MobileLoginFlagFilterTest"`
Expected: FAIL (컴파일 에러 — `MobileLoginFlagFilter`가 아직 없음)

- [ ] **Step 3: MobileLoginFlagFilter 구현**

```java
package com.trova.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class MobileLoginFlagFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain
    ) throws ServletException, IOException {
        if ("true".equals(request.getParameter("mobile"))) {
            HttpSession session = request.getSession(true);
            session.setAttribute("MOBILE_LOGIN", Boolean.TRUE);
        }
        filterChain.doFilter(request, response);
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.security.MobileLoginFlagFilterTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: SecurityConfig에 필터 등록**

import 추가:

```java
import com.trova.backend.security.MobileLoginFlagFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
```

생성자에 주입 추가 (Task 2의 `jwtAuthenticationFilter` 옆에):

```java
    private final MobileLoginFlagFilter mobileLoginFlagFilter;

    public SecurityConfig(CustomOAuth2UserService customOAuth2UserService,
                           OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler,
                           OAuth2LoginFailureHandler oAuth2LoginFailureHandler,
                           JwtAuthenticationFilter jwtAuthenticationFilter,
                           MobileLoginFlagFilter mobileLoginFlagFilter) {
        this.customOAuth2UserService = customOAuth2UserService;
        this.oAuth2LoginSuccessHandler = oAuth2LoginSuccessHandler;
        this.oAuth2LoginFailureHandler = oAuth2LoginFailureHandler;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.mobileLoginFlagFilter = mobileLoginFlagFilter;
    }
```

`securityFilterChain`의 필터 체이닝에 추가 (Task 2에서 추가한
`.addFilterBefore(jwtAuthenticationFilter, ...)` 바로 다음에 이어서):

```java
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(mobileLoginFlagFilter, OAuth2AuthorizationRequestRedirectFilter.class);

        return http.build();
```

- [ ] **Step 6: 전체 빌드 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/trova/backend/security/MobileLoginFlagFilter.java \
  src/main/java/com/trova/backend/config/SecurityConfig.java \
  src/test/java/com/trova/backend/security/MobileLoginFlagFilterTest.java
git commit -m "feat: 모바일 OAuth 요청을 세션에 표시하는 필터 추가"
```

---

### Task 4: OAuth2LoginSuccessHandler / FailureHandler 모바일 분기

**Files:**
- Modify: `src/main/java/com/trova/backend/security/OAuth2LoginSuccessHandler.java`
- Modify: `src/main/java/com/trova/backend/security/OAuth2LoginFailureHandler.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application.yml`
- Test: `src/test/java/com/trova/backend/security/OAuth2LoginSuccessHandlerTest.java`

**Interfaces:**
- Consumes: `JwtService.issue(User)` (Task 1), `MOBILE_LOGIN` 세션
  속성(Task 3), 기존 `OAuth2UserInfo.of(...)` +
  `UserRepository.findByProviderAndProviderUserId(...)` (이미 존재)
- Produces: 모바일 로그인 성공 시 `{app.mobile-redirect-scheme}://auth?token={jwt}`로
  리다이렉트, 실패 시 `{scheme}://auth?error=oauth_failed`로 리다이렉트

- [ ] **Step 1: application.yml에 mobile-redirect-scheme 추가**

`src/main/resources/application.yml`의 `app:` 블록에 (jwt-secret
아래):

```yaml
  mobile-redirect-scheme: ${MOBILE_REDIRECT_SCHEME:trova}
```

`src/test/resources/application.yml`의 `app:` 블록에도 동일하게:

```yaml
  mobile-redirect-scheme: trova
```

- [ ] **Step 2: 실패하는 테스트 작성**

```java
package com.trova.backend.security;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuth2LoginSuccessHandlerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtService jwtService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private HttpSession session;

    private OAuth2LoginSuccessHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OAuth2LoginSuccessHandler(userRepository, jwtService);
        ReflectionTestUtils.setField(handler, "frontendUrl", "http://localhost:3000");
        ReflectionTestUtils.setField(handler, "mobileRedirectScheme", "trova");
    }

    private OAuth2AuthenticationToken tokenFor(String sub) {
        OAuth2User principal = new DefaultOAuth2User(
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")),
                Map.of("sub", sub, "name", "테스트", "picture", "https://example.com/p.jpg"),
                "sub"
        );
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
    }

    @Test
    void 모바일_로그인이면_JWT를_발급해서_딥링크로_리다이렉트한다() throws Exception {
        User user = new User("google", "42", "테스트", null);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("MOBILE_LOGIN")).thenReturn(Boolean.TRUE);
        when(userRepository.findByProviderAndProviderUserId("google", "42")).thenReturn(Optional.of(user));
        when(jwtService.issue(user)).thenReturn("jwt-token-value");

        handler.onAuthenticationSuccess(request, response, tokenFor("42"));

        verify(session).removeAttribute("MOBILE_LOGIN");
        verify(response).sendRedirect("trova://auth?token=jwt-token-value");
    }

    @Test
    void 웹_로그인이면_기존처럼_프론트_URL로_리다이렉트한다() throws Exception {
        when(request.getSession(false)).thenReturn(null);

        handler.onAuthenticationSuccess(request, response, tokenFor("42"));

        verify(response).sendRedirect("http://localhost:3000");
    }
}
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.security.OAuth2LoginSuccessHandlerTest"`
Expected: FAIL (생성자 시그니처가 아직 안 맞음 — 지금
`OAuth2LoginSuccessHandler`는 기본 생성자만 있음)

- [ ] **Step 4: OAuth2LoginSuccessHandler 수정**

파일 전체를 아래로 교체:

```java
package com.trova.backend.security;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final JwtService jwtService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Value("${app.mobile-redirect-scheme}")
    private String mobileRedirectScheme;

    public OAuth2LoginSuccessHandler(UserRepository userRepository, JwtService jwtService) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException, ServletException {
        HttpSession session = request.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute("MOBILE_LOGIN"))) {
            session.removeAttribute("MOBILE_LOGIN");
            OAuth2AuthenticationToken oauth2Token = (OAuth2AuthenticationToken) authentication;
            OAuth2UserInfo info = OAuth2UserInfo.of(
                    oauth2Token.getAuthorizedClientRegistrationId(),
                    oauth2Token.getPrincipal().getAttributes()
            );
            User user = userRepository.findByProviderAndProviderUserId(info.provider(), info.providerUserId())
                    .orElseThrow(() -> new IllegalStateException(
                            "인증된 사용자를 찾을 수 없습니다: " + info.provider() + " " + info.providerUserId()));
            String token = jwtService.issue(user);
            response.sendRedirect(mobileRedirectScheme + "://auth?token=" + token);
            return;
        }
        response.sendRedirect(frontendUrl);
    }
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.security.OAuth2LoginSuccessHandlerTest"`
Expected: PASS (2 tests)

- [ ] **Step 6: OAuth2LoginFailureHandler 수정 (테스트 없이 —
  Success handler와 동일 패턴이라 통합 테스트에서 간접 검증)**

파일 전체를 아래로 교체:

```java
package com.trova.backend.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Value("${app.mobile-redirect-scheme}")
    private String mobileRedirectScheme;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                         AuthenticationException exception) throws IOException, ServletException {
        HttpSession session = request.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute("MOBILE_LOGIN"))) {
            session.removeAttribute("MOBILE_LOGIN");
            response.sendRedirect(mobileRedirectScheme + "://auth?error=oauth_failed");
            return;
        }
        response.sendRedirect(frontendUrl + "/login?error=oauth_failed");
    }
}
```

- [ ] **Step 7: 전체 빌드 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/trova/backend/security/OAuth2LoginSuccessHandler.java \
  src/main/java/com/trova/backend/security/OAuth2LoginFailureHandler.java \
  src/main/resources/application.yml src/test/resources/application.yml \
  src/test/java/com/trova/backend/security/OAuth2LoginSuccessHandlerTest.java
git commit -m "feat: 모바일 OAuth 로그인 성공/실패 시 딥링크로 리다이렉트"
```

---

### Task 5: CurrentUserService를 Authentication 제네릭으로 전환

**Files:**
- Modify: `src/main/java/com/trova/backend/service/CurrentUserService.java`
- Modify: `src/test/java/com/trova/backend/service/CurrentUserServiceTest.java`

**Interfaces:**
- Consumes: `JwtAuthenticationToken.getUserId() -> Long` (Task 2)
- Produces: `CurrentUserService.resolve(Authentication authentication) -> User`
  (기존 `OAuth2AuthenticationToken` 전용 시그니처를 대체 — Task 6의
  8개 컨트롤러가 이 새 시그니처를 그대로 호출)

- [ ] **Step 1: 실패하는 테스트 추가**

`src/test/java/com/trova/backend/service/CurrentUserServiceTest.java`
맨 아래(마지막 `}` 바로 위)에 테스트 추가:

```java
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
```

파일 상단 import에 추가:

```java
import com.trova.backend.security.JwtAuthenticationToken;
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.service.CurrentUserServiceTest"`
Expected: FAIL (`userRepository.findById`가 없거나 시그니처 불일치 —
`resolve`가 아직 `OAuth2AuthenticationToken`만 받음)

- [ ] **Step 3: CurrentUserService 수정**

파일 전체를 아래로 교체:

```java
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
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.service.CurrentUserServiceTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: 전체 빌드 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. 컨트롤러들이 아직
`OAuth2AuthenticationToken authentication` 파라미터를 그대로 쓰고
있어도 컴파일은 문제없다 — 좁은 타입(`OAuth2AuthenticationToken`)
인자를 넓은 파라미터(`Authentication`)에 넘기는 호출은 항상 허용되기
때문이다. **다만 이 시점에서는 JWT 인증이 실제로는 아직 동작하지
않는다**: Spring이 `Authentication` 타입 컨트롤러 파라미터를 채울 때
"현재 인증 객체가 선언된 파라미터 타입의 인스턴스인가"를 확인하는데,
컨트롤러 파라미터가 여전히 `OAuth2AuthenticationToken`으로 못박혀
있으면 실제 인증 객체가 `JwtAuthenticationToken`인 모바일 요청에서는
이 조건을 만족 못 해 `authentication`이 `null`로 들어와
`currentUserService.resolve(null)`에서 NPE가 난다. 이건 Task 6에서
8개 컨트롤러 파라미터 타입을 `Authentication`으로 넓혀야 비로소
해결된다 — 그때까지는 기존 테스트가 통과하는 것만 확인하고 넘어간다.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/trova/backend/service/CurrentUserService.java \
  src/test/java/com/trova/backend/service/CurrentUserServiceTest.java
git commit -m "feat: CurrentUserService가 세션/JWT 인증을 모두 처리하도록 변경"
```

---

### Task 6: 8개 컨트롤러 파라미터 타입 일괄 변경 (배치 작업)

이 태스크는 8개 파일에 걸친 동일한 모양의 기계적 변경이라 하나의
태스크로 묶는다. 파일별로 `import
org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;`을
`import org.springframework.security.core.Authentication;`으로
바꾸고, 메서드 파라미터의 `OAuth2AuthenticationToken authentication`을
`Authentication authentication`으로 바꾼다. 호출부
(`currentUserService.resolve(authentication)`)는 전혀 손대지 않는다
(Task 5에서 이미 `Authentication`을 받도록 넓혀놨으므로 그대로
호환됨).

**Files (모두 Modify, 각각 import 1줄 + 파라미터 타입만 변경):**
- `src/main/java/com/trova/backend/controller/SharesController.java`
- `src/main/java/com/trova/backend/controller/NotificationController.java`
- `src/main/java/com/trova/backend/controller/TripController.java`
- `src/main/java/com/trova/backend/controller/BookmarkController.java`
- `src/main/java/com/trova/backend/controller/AuthController.java`
- `src/main/java/com/trova/backend/controller/UsersController.java`
- `src/main/java/com/trova/backend/controller/RecommendationController.java`
- `src/main/java/com/trova/backend/controller/PlacesController.java`

**Interfaces:**
- Consumes: `CurrentUserService.resolve(Authentication)` (Task 5)
- Produces: 없음 (외부에서 보이는 동작 변화 없음 — 내부 파라미터
  타입만 넓어짐)

- [ ] **Step 1: PlacesController.java 변경**

`import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;`
줄을
`import org.springframework.security.core.Authentication;`으로 교체.

파일 안의 아래 6개 메서드 시그니처에서 `OAuth2AuthenticationToken
authentication`을 `Authentication authentication`으로 교체:
`list`, `pending`, `get`, `delete`, `moveDay`, `reorder`,
`optimizeRoute`, `generateItinerary` (총 8곳 — 파라미터가 있는 모든
public 메서드).

- [ ] **Step 2: SharesController.java 변경**

같은 import 교체. 57번째 줄 근처 `OAuth2AuthenticationToken
authentication`을 `Authentication authentication`으로 교체.

- [ ] **Step 3: NotificationController.java 변경**

같은 import 교체. `list`, `dismiss` 두 메서드의 파라미터 타입 교체.

- [ ] **Step 4: TripController.java 변경**

같은 import 교체. `OAuth2AuthenticationToken authentication`이 나오는
모든 곳(총 9곳)을 `Authentication authentication`으로 교체.

- [ ] **Step 5: BookmarkController.java 변경**

같은 import 교체. `list`, `create`, `delete` 세 메서드의 파라미터
타입 교체.

- [ ] **Step 6: AuthController.java 변경**

같은 import 교체. `me` 메서드의 파라미터 타입 교체.

- [ ] **Step 7: UsersController.java 변경**

같은 import 교체 (단, `SecurityContextLogoutHandler` import는 그대로
둠 — 별개). `me`와 그 아래 메서드(로그아웃 등)의 파라미터 타입 교체.

- [ ] **Step 8: RecommendationController.java 변경**

같은 import 교체. `recommend` 메서드의 파라미터 타입 교체.

- [ ] **Step 9: 전체 빌드 + 테스트 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — 모든 기존 컨트롤러 테스트(`oauth2Login()`
post-processor를 쓰는 것들 포함) 그대로 통과. `oauth2Login()`이
생성하는 `OAuth2AuthenticationToken`은 `Authentication`의 하위
타입이라 파라미터 바인딩에 문제없음.

- [ ] **Step 10: 커밋**

```bash
git add src/main/java/com/trova/backend/controller/SharesController.java \
  src/main/java/com/trova/backend/controller/NotificationController.java \
  src/main/java/com/trova/backend/controller/TripController.java \
  src/main/java/com/trova/backend/controller/BookmarkController.java \
  src/main/java/com/trova/backend/controller/AuthController.java \
  src/main/java/com/trova/backend/controller/UsersController.java \
  src/main/java/com/trova/backend/controller/RecommendationController.java \
  src/main/java/com/trova/backend/controller/PlacesController.java
git commit -m "refactor: 컨트롤러들이 세션/JWT 인증을 모두 받도록 파라미터 타입 일반화"
```

- [ ] **Step 11: 웹 로그인 수동 회귀 확인**

로컬 백엔드 재시작 후, 웹 프론트(`trova-frontend`)에서 카카오/구글
로그인 → `/places`, `/trips` 등 인증이 필요한 페이지가 여전히 정상
동작하는지 브라우저로 직접 확인. (이 계획의 "기존 웹 로그인 회귀
금지" 전역 제약의 최종 확인 지점)

---

## 앱 태스크 (새 레포 `trova-app`)

> 아래 태스크를 시작하기 전에: 사용자에게 `trova-app` 레포를
> Claude가 `gh repo create`로 만들지, 사용자가 직접 만들지 확인한다.
> 로컬 경로는 `/Users/gimtaehyeong/Desktop/trova-app` 제안 (기존
> `trova-frontend`가 `~/Desktop`에 있는 것과 동일한 위치 관례).

### Task 7: Expo 프로젝트 스캐폴딩

**Files:**
- Create: `trova-app/` (전체 프로젝트, `create-expo-app`이 생성)
- Modify: `trova-app/app.json`
- Create: `trova-app/.env`
- Create: `trova-app/.env.example`

**Interfaces:**
- Produces: `EXPO_PUBLIC_API_BASE_URL`, `EXPO_PUBLIC_KAKAO_MAP_JS_KEY`
  환경변수 (이후 모든 태스크의 API/지도 코드가 `process.env.EXPO_PUBLIC_*`로
  읽음). `app.json`의 `"scheme": "trova"` (Task 4의 백엔드 딥링크
  리다이렉트 대상과 반드시 일치해야 함)

- [ ] **Step 1: Expo 프로젝트 생성**

```bash
cd /Users/gimtaehyeong/Desktop
npx create-expo-app@latest trova-app --template blank-typescript
cd trova-app
```

- [ ] **Step 2: 의존성 설치**

```bash
npx expo install @react-navigation/native @react-navigation/native-stack \
  react-native-screens react-native-safe-area-context \
  react-native-webview expo-secure-store expo-web-browser expo-linking \
  expo-splash-screen
npm install @tanstack/react-query
npx expo install @expo-google-fonts/ibm-plex-mono expo-font
```

(`npx expo install`은 지금 설치된 Expo SDK 버전에 맞는 호환 버전을
자동으로 골라줌 — `npm install`보다 이 프로젝트에서 우선)

- [ ] **Step 3: app.json에 커스텀 스킴 추가**

`app.json`의 `"expo": { ... }` 블록 안, `"name"` 옆에 추가:

```json
    "scheme": "trova",
```

- [ ] **Step 4: 환경변수 파일 작성**

`.env` 생성:

```bash
EXPO_PUBLIC_API_BASE_URL=http://localhost:8080
EXPO_PUBLIC_KAKAO_MAP_JS_KEY=여기에_trova-frontend의_NEXT_PUBLIC_KAKAO_MAP_JS_KEY와_동일한_값
```

`.env.example` 생성 (커밋 대상, 실제 값 없이 키 이름만):

```bash
EXPO_PUBLIC_API_BASE_URL=http://localhost:8080
EXPO_PUBLIC_KAKAO_MAP_JS_KEY=
```

`.gitignore`에 `.env`가 이미 포함돼 있는지 확인(Expo 기본 템플릿의
`.gitignore`에는 보통 없으므로 직접 추가):

```
.env
```

- [ ] **Step 5: 개발 서버 실행 확인**

```bash
npx expo start
```

Expected: QR 코드와 함께 Metro 번들러가 정상 기동. 시뮬레이터나 Expo
Go 앱으로 스캔해서 기본 템플릿 화면이 뜨는지 확인.

- [ ] **Step 6: 초기 커밋**

```bash
git init
git add -A
git commit -m "chore: Expo 프로젝트 초기 세팅"
```

(원격 저장소 연결은 사용자 확인 후 이후 단계에서 — `git remote add
origin ...` 및 `gh repo create`는 finishing-a-development-branch 단계
또는 사용자 지시에 따름)

---

### Task 8: 폰트 및 플랫폼 공통 설정

**Files:**
- Create: `trova-app/src/components/AppText.tsx`
- Modify: `trova-app/App.tsx`

**Interfaces:**
- Produces: `<AppText weight="regular" | "medium">` 컴포넌트 (이후
  모든 화면이 RN 기본 `Text` 대신 이걸 사용), `App.tsx`가 폰트 로딩이
  끝나기 전까지 스플래시 화면을 유지

- [ ] **Step 1: AppText 컴포넌트 작성**

```tsx
import { Text, type TextProps } from "react-native";

export function AppText({
  weight = "regular",
  style,
  ...props
}: TextProps & { weight?: "regular" | "medium" }) {
  const fontFamily = weight === "medium" ? "IBMPlexMono_500Medium" : "IBMPlexMono_400Regular";
  return <Text style={[{ fontFamily }, style]} {...props} />;
}
```

- [ ] **Step 2: App.tsx에 폰트 로딩 + 스플래시 연동**

```tsx
import { useEffect } from "react";
import { useFonts, IBMPlexMono_400Regular, IBMPlexMono_500Medium } from "@expo-google-fonts/ibm-plex-mono";
import * as SplashScreen from "expo-splash-screen";
import { StatusBar } from "expo-status-bar";
import { SafeAreaProvider } from "react-native-safe-area-context";

SplashScreen.preventAutoHideAsync();

export default function App() {
  const [fontsLoaded] = useFonts({
    IBMPlexMono_400Regular,
    IBMPlexMono_500Medium,
  });

  useEffect(() => {
    if (fontsLoaded) {
      SplashScreen.hideAsync();
    }
  }, [fontsLoaded]);

  if (!fontsLoaded) {
    return null;
  }

  return (
    <SafeAreaProvider>
      <StatusBar style="dark" />
      {/* Task 15에서 여기에 네비게이션 컨테이너가 들어감 */}
    </SafeAreaProvider>
  );
}
```

`expo-status-bar`는 Expo 기본 템플릿에 이미 포함돼 있어 별도 설치
불필요 (없다면 `npx expo install expo-status-bar` 실행).

- [ ] **Step 3: 실행 확인**

```bash
npx expo start
```

Expected: 스플래시 화면이 뜬 뒤 빈 흰 화면(SafeAreaProvider만 있는
상태)으로 전환됨. 콘솔에 폰트 로드 에러가 없어야 함.

- [ ] **Step 4: 커밋**

```bash
git add src/components/AppText.tsx App.tsx
git commit -m "feat: IBM Plex Mono 폰트 로딩 및 공통 텍스트 컴포넌트 추가"
```

---

### Task 9: API 클라이언트

**Files:**
- Create: `trova-app/src/lib/api/client.ts`
- Create: `trova-app/src/lib/api/auth.ts`
- Create: `trova-app/src/lib/api/places.ts`
- Create: `trova-app/src/lib/tokenStorage.ts`

**Interfaces:**
- Produces: `apiFetch(path: string, options?: RequestInit) -> Promise<Response>`
  (Authorization 헤더 자동 첨부, 401이면 저장된 토큰 삭제 후 원본
  Response를 그대로 반환), `setUnauthorizedHandler(() => void) -> void`
  (Task 10의 AuthProvider가 이걸로 자신의 `setUser(null)`을 등록해서
  401 발생 시 로그인 화면으로 즉시 전환되게 함),
  `getToken()`/`setToken(string)`/`clearToken() -> Promise<void>`,
  `getMe() -> Promise<{id:number, nickname:string|null, profileImageUrl:string|null}>`,
  `createShare(url: string) -> Promise<{jobId: number}>`,
  `getPendingJobs() -> Promise<PendingJob[]>`,
  `getPlaces() -> Promise<Place[]>`, `getPlace(id: number) -> Promise<Place | undefined>`

- [ ] **Step 1: 토큰 저장소**

```ts
import * as SecureStore from "expo-secure-store";

const TOKEN_KEY = "trova_auth_token";

export async function getToken(): Promise<string | null> {
  return SecureStore.getItemAsync(TOKEN_KEY);
}

export async function setToken(token: string): Promise<void> {
  await SecureStore.setItemAsync(TOKEN_KEY, token);
}

export async function clearToken(): Promise<void> {
  await SecureStore.deleteItemAsync(TOKEN_KEY);
}
```

- [ ] **Step 2: fetch 래퍼**

```ts
import { clearToken, getToken } from "@/lib/tokenStorage";

const API_BASE_URL = process.env.EXPO_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

// AuthProvider가 등록해두는 콜백 — 401을 받으면 토큰만 지우는 게 아니라
// 인증 상태(user)도 즉시 null로 바꿔서 네비게이터가 로그인 화면으로
// 전환되게 한다. 모듈 스코프 변수로 두는 이유는 apiFetch가 훅이 아니라
// 어디서든 호출되는 평범한 함수라 React context를 직접 구독할 수 없기
// 때문.
let unauthorizedHandler: (() => void) | null = null;

export function setUnauthorizedHandler(handler: () => void): void {
  unauthorizedHandler = handler;
}

export async function apiFetch(path: string, options: RequestInit = {}): Promise<Response> {
  const token = await getToken();
  const headers = new Headers(options.headers);
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const res = await fetch(`${API_BASE_URL}${path}`, { ...options, headers });
  if (res.status === 401) {
    await clearToken();
    unauthorizedHandler?.();
  }
  return res;
}
```

(`@/lib/...` 경로 별칭은 Expo TypeScript 템플릿의 `tsconfig.json`에
`"paths": {"@/*": ["./src/*"]}`를 추가해야 동작 — 없으면 이 스텝에서
`tsconfig.json`에 추가할 것)

- [ ] **Step 3: 인증 API**

```ts
import { apiFetch } from "@/lib/api/client";

export type CurrentUser = {
  id: number;
  nickname: string | null;
  profileImageUrl: string | null;
};

export async function getMe(): Promise<CurrentUser | null> {
  const res = await apiFetch("/api/auth/me");
  if (res.status === 401) {
    return null;
  }
  if (!res.ok) {
    throw new Error(`GET /api/auth/me failed: ${res.status}`);
  }
  return res.json();
}

const API_BASE_URL = process.env.EXPO_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export function oauthUrl(provider: "kakao" | "google"): string {
  return `${API_BASE_URL}/oauth2/authorization/${provider}?mobile=true`;
}
```

- [ ] **Step 4: 장소/공유 API**

```ts
import { apiFetch } from "@/lib/api/client";

export type Place = {
  id: number;
  jobId: number;
  placeName: string;
  region: string | null;
  category: string | null;
  latitude: number | null;
  longitude: number | null;
  sourceUrl: string;
  title: string | null;
  sourcePlatform: "INSTAGRAM" | "YOUTUBE";
  createdAt: string;
  address: string | null;
};

export type PendingJob = {
  jobId: number;
  sourceUrl: string;
  title: string | null;
  sourcePlatform: "INSTAGRAM" | "YOUTUBE";
  status: "PENDING" | "PROCESSING" | "FAILED";
  createdAt: string;
};

export async function createShare(url: string): Promise<{ jobId: number }> {
  const res = await apiFetch("/api/shares", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url }),
  });
  if (res.status === 401) {
    throw new Error("로그인이 필요해요.");
  }
  if (res.status === 400) {
    const body = await res.json().catch(() => null);
    throw new Error(body?.message ?? "지원하지 않는 URL입니다.");
  }
  if (!res.ok) {
    throw new Error(`POST /api/shares failed: ${res.status}`);
  }
  return res.json();
}

export async function getPendingJobs(): Promise<PendingJob[]> {
  const res = await apiFetch("/api/places/pending");
  if (!res.ok) {
    throw new Error(`GET /api/places/pending failed: ${res.status}`);
  }
  return res.json();
}

export async function getPlaces(): Promise<Place[]> {
  const res = await apiFetch("/api/places");
  if (!res.ok) {
    throw new Error(`GET /api/places failed: ${res.status}`);
  }
  return res.json();
}

export async function getPlace(id: number): Promise<Place | undefined> {
  const res = await apiFetch(`/api/places/${id}`);
  if (res.status === 404) {
    return undefined;
  }
  if (!res.ok) {
    throw new Error(`GET /api/places/${id} failed: ${res.status}`);
  }
  return res.json();
}
```

- [ ] **Step 5: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음 (경로 별칭 `@/*`가 `tsconfig.json`에 없다면 여기서
"Cannot find module '@/lib/...'" 에러 — 이 경우 `tsconfig.json`의
`compilerOptions`에 다음을 추가:
`"baseUrl": ".", "paths": {"@/*": ["./src/*"]}`)

- [ ] **Step 6: 커밋**

```bash
git add src/lib/tokenStorage.ts src/lib/api/client.ts src/lib/api/auth.ts \
  src/lib/api/places.ts tsconfig.json
git commit -m "feat: 백엔드 API 클라이언트 및 토큰 저장소 추가"
```

---

### Task 10: 인증 컨텍스트 + 로그인 화면 + 딥링크 처리

**Files:**
- Create: `trova-app/src/lib/auth/AuthContext.tsx`
- Create: `trova-app/src/screens/LoginScreen.tsx`

**Interfaces:**
- Consumes: `getMe`, `oauthUrl` (Task 9), `setToken`/`clearToken`/
  `setUnauthorizedHandler` (Task 9), `AppText` (Task 8)
- Produces: `useAuth() -> {user, loading, refresh, logout}` (Task 15의
  네비게이션이 이 값을 보고 로그인 화면 vs 메인 화면을 분기),
  `<LoginScreen>` 컴포넌트

- [ ] **Step 1: AuthContext 작성**

```tsx
import { createContext, useCallback, useContext, useEffect, useState } from "react";
import * as Linking from "expo-linking";
import { getMe, type CurrentUser } from "@/lib/api/auth";
import { setUnauthorizedHandler } from "@/lib/api/client";
import { clearToken, setToken } from "@/lib/tokenStorage";

type AuthState = {
  user: CurrentUser | null;
  loading: boolean;
  refresh: () => Promise<void>;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      setUser(await getMe());
    } finally {
      setLoading(false);
    }
  }, []);

  const logout = useCallback(async () => {
    await clearToken();
    setUser(null);
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  useEffect(() => {
    setUnauthorizedHandler(() => setUser(null));
  }, []);

  useEffect(() => {
    const subscription = Linking.addEventListener("url", async ({ url }) => {
      const { queryParams } = Linking.parse(url);
      const token = queryParams?.token;
      if (typeof token === "string") {
        await setToken(token);
        await refresh();
      }
    });
    return () => subscription.remove();
  }, [refresh]);

  return (
    <AuthContext.Provider value={{ user, loading, refresh, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return ctx;
}
```

(딥링크 `trova://auth?token=...`는 `expo-linking`의 `url` 이벤트로
받는다 — 앱이 이미 실행 중일 때. 앱이 완전히 종료된 상태에서
콜드스타트로 딥링크가 열리는 경우는 `Linking.getInitialURL()`로도
처리해야 하나, 1단계 범위에서는 인앱 브라우저를 통해 로그인하는
동안 앱이 백그라운드에 남아있는 게 일반적인 흐름이라 `addEventListener`만으로
충분 — 콜드스타트 케이스는 2단계 이후 필요시 보강)

- [ ] **Step 2: 로그인 화면 작성**

```tsx
import { Pressable, View } from "react-native";
import * as WebBrowser from "expo-web-browser";
import { AppText } from "@/components/AppText";
import { oauthUrl } from "@/lib/api/auth";

export function LoginScreen() {
  async function handleLogin(provider: "kakao" | "google") {
    await WebBrowser.openAuthSessionAsync(oauthUrl(provider), "trova://auth");
  }

  return (
    <View style={{ flex: 1, justifyContent: "center", padding: 24, gap: 12 }}>
      <AppText weight="medium" style={{ fontSize: 24, marginBottom: 24, textAlign: "center" }}>
        Trova에 로그인
      </AppText>
      <Pressable
        onPress={() => handleLogin("kakao")}
        style={{ height: 48, borderRadius: 12, backgroundColor: "#FEE500", justifyContent: "center", alignItems: "center" }}
      >
        <AppText weight="medium">카카오로 시작하기</AppText>
      </Pressable>
      <Pressable
        onPress={() => handleLogin("google")}
        style={{ height: 48, borderRadius: 12, borderWidth: 1, borderColor: "#DEDED8", justifyContent: "center", alignItems: "center" }}
      >
        <AppText weight="medium">Google로 계속하기</AppText>
      </Pressable>
    </View>
  );
}
```

- [ ] **Step 3: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add src/lib/auth/AuthContext.tsx src/screens/LoginScreen.tsx
git commit -m "feat: 인증 컨텍스트와 로그인 화면 추가"
```

(실제 로그인 왕복 동작 확인은 Task 15에서 네비게이션이 완성된 뒤
시뮬레이터로 수행 — 그 전까지는 화면 단독으로는 딥링크 콜백을 받을
루트가 없어 부분 검증만 가능)

---

### Task 11: 홈 / 링크 공유 화면

**Files:**
- Create: `trova-app/src/screens/HomeScreen.tsx`

**Interfaces:**
- Consumes: `createShare`, `getPendingJobs` (Task 9), `AppText`
  (Task 8)
- Produces: `<HomeScreen navigation>` — 공유 처리 성공 시
  `navigation.navigate("PlacesList")`로 이동 (Task 15 네비게이터가
  이 라우트 이름을 등록)

- [ ] **Step 1: 화면 작성**

```tsx
import { useState } from "react";
import { KeyboardAvoidingView, Platform, Pressable, TextInput } from "react-native";
import { AppText } from "@/components/AppText";
import { createShare } from "@/lib/api/places";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";

type Props = {
  navigation: NativeStackNavigationProp<Record<string, object | undefined>>;
};

export function HomeScreen({ navigation }: Props) {
  const [url, setUrl] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit() {
    if (!url.trim() || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await createShare(url.trim());
      navigation.navigate("PlacesList");
    } catch (err) {
      setError(err instanceof Error ? err.message : "요청에 실패했어요.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <KeyboardAvoidingView
      behavior={Platform.OS === "ios" ? "padding" : undefined}
      style={{ flex: 1, padding: 24, justifyContent: "center", gap: 12 }}
    >
      <AppText weight="medium" style={{ fontSize: 20, marginBottom: 8 }}>
        여행 영상 링크를 붙여넣으세요
      </AppText>
      <TextInput
        value={url}
        onChangeText={setUrl}
        placeholder="인스타그램 또는 유튜브 링크"
        autoCapitalize="none"
        autoCorrect={false}
        style={{
          height: 48,
          borderWidth: 1,
          borderColor: "#DEDED8",
          borderRadius: 12,
          paddingHorizontal: 16,
          fontFamily: "IBMPlexMono_400Regular",
        }}
      />
      <Pressable
        onPress={handleSubmit}
        disabled={submitting}
        style={{
          height: 48,
          borderRadius: 12,
          backgroundColor: "#FF6B4A",
          justifyContent: "center",
          alignItems: "center",
          opacity: submitting ? 0.6 : 1,
        }}
      >
        <AppText weight="medium" style={{ color: "#fff" }}>
          {submitting ? "추출 중..." : "장소 추출하기"}
        </AppText>
      </Pressable>
      {error && <AppText style={{ color: "#FF6B4A" }}>{error}</AppText>}
    </KeyboardAvoidingView>
  );
}
```

(Android는 `KeyboardAvoidingView`의 `behavior`를 `undefined`로 둬서
OS 기본 리사이즈 동작에 맡긴다 — `app.json`의
`"android": {"softwareKeyboardLayoutMode": "resize"}` 설정과 함께
동작. `app.json`에 `android` 블록이 없다면 이 스텝에서 추가할 것)

- [ ] **Step 2: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음 (Task 15 전이라 네비게이션 타입은 임시로 느슨하게
잡아둠 — `Record<string, object | undefined>`)

- [ ] **Step 3: 커밋**

```bash
git add src/screens/HomeScreen.tsx app.json
git commit -m "feat: 링크 공유 입력 홈 화면 추가"
```

---

### Task 12: 저장한 장소 목록 화면

**Files:**
- Create: `trova-app/src/screens/PlacesListScreen.tsx`

**Interfaces:**
- Consumes: `getPlaces`, `getPendingJobs`, `Place`, `PendingJob`
  (Task 9), `AppText` (Task 8), `@tanstack/react-query`
- Produces: `<PlacesListScreen navigation>` — 항목 클릭 시
  `navigation.navigate("PlaceDetail", {id})`로 이동

- [ ] **Step 1: 화면 작성**

```tsx
import { FlatList, Platform, Pressable, View } from "react-native";
import { useQuery } from "@tanstack/react-query";
import { AppText } from "@/components/AppText";
import { getPendingJobs, getPlaces } from "@/lib/api/places";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";

type Props = {
  navigation: NativeStackNavigationProp<Record<string, object | undefined>>;
};

const CARD_SHADOW = Platform.select({
  ios: { shadowColor: "#000", shadowOffset: { width: 0, height: 1 }, shadowOpacity: 0.08, shadowRadius: 3 },
  android: { elevation: 2 },
});

export function PlacesListScreen({ navigation }: Props) {
  const placesQuery = useQuery({ queryKey: ["places"], queryFn: getPlaces });
  const pendingQuery = useQuery({ queryKey: ["pendingJobs"], queryFn: getPendingJobs });

  if (placesQuery.isLoading || pendingQuery.isLoading) {
    return (
      <View style={{ flex: 1, justifyContent: "center", alignItems: "center" }}>
        <AppText>불러오는 중...</AppText>
      </View>
    );
  }

  const places = placesQuery.data ?? [];
  const pendingCount = pendingQuery.data?.length ?? 0;

  return (
    <FlatList
      contentContainerStyle={{ padding: 16, gap: 12 }}
      data={places}
      keyExtractor={(item) => String(item.id)}
      ListHeaderComponent={
        pendingCount > 0 ? (
          <AppText style={{ marginBottom: 12, color: "#8C8C86" }}>
            처리 중인 링크 {pendingCount}개
          </AppText>
        ) : null
      }
      ListEmptyComponent={<AppText style={{ textAlign: "center", marginTop: 32 }}>아직 저장한 장소가 없어요.</AppText>}
      renderItem={({ item }) => (
        <Pressable
          onPress={() => navigation.navigate("PlaceDetail", { id: item.id })}
          style={{
            padding: 16,
            borderRadius: 12,
            borderWidth: 1,
            borderColor: "#DEDED8",
            backgroundColor: "#fff",
            ...CARD_SHADOW,
          }}
        >
          <AppText weight="medium">{item.placeName}</AppText>
          {item.address && <AppText style={{ marginTop: 4, fontSize: 12, color: "#8C8C86" }}>{item.address}</AppText>}
        </Pressable>
      )}
    />
  );
}
```

- [ ] **Step 2: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add src/screens/PlacesListScreen.tsx
git commit -m "feat: 저장한 장소 목록 화면 추가"
```

---

### Task 13: 장소 상세 화면

**Files:**
- Create: `trova-app/src/screens/PlaceDetailScreen.tsx`

**Interfaces:**
- Consumes: `getPlace` (Task 9), `AppText` (Task 8)
- Produces: `<PlaceDetailScreen route>` — `route.params.id`로 장소 ID를
  받음, "지도에서 보기" 버튼으로 `navigation.navigate("Map", {id})`
  이동 (Task 14가 이 라우트를 소비)

- [ ] **Step 1: 화면 작성**

```tsx
import { Pressable, View } from "react-native";
import { useQuery } from "@tanstack/react-query";
import { AppText } from "@/components/AppText";
import { getPlace } from "@/lib/api/places";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import type { RouteProp } from "@react-navigation/native";

type Props = {
  route: RouteProp<Record<string, { id: number }>, string>;
  navigation: NativeStackNavigationProp<Record<string, object | undefined>>;
};

export function PlaceDetailScreen({ route, navigation }: Props) {
  const { id } = route.params;
  const { data: place, isLoading } = useQuery({
    queryKey: ["place", id],
    queryFn: () => getPlace(id),
  });

  if (isLoading) {
    return (
      <View style={{ flex: 1, justifyContent: "center", alignItems: "center" }}>
        <AppText>불러오는 중...</AppText>
      </View>
    );
  }

  if (!place) {
    return (
      <View style={{ flex: 1, justifyContent: "center", alignItems: "center" }}>
        <AppText>장소를 찾을 수 없어요.</AppText>
      </View>
    );
  }

  return (
    <View style={{ flex: 1, padding: 24, gap: 12 }}>
      <AppText weight="medium" style={{ fontSize: 22 }}>{place.placeName}</AppText>
      {place.address && <AppText style={{ color: "#8C8C86" }}>{place.address}</AppText>}
      {place.category && <AppText>{place.category}</AppText>}
      <Pressable
        onPress={() => navigation.navigate("Map", { id: place.id })}
        style={{ marginTop: 24, height: 48, borderRadius: 12, backgroundColor: "#FF6B4A", justifyContent: "center", alignItems: "center" }}
      >
        <AppText weight="medium" style={{ color: "#fff" }}>지도에서 보기</AppText>
      </Pressable>
    </View>
  );
}
```

- [ ] **Step 2: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add src/screens/PlaceDetailScreen.tsx
git commit -m "feat: 장소 상세 화면 추가"
```

---

### Task 14: 지도 화면 (WebView + 카카오맵)

**Files:**
- Create: `trova-app/src/lib/kakaoMapHtml.ts`
- Create: `trova-app/src/screens/MapScreen.tsx`

**Interfaces:**
- Consumes: `getPlace` (Task 9), `MapPin`(신규 타입: `{id: string,
  latitude: number, longitude: number}`)
- Produces: `buildKakaoMapHtml(appKey: string) -> string`,
  `<MapScreen route>` (핀 1개 — 상세 화면에서 넘어온 장소 하나만
  표시. 1단계는 저장한 장소 개별 위치 확인이 목적이라 여러 핀을 한
  지도에 모으는 건 2단계 "내 지도" 기능으로 미룸)

- [ ] **Step 1: 카카오맵 HTML 템플릿 작성**

`trova-frontend/src/components/KakaoMap.tsx`의 `buildPinElement`/지도
초기화 로직을 참고해, RN WebView 안에서 실행될 순수 HTML/JS로
이식한다:

```ts
export function buildKakaoMapHtml(appKey: string): string {
  return `
<!DOCTYPE html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
  <style>html, body, #map { width: 100%; height: 100%; margin: 0; padding: 0; }</style>
</head>
<body>
  <div id="map"></div>
  <script src="//dapi.kakao.com/v2/maps/sdk.js?appkey=${appKey}&autoload=false"></script>
  <script>
    function renderPins(pins) {
      kakao.maps.load(function () {
        var container = document.getElementById('map');
        var first = pins[0];
        var center = new kakao.maps.LatLng(first.latitude, first.longitude);
        var map = new kakao.maps.Map(container, { center: center, level: 4 });

        pins.forEach(function (pin, index) {
          var position = new kakao.maps.LatLng(pin.latitude, pin.longitude);
          var el = document.createElement('div');
          el.textContent = String(index + 1);
          el.style.cssText = 'width:26px;height:26px;border-radius:9999px;background:#FF6B4A;' +
            'color:#fff;display:flex;align-items:center;justify-content:center;' +
            'font-size:12px;font-weight:700;border:2px solid #fff;box-shadow:0 1px 3px rgba(0,0,0,0.35);';
          new kakao.maps.CustomOverlay({ position: position, content: el, zIndex: 2 }).setMap(map);
        });
      });
    }

    function handleMessage(event) {
      try {
        var pins = JSON.parse(event.data);
        renderPins(pins);
      } catch (e) {
        // 무시 — 핀 데이터가 아닌 다른 메시지일 수 있음
      }
    }

    document.addEventListener('message', handleMessage);
    window.addEventListener('message', handleMessage);
  </script>
</body>
</html>
  `;
}
```

(`document.addEventListener`와 `window.addEventListener` 둘 다
등록하는 이유: `react-native-webview`의 `postMessage`가 Android에서는
`document`로, iOS에서는 `window`로 이벤트를 쏴서 두 플랫폼 모두
받으려면 양쪽에 리스너가 필요함 — react-native-webview 공식 문서에
명시된 크로스플랫폼 대응 패턴)

- [ ] **Step 2: MapScreen 작성**

```tsx
import { useEffect, useRef } from "react";
import { View } from "react-native";
import { WebView } from "react-native-webview";
import { useQuery } from "@tanstack/react-query";
import { AppText } from "@/components/AppText";
import { getPlace } from "@/lib/api/places";
import { buildKakaoMapHtml } from "@/lib/kakaoMapHtml";
import type { RouteProp } from "@react-navigation/native";

type Props = {
  route: RouteProp<Record<string, { id: number }>, string>;
};

const KAKAO_MAP_JS_KEY = process.env.EXPO_PUBLIC_KAKAO_MAP_JS_KEY ?? "";

export function MapScreen({ route }: Props) {
  const { id } = route.params;
  const webviewRef = useRef<WebView>(null);
  const { data: place, isLoading } = useQuery({
    queryKey: ["place", id],
    queryFn: () => getPlace(id),
  });

  useEffect(() => {
    if (!place || place.latitude === null || place.longitude === null) return;
    const pins = [{ id: String(place.id), latitude: place.latitude, longitude: place.longitude }];
    // WebView가 HTML을 완전히 로드하기 전에 postMessage가 도착하면 씹힐 수 있어
    // onLoadEnd 이후 약간의 지연을 둔다 (react-native-webview에서 흔히 쓰는 임시방편).
    const timer = setTimeout(() => {
      webviewRef.current?.postMessage(JSON.stringify(pins));
    }, 300);
    return () => clearTimeout(timer);
  }, [place]);

  if (isLoading) {
    return (
      <View style={{ flex: 1, justifyContent: "center", alignItems: "center" }}>
        <AppText>불러오는 중...</AppText>
      </View>
    );
  }

  if (!place || place.latitude === null || place.longitude === null) {
    return (
      <View style={{ flex: 1, justifyContent: "center", alignItems: "center" }}>
        <AppText>이 장소는 좌표 정보가 없어요.</AppText>
      </View>
    );
  }

  return (
    <WebView
      ref={webviewRef}
      source={{ html: buildKakaoMapHtml(KAKAO_MAP_JS_KEY) }}
      style={{ flex: 1 }}
    />
  );
}
```

- [ ] **Step 3: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add src/lib/kakaoMapHtml.ts src/screens/MapScreen.tsx
git commit -m "feat: 카카오맵 WebView 지도 화면 추가"
```

---

### Task 15: 네비게이션 조립 + 전체 동작 확인

**Files:**
- Modify: `trova-app/App.tsx`
- Create: `trova-app/src/navigation/RootNavigator.tsx`

**Interfaces:**
- Consumes: 모든 이전 태스크의 컴포넌트 —
  `AuthProvider`/`useAuth`(Task 10), `LoginScreen`(Task 10),
  `HomeScreen`(Task 11), `PlacesListScreen`(Task 12),
  `PlaceDetailScreen`(Task 13), `MapScreen`(Task 14)
- Produces: 완성된 1단계 앱 — 로그인 → 홈 → 저장한 장소 목록 → 상세
  → 지도까지 실제로 동작하는 네비게이션 트리

- [ ] **Step 1: RootNavigator 작성**

```tsx
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { useAuth } from "@/lib/auth/AuthContext";
import { LoginScreen } from "@/screens/LoginScreen";
import { HomeScreen } from "@/screens/HomeScreen";
import { PlacesListScreen } from "@/screens/PlacesListScreen";
import { PlaceDetailScreen } from "@/screens/PlaceDetailScreen";
import { MapScreen } from "@/screens/MapScreen";
import { AppText } from "@/components/AppText";
import { View } from "react-native";

const Stack = createNativeStackNavigator();

export function RootNavigator() {
  const { user, loading } = useAuth();

  if (loading) {
    return (
      <View style={{ flex: 1, justifyContent: "center", alignItems: "center" }}>
        <AppText>불러오는 중...</AppText>
      </View>
    );
  }

  return (
    <Stack.Navigator screenOptions={{ headerTitleAlign: "center" }}>
      {!user ? (
        <Stack.Screen name="Login" component={LoginScreen} options={{ headerShown: false }} />
      ) : (
        <>
          <Stack.Screen name="Home" component={HomeScreen} options={{ title: "Trova" }} />
          <Stack.Screen name="PlacesList" component={PlacesListScreen} options={{ title: "저장한 장소" }} />
          <Stack.Screen name="PlaceDetail" component={PlaceDetailScreen} options={{ title: "장소 상세" }} />
          <Stack.Screen name="Map" component={MapScreen} options={{ title: "지도" }} />
        </>
      )}
    </Stack.Navigator>
  );
}
```

(`headerTitleAlign: "center"`는 Android 기본값(좌측 정렬)을 iOS
기본값(중앙 정렬)에 맞춰 통일하는 설정 — 스펙의 플랫폼 배치 통일
요구사항)

- [ ] **Step 2: App.tsx에 네비게이션 + Provider 조립**

`App.tsx`를 아래로 교체 (Task 8에서 만든 폰트 로딩 로직은 그대로
유지하고 그 안에 아래 내용을 채워 넣음):

```tsx
import { useEffect } from "react";
import { useFonts, IBMPlexMono_400Regular, IBMPlexMono_500Medium } from "@expo-google-fonts/ibm-plex-mono";
import * as SplashScreen from "expo-splash-screen";
import { StatusBar } from "expo-status-bar";
import { SafeAreaProvider } from "react-native-safe-area-context";
import { NavigationContainer } from "@react-navigation/native";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { AuthProvider } from "@/lib/auth/AuthContext";
import { RootNavigator } from "@/navigation/RootNavigator";

SplashScreen.preventAutoHideAsync();

const queryClient = new QueryClient();

export default function App() {
  const [fontsLoaded] = useFonts({
    IBMPlexMono_400Regular,
    IBMPlexMono_500Medium,
  });

  useEffect(() => {
    if (fontsLoaded) {
      SplashScreen.hideAsync();
    }
  }, [fontsLoaded]);

  if (!fontsLoaded) {
    return null;
  }

  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <SafeAreaProvider>
          <StatusBar style="dark" />
          <NavigationContainer>
            <RootNavigator />
          </NavigationContainer>
        </SafeAreaProvider>
      </AuthProvider>
    </QueryClientProvider>
  );
}
```

- [ ] **Step 3: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add App.tsx src/navigation/RootNavigator.tsx
git commit -m "feat: 인증 상태 기반 네비게이션 조립"
```

- [ ] **Step 5: 전체 수동 동작 확인 (시뮬레이터/Expo Go)**

1. 백엔드 로컬 실행 확인 (`./gradlew bootRun`, 8080 포트)
2. `EXPO_PUBLIC_API_BASE_URL`이 시뮬레이터/기기에서 백엔드에 실제로
   접근 가능한 주소인지 확인 (iOS 시뮬레이터는 `localhost` 그대로
   가능, 실 기기는 같은 네트워크의 백엔드 머신 IP로 바꿔야 함)
3. `npx expo start` → 시뮬레이터에서 앱 실행
4. 로그인 화면에서 "카카오로 시작하기" 클릭 → 인앱 브라우저로 카카오
   로그인 → 로그인 성공 후 앱으로 자동 복귀하며 홈 화면으로 전환되는지
   확인
5. URL 입력 후 "장소 추출하기" → 저장한 장소 목록으로 이동 확인
6. 목록에서 장소 클릭 → 상세 화면 → "지도에서 보기" → 카카오맵에
   핀이 정상적으로 찍히는지 확인
7. 앱을 완전히 종료 후 재실행 — 로그인 상태가 유지되는지(SecureStore
   토큰 영속) 확인
8. (가능하면) Android 에뮬레이터에서도 1~7 반복 — 특히 헤더 정렬,
   키보드 동작, 폰트가 iOS와 동일하게 보이는지 비교

**Expected:** 1~8 모두 정상 동작. 문제가 있으면 systematic-debugging
스킬로 근본 원인부터 조사할 것 (특히 딥링크 콜백이 안 오는 경우
`app.json`의 scheme과 백엔드
`app.mobile-redirect-scheme`이 정확히 일치하는지부터 확인).
