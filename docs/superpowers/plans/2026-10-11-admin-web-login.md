# 관리자 웹 로그인·관리자 전용 토큰 구현 계획 (PR②, #163)

> 설계: [관리자 대시보드 설계](../specs/2026-10-11-admin-dashboard-design.md) "웹 로그인"·"보안" 절, [ADR 0012](../../adr/0012-admin-dashboard-web.md).
> PR①(#162, 집계 API) 브랜치 위에 쌓는다. 화면·Cloudflare Pages는 PR③.

## Global Constraints
- 비용 0원, 새 외부 서비스 없음. 서버 1대라 교환 코드·요청 횟수는 메모리에 둔다(재시작하면 사라짐 — 다시 로그인하면 됨).
- 기존 앱 로그인(`mobile=true` → 딥링크 JWT 90일)과 웹 세션 로그인은 그대로 동작해야 한다.
- 토큰을 주소창에 넣지 않는다(교환 코드만). 기록에는 내부 번호만, 개인정보 없음.
- 코드 스타일: 생성자 주입, record, `var`·와일드카드 import 금지, 한글 테스트 이름.

## 설계에서 정한 세부(이 계획에서 확정)
- 시작 주소의 표시는 `?client=admin-web`(설계 초안의 `client=web`은 일반 웹 로그인과 헷갈려 바꿈). `mobile=true`와 함께 오면 나중 표시가 이긴다(둘 중 하나만 세션에 남김).
- 관리자가 아니면 교환 코드를 아예 만들지 않고 `<관리자 웹>/auth/callback?error=forbidden`으로 보낸다. 로그인 실패는 `?error=oauth_failed`.
- 관리자 웹 로그인이 끝나면 서버 세션을 지운다 — 관리자 웹은 토큰만 쓰고, 세션 쿠키가 남으면 웹 세션 인증 경로가 하나 더 생긴다.
- 관리자 웹 토큰(`aud=admin-web`, 1시간)은 `/api/admin/**`에서만 인증으로 받는다. 다른 API에서는 무시(→ 401). 관리자 API는 이 토큰만 받는다(앱 토큰·웹 세션 → 403).
- `APP_ADMIN_WEB_URL`이 비어 있으면 관리자 웹 로그인은 꺼진다(`client=admin-web`이어도 일반 웹 로그인처럼 처리).
- 교환 실패(없는 코드·만료·재사용)는 400, 교환 사이에 관리자에서 빠졌으면 403.
- 감사 기록 테이블은 2단계 `admin_action_logs`를 지금 만든다: `created_at, actor_user_id, action, target_id, reason, result`. 이번 PR은 `ADMIN_WEB_LOGIN`(성공)·`ADMIN_WEB_LOGIN_DENIED`(관리자 아님)만 남긴다.
- 요청 횟수: 교환 IP당 분당 10회, 관리자 API 관리자당 분당 120회(설정, 감으로 정함·실측 아님) → 429. 1분 고정 창 카운터.

## File Structure
- Create `admin/AdminWebLoginService.java` — 관리자 확인·교환 코드 발급·감사 기록·돌아갈 주소
- Create `admin/AdminExchangeCodeStore.java` — 1회용 코드(32바이트, 60초, 1회)
- Create `admin/RequestRateLimiter.java` — 키별 분당 고정 창 카운터
- Create `entity/AdminActionLog.java`, `repository/AdminActionLogRepository.java`
- Modify `security/JwtService.java` — `issueAdminWeb`, `parse`(사용자 + 관리자 웹 여부)
- Modify `security/JwtAuthenticationToken.java`, `security/JwtAuthenticationFilter.java`
- Modify `security/MobileLoginFlagFilter.java`, `security/OAuth2LoginSuccessHandler.java`, `security/OAuth2LoginFailureHandler.java`
- Modify `admin/AdminGuard.java` — 관리자 웹 토큰만, 횟수 제한
- Modify `controller/AuthController.java` — `POST /api/auth/exchange`
- Modify `config/SecurityConfig.java` — 교환 경로 열기, CORS에 관리자 웹 주소
- Modify `application.yml`, `.env.example` — `APP_ADMIN_WEB_URL`, 횟수 기준
- Tests: 위 각각 + `AdminEndpointsSecurityTest`(앱 토큰·웹 세션 403), 기존 관리자 API 테스트를 관리자 웹 토큰으로

## Tasks (각각 실패하는 테스트 → 구현 → 통과 → 커밋)
1. 관리자 웹 토큰: `JwtService.issueAdminWeb`/`parse`, 필터가 `/api/admin/**`에서만 받기, `AdminGuard`가 이 토큰만 → 기존 관리자 API 테스트를 새 토큰으로 바꾸고 전수 권한 테스트에 앱 토큰·웹 세션 403 추가.
2. 교환 코드 저장소·감사 기록·`AdminWebLoginService`.
3. 로그인 흐름: 필터 표시, 성공·실패 처리, 세션 지우기.
4. `POST /api/auth/exchange` + 보안 설정(경로 열기·CORS).
5. 요청 횟수 제한(교환 IP·관리자 API).
6. 전체 빌드, 로컬 서버(개발 DB)에서 실제 OAuth 없이 확인할 수 있는 것(교환 400·429, 앱 토큰 403, CORS 사전 요청) 확인, 작업 기록, PR(base: PR① 브랜치), reviewflow.

## 확인하지 않는 것(이 PR)
- 실제 Google·카카오 로그인 → 관리자 웹 콜백 왕복은 화면(PR③)이 생긴 뒤 로컬에서 확인.
