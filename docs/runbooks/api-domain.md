# API와 소셜 로그인 주소 전환

[공유 웹 이전 기록](share-web-domain.md)에 이어 새 API 호스트만 추가한다. 매니저가 두 PR을 검토하고 사용자 승인을 받은 뒤 적용한다. 기존 Duck DNS와 Caddy 자동 HTTPS, 동일한 서버를 사용해 추가 인프라 비용은 0원이다.

| 사용처 | 전환 내용 |
| --- | --- |
| Spring OAuth | Caddy의 HTTPS와 호스트를 내부 Tomcat이 읽는다. Google 기본 템플릿과 Kakao의 `{baseUrl}/login/oauth2/code/{registrationId}`가 시작한 호스트로 돌아와 같은 세션 쿠키를 받는다. 백엔드 포트는 계속 프록시에서만 접근 가능해야 한다. |
| CORS·로그인 완료 | 허용 대상은 요청을 보내는 웹 출처다. `APP_WEB_APP_URL`, `APP_WEB_APP_LEGACY_ORIGIN`, `FRONTEND_URL`, `APP_ADMIN_WEB_URL`, `MOBILE_REDIRECT_SCHEME`을 유지한다. 웹 성공·실패는 웹의 /auth/callback/로, 모바일은 앱으로 돌아간다. 기존 공급자 키는 `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `KAKAO_CLIENT_ID` 등이며 새 환경 키는 없다. |
| 관리자 웹 | 별도 trova-frontend/admin-web의 `NEXT_PUBLIC_API_BASE_URL`이 API 대상이다. 기존 빌드는 옛 API로 계속 로그인한다. 추후 새 주소로 빌드할 때 관리자 CSP와 관리자 로그인도 확인한다. |
| 공유·실시간 | 공유 페이지는 웹 주소, 데이터 조회와 /api/shares는 API 주소다. 여행 이벤트 티켓 발급과 SSE 구독은 앱의 같은 API 기준 주소를 쓴다. |
| Expo·웹 CSP | src/lib/api/config.ts의 `EXPO_PUBLIC_API_BASE_URL`을 빌드 시 JS에 넣는다. 웹은 운영 API 두 출처를 connect-src에 허용한다. 앱 링크용 app. 호스트는 웹 주소다. |
| 단축어 | 새로 만든 단축어는 새 API에 POST한다. 기존 설치본과 저장소의 서명본은 옛 API를 계속 사용한다. 새 서명본은 앱 docs/pwa.md에 따라 macOS에서 서명한 뒤 웹 파일에 포함한다. |
| 테스트·측정 | 앱 update-bundle-check와 PWA 시작·홈·로그인 측정은 새 주소를 사용한다. 백엔드 monitoring/health-smoke.js, places-load.js, limit/prod_run.py 및 과거 벤치마크는 옛 주소로도 실행 가능하다. |
| Caddy | [추가할 블록](../../deploy/caddy/Caddyfile.api-domain)은 기존 localhost:8080과 관리 엔드포인트 차단 규칙을 사용한다. 기존 API 블록과 공유 웹 전용 경로 규칙은 그대로 둔다. |

## 승인 후 적용

1. Google 웹 OAuth 클라이언트에 `https://api.trova.duckdns.org/login/oauth2/code/google`, 카카오 로그인 Redirect URI에 `https://api.trova.duckdns.org/login/oauth2/code/kakao`를 추가한다. 기존 URI도 두 콘솔에 남겨 둔다.
2. 새 블록을 서버의 /tmp/Caddyfile.api-domain에 준비한다. 현재 API 블록의 업스트림과 접근 제한이 설정안과 일치하는지 확인한다. 같은 셸에서 아래 명령을 실행하고 두 호스트의 /actuator/health 응답과 보호된 /actuator/env의 404를 확인한다.

```sh
set -eu
backup=$(sudo mktemp -d /etc/caddy/api-domain.XXXXXX)
sudo cp -p /etc/caddy/Caddyfile "$backup/original"
sudo cp -p "$backup/original" "$backup/candidate"
printf '\n' | sudo tee -a "$backup/candidate" >/dev/null
sudo tee -a "$backup/candidate" </tmp/Caddyfile.api-domain >/dev/null
sudo caddy validate --config "$backup/candidate" --adapter caddyfile
sudo install -m 644 "$backup/candidate" /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

3. 백엔드 PR의 JAR를 기존 배포 절차로 배포한다. 두 호스트 각각에서 /oauth2/authorization/google과 /oauth2/authorization/kakao의 302에 담긴 redirect_uri가 시작한 호스트의 HTTPS 콜백인지 확인한다.
4. EAS production 환경과 웹 빌드의 EXPO_PUBLIC_API_BASE_URL을 새 주소로 지정한다. 기존 정상 업데이트 그룹과 웹 산출물을 보관한다. 기존 production 채널과 런타임이 맞는 Android 기기에 scripts/publish-update.sh로 OTA하고 웹 번들과 보안 헤더를 함께 배포한다. 웹과 단축어는 OTA와 별도다.
5. 신규 앱·웹에서 Google·Kakao 로그인 완료, 공유 링크 열기와 가져오기, 두 기기에서 같이 고치기 실시간 반영, iPhone 단축어 저장을 확인한다. 업데이트 전 v10과 기존 단축어에서도 옛 주소로 같은 기능이 동작하는지 확인한다.

## 되돌리기

새 주소를 쓰는 앱이 배포되면 두 API 호스트와 양쪽 콘솔 URI를 유지한 채 직전 OTA 그룹을 같은 런타임에 재게시하고 웹 빌드·헤더를 함께 복원한다. 필요하면 직전 백엔드 JAR를 복원한다. 새 주소를 사용하는 클라이언트가 배포되기 전에만 아래 명령으로 Caddy 설정을 되돌린다. backup은 위에서 만든 디렉터리를 사용하며 중간에 다른 설정 변경이 있으면 추가한 블록만 제거해 검증한다.

```sh
sudo caddy validate --config "$backup/original" --adapter caddyfile
sudo install -m 644 "$backup/original" /etc/caddy/Caddyfile
sudo systemctl reload caddy
```
