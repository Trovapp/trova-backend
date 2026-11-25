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

## 2026-11-24 운영 적용 결과

시간은 한국 표준시(KST)다.

| 시각 | 운영 결과 |
| --- | --- |
| 16:16:52 | Caddyfile을 백업하고 기존 API 블록을 호스트명만 치환해 추가했다. 원래 블록의 웹 경로 처리, actuator 차단, 업스트림을 그대로 복제했다. 검증과 reload 후 새 주소의 Let's Encrypt 인증서와 SAN을 확인했다. 두 호스트에서 헬스는 UP, 보호된 `/actuator/env`는 404, 같은 유효한 공유 링크의 공개 API는 200이었다. |
| 16:17:28 | 백엔드 [#520](https://github.com/Trovapp/trova-backend/pull/520)을 squash 머지했다. 운영 JAR는 `fcf2195`에서 빌드했고 전송 전후 SHA-256은 `a2f4cfa5b1d30c59fe7e8d9964cbcbcbc82853a02dba7e35d29079a14fc993ab`으로 일치했다. |
| 16:18:43–16:21:48 | 전송 전과 교체 직전 모두 진행 중 분석·초안 작업 0건이었다. 재시작 요청부터 서버 내부 actuator UP 및 공개 HTTPS UP까지 **185초**가 걸렸다. systemd는 active였고 중간에 공개 헬스의 502를 관측했다. |
| 16:22 | 서버에서 Google·카카오 인증 시작 응답 네 건의 Location을 검사했다. 공급자 로그인 화면으로 이동하지 않았다. |
| 16:22:35–16:23:20 | 앱 [#258](https://github.com/Trovapp/trova-app/pull/258)을 `94ab71c`로 squash 머지하고 EAS production의 `EXPO_PUBLIC_API_BASE_URL`을 `https://api.trova.duckdns.org`로 바꿨다. 지문을 확인한 뒤 Android production에 **API와 소셜 로그인 주소 전환**을 게시했다. |
| 16:23:43–16:25:07 | 웹 정적 파일과 헤더를 백업했다. 첫 전송 때 제한된 파일 권한 때문에 Caddy의 헤더 읽기와 reload가 실패했다. 첫 복사 시작 16:23:53부터 권한 복구 후 첫 화면 200 확인 16:24:34까지 41초가 웹 접속 영향 가능 구간이다. 개별 요청의 실제 실패 시간은 측정하지 못했다. 배포 스크립트를 정상 권한으로 다시 실행했고 첫 화면·Brotli 번들·SPA 경로가 모두 200이었다. |
| 16:25:22–16:25:24 | 최종 Caddyfile이 검증된 후보와 바이트 단위로 같았다. Caddy와 백엔드는 active였고 내부 헬스는 UP이었다. 웹의 실제 공유 경로와 두 호스트의 공개 공유 API는 200이었다. 웹 응답 CSP의 connect-src에 두 API 주소가 있었다. 두 호스트에서 인증 없는 공유 제출은 401이었고, OAuth 네 건도 다시 확인했다. |

| 시작 호스트 | Google 302의 redirect_uri | Kakao 302의 redirect_uri |
| --- | --- | --- |
| `api.trova.duckdns.org` | `https://api.trova.duckdns.org/login/oauth2/code/google` | `https://api.trova.duckdns.org/login/oauth2/code/kakao` |
| `193-123-161-217.sslip.io` | `https://193-123-161-217.sslip.io/login/oauth2/code/google` | `https://193-123-161-217.sslip.io/login/oauth2/code/kakao` |

Android v11의 게시 전 계산값과 EAS 게시 결과의 런타임은 모두 `30389d07d2e20855b4e5082cf6ac8310e2e3fde8`이다. 새 그룹은 `56c01a96-b47e-4ee1-a761-5d812b30af62`다. v10의 최근 그룹은 배포 전후 모두 `9c0fc37e-61ad-42fd-9eb1-48b3d138a588`이었다.

### 이번 릴리스의 복구 지점

| 대상 | 백업과 복구 방법 |
| --- | --- |
| Caddy | 원본은 `/etc/caddy/api-domain.OTmDD6/original`이다. 새 주소를 쓰는 앱이 이미 배포되어 있으므로 클라이언트 복구 중에는 두 API 호스트와 등록된 콜백을 유지한다. |
| 백엔드 | 필요하면 `sudo cp -p /opt/trova-backend/app.jar.bak-20261124-161833 /opt/trova-backend/app.jar && sudo systemctl restart trova-backend`로 복원한다. 서버 내부 헬스 UP, systemd active, 양쪽 콜백을 다시 확인한다. |
| Android | 앱 작업 디렉터리에서 `npx eas-cli update:republish --group 269e1f24-a813-4945-bed4-3d2ff3085fe9 --destination-channel production --platform android`로 직전 v11 그룹을 재게시한다. 이후 빌드도 되돌릴 경우 production의 API 주소 변수를 종전 값인 `https://193-123-161-217.sslip.io`로 복원한다. |
| 웹 | `/srv/trova-app/backup-before-94ab71c-I4vjeoJ3` 안의 `dist/`와 `headers.caddy`를 함께 복원하고 Caddy 계정의 읽기 권한을 확인한다. Caddyfile을 검증한 뒤 reload하고 첫 화면·공유 링크·공개 API를 다시 확인한다. |

Mac 전체 백엔드 테스트 재실행 결과는 1,465건 통과, 6건 건너뜀, 1건 실패였다. pgvector 대안 추천 테스트의 SQL 횟수가 기대 9회, 실제 7회였으며 #520 머지 직전 커밋에서도 같은 실패를 재현했다. 앱 CSP 및 OTA 번들 검사 테스트 13건은 통과했다.

실기기 후속 확인은 Android v11에서 OTA 수신 후 Google·카카오 로그인 완료와 앱 복귀, 웹/PWA에서 두 공급자의 로그인 완료 및 세션 유지, 기존 v10 로그인과 기존 iPhone 단축어 저장이다. 실제 공유 링크 열기·가져오기와 두 기기 사이 공동 편집 반영도 기기에서 확인한다.
