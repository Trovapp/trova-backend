# API 요청마다 서버 세션이 생기던 문제 실측 (2026-09-29, #45)

## 측정 방법
- 로컬 서버(Spring Boot, macOS, 같은 Supabase DB)를 `ACTUATOR_EXPOSE=health,metrics`로 실행
- 세션 수: `GET /actuator/metrics/tomcat.sessions.active.current`
- 부하: `GET /api/trips` 1,000회(동시 8개)
  - 비로그인(쿠키·토큰 없음) 500회 → 401
  - JWT(`Authorization: Bearer`) 500회 → 200, 데이터 없는 계정이라 읽기 전용
- 수정 후는 같은 스크립트를 8090 포트의 수정본 JAR에 실행하고, 응답의 `Set-Cookie` 개수도 셌다

## 결과

| | 수정 전 | 수정 후 |
|---|---|---|
| 요청 1,000회 후 활성 세션 | 1,000개 | 0개 |
| `Set-Cookie`(JSESSIONID) 받은 응답 | 매 요청(표본 7회 모두) | 0 / 1,000 |

- 수정 후 OAuth 로그인 시작(`/oauth2/authorization/google`)은 여전히 세션을 만든다(302 + JSESSIONID) — 로그인 흐름에 필요한 세션이라 의도된 동작
- 세션 타임아웃은 기본 30분이라, 수정 전에는 요청 수만큼 세션이 30분씩 메모리에 남았다
- (추정, 실측 아님) 부하테스트 처리량 초당 약 250건이 30분 이어지면 약 45만 개. 세션당 1~2KB로 잡으면 수백 MB — E2.1.Micro 메모리는 1GB

## 원인과 수정
Spring Security 기본 동작 두 가지가 원인이었다(TRACE 로그로 확인).
- 비로그인 요청: 401을 줄 때 `HttpSessionRequestCache`가 "로그인 후 돌아갈 요청"을 세션에 저장
  → `NullRequestCache`로 교체. 로그인 성공 처리기는 저장된 요청을 쓰지 않고 항상 프론트로 보낸다.
- JWT 요청: `SessionManagementFilter`가 "이번 요청에서 인증됨"으로 보고 `HttpSessionSecurityContextRepository`에 저장
  → `JwtSkippingSecurityContextRepository`: `JwtAuthenticationToken`만 요청 속성에만 저장하고, 그 외(웹 OAuth2 세션 로그인)는 기본값(요청 속성 + 세션) 그대로.

웹 프론트는 세션 쿠키(`credentials: "include"`)로 인증하므로 세션 자체를 끄지 않았다.
회귀 방지: `ApiSessionCreationIntegrationTest` — JWT·비로그인 요청 후 세션 없음, 기존 세션 쿠키 인증은 200(수정 전 앞의 두 테스트 실패).
