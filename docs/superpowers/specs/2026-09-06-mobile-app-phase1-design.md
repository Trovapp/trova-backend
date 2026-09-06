# React Native 모바일 앱 1단계(기반 구조) 설계

날짜: 2026-09-06
상태: 승인됨 (브레인스토밍 완료)

## 배경

Trova는 지금까지 백엔드(Spring Boot API, `trova-backend`)와 웹
프론트엔드(Next.js, `trova-frontend`) 두 레포로 구성돼 있었다. 이번에
포트폴리오 가치와 실제 사용 편의성(SNS 앱에서 "공유하기"로 바로
링크를 넘기는 흐름)을 위해 React Native(Expo) 기반 모바일 앱을 추가로
만들기로 했다.

전체 기능(공유 처리, 저장한 장소, 내 여행/일정, 추천/찜, 마이페이지)을
한 번에 옮기기엔 스코프가 커서 단계별로 나눈다:

- **1단계 (이 스펙)**: 프로젝트 기반 구조, 인증, 공유 처리, 저장한 장소
  목록/상세/지도
- 2단계: 내 여행/일정 관리 (별도 스펙)
- 3단계: 추천/찜/마이페이지 (별도 스펙)

## 범위

이 스펙은 1단계만 다룬다. 백엔드 변경은 "기존 웹 로그인 흐름을 전혀
건드리지 않고, 모바일 전용 인증 경로를 추가하는" 것으로 한정한다.

**비목표 (이번 단계에서 하지 않음)**:
- JWT 리프레시 토큰 (v1은 장기 만료 토큰 하나로 단순화)
- 오프라인 캐싱/동기화
- 푸시 알림
- 네이티브 공유 확장(iOS Share Extension/Android Intent Filter) — 앱
  안에 링크를 붙여넣는 방식으로 먼저 검증하고, 확장은 이후 단계 후보

## 결정 사항

브레인스토밍 과정에서 확정한 것:

- **범위**: 웹앱 전체 기능을 결국 다 옮기되, 1단계는 핵심 루프(로그인
  → 공유 처리 → 저장한 장소 지도)까지만
- **인증**: 백엔드에 JWT 발급을 추가. 기존 세션 쿠키 로그인(웹)은
  그대로 두고, 모바일 요청만 구분해 JWT를 내려준다
- **지도**: 카카오맵 JS SDK를 `react-native-webview`로 감싸서 재사용.
  `react-native-maps`(Google Maps 유료 등록 필요)로 새로 만들지 않는다
- **스택**: Expo(managed workflow) + TypeScript, React Navigation,
  TanStack Query
- **레포 위치**: 새 레포 `trova-app` (기존 백엔드/프론트와 동일하게
  분리된 레포 구조를 유지)

## 아키텍처

### 프로젝트 초기 세팅 (`trova-app`)

- `npx create-expo-app trova-app --template` (TypeScript 템플릿)
- 의존성: `@react-navigation/native` + 관련 패키지,
  `@tanstack/react-query`, `expo-secure-store`, `expo-web-browser`,
  `expo-linking`, `react-native-webview`
- `app.json`에 커스텀 URL 스킴 등록: `"scheme": "trova"` (OAuth 콜백
  딥링크용)
- 폴더 구조: `app/`(화면, Expo Router 사용 시) 또는
  `src/screens`+`src/navigation`(React Navigation 수동 구성) 중
  구현 단계에서 택1 — 이 스펙에서는 화면 목록과 데이터 흐름만 고정하고
  세부 폴더 구조는 계획 단계에서 정한다
- `src/lib/api/` — 백엔드 API 클라이언트 (기존 `trova-frontend`의
  `src/lib/api/*.ts` 파일들과 동일한 함수 시그니처를 최대한 재사용,
  fetch 대신 `Authorization` 헤더를 자동으로 붙이는 공통 래퍼 사용)

### 인증 흐름

**웹(기존, 변경 없음)**: 세션 쿠키 로그인 그대로 유지.

**모바일(신규)**:

1. 앱: "카카오로 로그인" 버튼 → `expo-web-browser`의
   `openAuthSessionAsync`로
   `{BACKEND_URL}/oauth2/authorization/kakao?mobile=true`를 인앱
   브라우저로 염 (구글도 동일 패턴)
2. 백엔드: 새 `MobileLoginFlagFilter`(`OncePerRequestFilter`)가
   `/oauth2/authorization/**` 요청에서 `mobile=true` 쿼리 파라미터를
   보고 `HttpSession`에 `MOBILE_LOGIN=true`를 저장. 이 필터는
   `OAuth2AuthorizationRequestRedirectFilter` 앞에 등록
   (`http.addFilterBefore(...)`)
3. provider 로그인/동의 → 콜백 → 기존 `CustomOAuth2UserService`가
   그대로 User upsert (변경 없음)
4. `OAuth2LoginSuccessHandler` 수정: 세션에 `MOBILE_LOGIN`이 있으면—
   - 새 `JwtService.issue(User)`로 JWT 발급 (클레임: `userId`, 만료
     90일)
   - `{app.mobile-redirect-scheme}://auth?token={jwt}`로 리다이렉트
     (딥링크로 앱이 열림)
   - 세션 속성 제거
   - 없으면 기존 로직(프론트 URL로 리다이렉트) 그대로
5. 앱: 딥링크로 토큰을 받아 `expo-secure-store`에 저장
6. 이후 모든 API 요청: `Authorization: Bearer {jwt}` 헤더 사용

**백엔드 인증 처리 통합 (필요한 리팩터링)**:

지금 8개 컨트롤러(`SharesController`, `NotificationController`,
`TripController`, `BookmarkController`, `AuthController`,
`UsersController`, `RecommendationController`, `PlacesController`)와
`CurrentUserService.resolve(...)`가 파라미터 타입을 구체 클래스
`OAuth2AuthenticationToken`으로 못박아 놨다. JWT 인증은 이 타입이 아닌
별도의 `Authentication` 구현체를 만들 수밖에 없어서, 이 8곳을 모두
`Authentication`(제네릭) 타입으로 바꾸고 `CurrentUserService.resolve`
내부에서 구현체별로 분기해야 한다. 기계적이지만 8개 파일에 걸친
변경이라 이 스펙에 명시해둔다:

- `CurrentUserService.resolve(Authentication authentication)`:
  - `authentication instanceof OAuth2AuthenticationToken oauth2` →
    기존 로직 그대로
  - `authentication instanceof JwtAuthenticationToken jwt` → 새 로직,
    `userRepository.findById(jwt.getUserId())`
- 새 `JwtAuthenticationToken`(`security` 패키지,
  `AbstractAuthenticationToken` 상속) — `Long userId`를 들고 있는
  최소 구현
- 새 `JwtAuthenticationFilter`(`OncePerRequestFilter`) — `Authorization:
  Bearer` 헤더를 읽어 `JwtService.verify(token)`으로 검증하고
  성공하면 `SecurityContextHolder`에 `JwtAuthenticationToken` 설정.
  세션 기반 필터 앞에 등록하되, 헤더가 없으면 그냥 다음 필터로 넘김
  (기존 세션 인증과 공존)
- 새 `JwtService`(`security` 패키지) — `io.jsonwebtoken:jjwt` 사용,
  `issue(User)` / `verify(String) -> Long userId`
- 8개 컨트롤러: `OAuth2AuthenticationToken authentication` →
  `Authentication authentication`으로 파라미터 타입만 변경 (호출부
  `currentUserService.resolve(authentication)`는 그대로)

### 새 의존성 (build.gradle)

```
implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.6'
runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.6'
```

MIT 라이선스, 무료 — 비용 원칙에 저촉 없음.

### 환경변수 / 설정 (백엔드 추가분)

- `JWT_SECRET` — HMAC 서명 키 (최소 256비트, 로컬 개발용은 랜덤
  생성해서 `.env`에 저장)
- `app.mobile-redirect-scheme` (`application.yml`, 기본값
  `trova`) — 딥링크 스킴

### 1단계 화면 (앱)

1. **로그인** — 카카오/구글 로그인 버튼 (웹의 `ProviderLoginButton`과
   동일한 두 옵션)
2. **홈 / 링크 붙여넣기** — URL 입력 후 `POST /api/shares` 호출,
   처리 상태 폴링 (웹의 `/processing/[jobId]`와 동일한 폴링 로직)
3. **저장한 장소 목록** — `GET /api/places` (웹의 `/places`와 동일)
4. **장소 상세** — `GET /api/places/{id}`
5. **지도** — `react-native-webview`에 카카오맵 HTML을 로드하고,
   핀 데이터는 `postMessage`로 웹뷰에 전달 → 웹뷰 내 JS가
   `window.addEventListener('message', ...)`로 받아 마커 렌더링.
   웹의 `KakaoMap.tsx` 로직을 HTML 파일 하나로 이식

### 데이터 계층

- TanStack Query로 API 호출 캐싱/재시도 (웹은 자체 `useEffect` 폴링
  패턴을 쓰지만, 앱에서는 표준 캐싱 라이브러리를 쓰는 게 유지보수와
  포트폴리오 양쪽에 유리)
- 공통 fetch 래퍼(`src/lib/api/client.ts`)가 `expo-secure-store`에서
  토큰을 읽어 모든 요청에 `Authorization` 헤더를 붙임. 401 응답이면
  저장된 토큰을 지우고 로그인 화면으로 리다이렉트

## 에러 처리

- JWT 만료/위조 → `JwtAuthenticationFilter`가 그냥 인증 안 된 상태로
  다음 필터에 넘기고, 결국 `anyRequest().authenticated()`에 걸려 401
  → 앱이 401을 받으면 토큰 삭제 + 로그인 화면 이동
- OAuth 실패(모바일 경로) → 기존 `OAuth2LoginFailureHandler`도
  `MOBILE_LOGIN` 세션 속성을 확인해, 있으면
  `{scheme}://auth?error=oauth_failed`로 리다이렉트 (없으면 기존
  웹 실패 리다이렉트 그대로)
- 딥링크 파싱 실패(토큰 없음 등) → 앱이 에러 토스트 표시 후 로그인
  화면 유지

## 테스트 전략

- 단위(백엔드): `JwtService.issue`/`verify` 왕복 테스트, 만료된
  토큰/위조된 서명 각각 검증 실패 확인
- 단위(백엔드): `CurrentUserService.resolve`가
  `OAuth2AuthenticationToken`/`JwtAuthenticationToken` 각각에서 올바른
  `User`를 찾는지
- 통합(백엔드): `MobileLoginFlagFilter`가 `mobile=true` 파라미터가
  있을 때만 세션 속성을 세팅하는지
- 수동(앱): Expo Go 또는 시뮬레이터로 카카오/구글 로그인 전체 왕복,
  링크 공유 → 폴링 → 지도 표시까지 실제 기기/시뮬레이터에서 확인
  (OAuth 왕복 자체는 자동화 대상에서 제외 — 웹 스펙과 동일한 방침)

## 사용자가 직접 해야 하는 일 (Claude가 대신 할 수 없음)

1. **Kakao/Google OAuth 설정에 모바일 리다이렉트 URI 추가**: 지금
   등록된 `http://localhost:8080/login/oauth2/code/{provider}`는
   그대로 두고 추가 설정은 필요 없음 (OAuth 콜백은 여전히 백엔드로만
   오고, 백엔드가 딥링크로 리다이렉트하는 방식이라 provider 쪽 설정은
   안 바뀜)
2. **새 GitHub 레포(`trova-app`) 생성 여부 결정**: Claude가
   `gh repo create`로 만들지, 직접 만들지 확인 필요 (계획 실행 시
   다시 물어봄)
3. `JWT_SECRET` 값은 Claude가 로컬 개발용으로 생성해서 `.env`에 넣을
   수 있음 — 배포 시에는 별도로 안전하게 재발급 권장
4. Expo 개발 환경(Xcode 시뮬레이터 또는 실제 기기 + Expo Go 앱) 준비

## 커밋 전 확인

백엔드 변경분: CLAUDE.md 규칙대로 `./gradlew build` 실행.
앱: `npx tsc --noEmit` (계획 단계에서 정할 빌드 커맨드로 대체 가능).
