# Trova 공유 주소 이전

운영 반영 전 검토안이다. 2026-11-23에 두 저장소의 main(백엔드 `c6db6dc`, 앱 `d8b82b6`), 운영 Caddyfile, 서비스가 읽는 환경 파일의 관련 키 이름을 확인했다. 새 도메인의 DNS A 레코드는 `193.123.161.217`이다.

| 주소 | 현재 역할 | 적용 뒤 |
| --- | --- | --- |
| `app.193-123-161-217.sslip.io` | 웹 화면, 공유 링크, Android 앱 연결 검증 | 웹 요청은 같은 경로와 쿼리로 301 이동. 기존 폰의 앱 연결 검증 파일은 직접 200 응답 |
| `193-123-161-217.sslip.io` | API, Google·Kakao 로그인 시작·복귀, 실시간 연결, 상태 확인 | 첫 화면과 공유 경로에 들어온 GET·HEAD만 301 이동. API·로그인·상태 확인은 기존 서버에서 응답 |
| `trova.duckdns.org` | 같은 Oracle IP로 DNS 연결 | Caddy가 자동 인증서를 받고 기존 웹 파일을 제공. 새 공유 링크와 웹 로그인 완료 화면의 기준 주소 |
| `app.trovapp.workers.dev` | 더 오래된 웹 주소 | 기존 중계로도 최종 새 웹에 도달. 앱 PR의 Cloudflare 설정을 추후 적용하면 중간 경유 한 번 감소 |

## 옛 주소 사용처

| 위치 | 확인 결과 |
| --- | --- |
| 백엔드 `TripShareLinkService`, `WebAppLoginService` | `APP_WEB_APP_URL`로 공유 링크와 로그인 성공·실패 후의 웹 주소를 만든다. 공유 데이터에는 토큰이 저장되어 기존 링크 재발급이나 DB 변경이 필요 없다. |
| 백엔드 `SecurityConfig`, `application.yml`, `.env.example` | 현재 PWA 주소를 한 곳만 허용한다. 이전 기간에는 `APP_WEB_APP_LEGACY_ORIGIN` 한 곳을 추가로 허용한다. 두 주소에 같은 일반 API·쿠키 없는 호출 규칙이 적용된다. 관리자 웹과 쿠키용 프런트의 설정은 별도다. |
| 백엔드 OAuth·SSE | API 호스트의 `/oauth2/authorization/{google,kakao}`에서 시작해 `/login/oauth2/code/{google,kakao}`로 복귀한다. 마지막에 웹은 새 웹의 `/auth/callback/`, 모바일은 `trova://auth`로 간다. `TripEventsController`·`EventTickets`의 1회용 티켓은 여행·사용자에 묶이고 60초 동안 유효하다. 발급·연결 모두 API 호스트를 쓴다. |
| 앱 `TripShareSheet`, `TripDetailScreen`, `src/lib/api/trips.ts`, `systemShare.ts` | 서버가 준 링크를 복사하거나 운영체제의 공유 창에 전달한다. 앱에서 토큰 주소를 다시 만들지 않는다. |
| 앱 `src/navigation/linking.ts`, `RootNavigator.tsx`, `SharedTripScreen.tsx` | 설치 앱이 받는 공유 경로와 설치 안내 기본 주소. JS 변경으로 새·옛 링크를 읽고 새 설치 페이지로 간다. 웹 주소 해석은 경로 기반이다. |
| 앱 `app.json`, `app.config.ts`, `public/.well-known/assetlinks.json` | Android는 옛 `app.` 주소의 `/t/`만 등록돼 있다. iOS에는 `associatedDomains`와 Apple 사이트 연결 파일이 없다. 웹 기본 호스트는 두 앱 설정 파일에 따로 없다. |
| 앱 웹 배포 | `scripts/deploy-web-oracle.sh`의 공개 주소와 `deploy/cloudflare-redirect/{_redirects,index.html}`의 목적지를 새 웹으로 준비했다. 서버 IP·정적 파일 위치와 `EXPO_PUBLIC_API_BASE_URL`은 API용이다. `scripts/web-csp.mjs`는 빌드 때 API 주소를 웹 보안 헤더에 포함한다. |
| iPhone 단축어 | `scripts/build-shortcut.py`는 빌드 때 지정한 백엔드 주소로 여행 영상을 보낼 단축어를 만든다. `src/lib/web/shortcut.ts`는 현재 웹의 상대 경로로 서명된 파일을 내려받는다. API 호스트를 유지하므로 기존 단축어도 계속 쓸 수 있다. |
| 앱 점검·기록 | `scripts/trip-copy.test.mjs`는 두 웹 주소와 스토어 설정을 검사한다. `scripts/{check-pwa-startup,measure-pwa-home,measure-pwa-login}.cjs`는 Cloudflare를 가상 웹 출처로 사용하고 bare SSLIP API를 모의 처리한다. `scripts/update-bundle-check{,.test}.mjs`는 운영 API 주소를 검사하고 `qa-149-151-152.test.mjs`에는 DNS 오류 예시가 있다. `docs/pwa.md`는 운영 절차이고, `docs/experience-notes.md`, `docs/qa/2026-11-22-ios-scroll-fix`, `docs/screenshots/09-simplicity-v2`, `docs/web-deploy-2026-11-{22d,23,23b}.md`는 과거 기록이다. |
| 백엔드 점검·기록 | `monitoring/{health-smoke.js,places-load.js,limit/prod_run.py}`, `docs/benchmarks/scripts/{stress-test,spike-test}.js`, `docs/runbooks/high-http-5xx.md`의 대상은 API다. 과거 기록은 `docs/adr/0044*`, `docs/experience-notes.md`, `docs/benchmarks/2026-09-21*`, `docs/monitoring/2026-11-{04,08}*`, `docs/superpowers/plans/2026-10-14-android-app.md`에 있다. 접속 설명·모니터링에 쓰이는 숫자 IP도 그대로 사용한다. |

운영 서비스는 `/opt/trova-backend/.env`를 읽는다. 관련 기존 키 이름은 `APP_WEB_APP_URL`, `FRONTEND_URL`, `APP_ADMIN_WEB_URL`, `MOBILE_REDIRECT_SCHEME`, `GOOGLE_CLIENT_ID`, `KAKAO_CLIENT_ID`다. 이번 PR이 추가하는 키는 `APP_WEB_APP_LEGACY_ORIGIN`이다. 앱의 빌드 입력은 `EXPO_PUBLIC_API_BASE_URL`과 지도용 `EXPO_PUBLIC_KAKAO_MAP_JS_KEY`다.

정확한 파일 목록은 해당 저장소에서 기준 커밋으로 다시 뽑을 수 있다. 환경 파일 내용은 출력하지 않는다.

```sh
git grep -IlE 'sslip[.]io|193[.-]123[.-]161[.-]217' c6db6dc    # 백엔드
git grep -IlE 'sslip[.]io|193[.-]123[.-]161[.-]217' d8b82b6    # 앱
```

## 사용자가 먼저 할 일

- 카카오 디벨로퍼스에서 Trova 앱 → 앱 → 플랫폼 키 → 웹 지도에서 사용하는 JavaScript 키 → JavaScript SDK 도메인을 연다. `https://trova.duckdns.org`를 경로 없이 한 줄 추가하고 기존 항목도 둔다. 새 웹에서 일정의 지도가 뜨는지 확인한다. [카카오맵 설정](https://developers.kakao.com/docs/ko/kakaomap/common) · [등록 화면 안내](https://developers.kakao.com/docs/ko/app-setting/app)
- Google OAuth와 카카오 로그인 콘솔에 추가 입력할 항목은 없다. 앱은 웹과 모바일 모두 API를 통해 로그인하므로 공급자에게 보내는 복귀 주소는 계속 기존 API 호스트다. 새 웹의 `/auth/callback/`는 Trova가 로그인 완료 후 보내는 마지막 화면이다.
- 기존 웹 사용자는 새 주소에서 한 번 다시 로그인한다. 홈 화면에 설치한 웹앱과 웹 푸시 권한·구독도 주소별로 관리되므로 필요한 기기에서 다시 추가하고 알림을 켠다.

## 승인 후 적용 순서

**백업과 전제.** 아래 서버 명령은 승인된 작업 시간에 같은 셸에서 실행한다. 먼저 두 PR의 승인된 버전과 기존 앱의 정상 production 업데이트 그룹 ID·런타임을 기록한다. 진행 중 분석 작업이 없는 시간에 백엔드를 재시작한다. [최종 Caddy 설정안](../../deploy/caddy/Caddyfile.share-domain)을 서버 `/tmp/Caddyfile.share-domain`에 준비한다. 조사 때 Caddyfile의 SHA-256은 `e9c842a11f39984621d720bdd8a55e2aeb59e3d717094820b0cad16dca09b1d7`였다. 운영 파일이 바뀌었으면 새 변경분을 먼저 설정안에 반영한다.

```sh
set -eu
backup="/var/backups/trova-domain-$(date +%Y%m%d-%H%M%S)"
sudo install -d -m 700 "$backup"
sudo cp -p /etc/caddy/Caddyfile "$backup/Caddyfile"
sudo cp -p /opt/trova-backend/.env "$backup/backend.env"
sudo cp -p /opt/trova-backend/app.jar "$backup/app.jar"
sudo cp -a /srv/trova-app/dist /srv/trova-app/headers.caddy "$backup/"
sudo sha256sum /etc/caddy/Caddyfile
```

1. **Caddy에서 새 웹을 먼저 연다.** 옛 웹도 계속 서비스하는 중간 설정을 만든다. Caddy는 새 호스트의 인증서를 자동으로 준비한다. 새 주소에서 HTTPS 오류 없이 첫 화면·공유 경로·Android 검증 JSON이 열릴 때까지 301을 보류한다.

   ```sh
   sudo sed 's/^app\.193-123-161-217\.sslip\.io {/app.193-123-161-217.sslip.io, trova.duckdns.org {/' "$backup/Caddyfile" | sudo tee "$backup/Caddyfile.dual" >/dev/null
   sudo caddy validate --config "$backup/Caddyfile.dual" --adapter caddyfile
   sudo install -m 644 "$backup/Caddyfile.dual" /etc/caddy/Caddyfile
   sudo systemctl reload caddy
   curl -fsS -o /dev/null https://trova.duckdns.org/t/domain-check
   curl -fsS https://trova.duckdns.org/.well-known/assetlinks.json | jq -e '.[0].target.package_name == "com.trovapp.trova"'
   ```

2. **서버 환경을 준비한다.** `sudoedit /opt/trova-backend/.env`에서 `APP_WEB_APP_URL`에는 위 표의 새 웹 HTTPS 주소, `APP_WEB_APP_LEGACY_ORIGIN`에는 `app.`이 붙은 옛 웹 HTTPS 주소를 넣는다. 각각 한 곳씩, 끝 슬래시·경로 없이 입력한다. API 주소, 관리자 설정, OAuth 키에는 입력할 것이 없다. 편집기 밖으로 운영 파일 내용을 복사하지 않는다.
3. **새 백엔드를 한 번 재시작한다.** 옛 웹을 허용하는 코드는 이번 PR에 들어 있다. 승인된 커밋에서 기존 devflow 백엔드 배포 절차로 새 JAR 교체와 재시작을 한 번에 진행한다. 이미 이 JAR를 올린 경우만 `sudo systemctl restart trova-backend`를 쓴다. 기존 운영 기록은 상태 검사 `UP`까지 140~150초였다. 약 2~3분 동안 로그인·저장·실시간 연결이 끊길 수 있다. 열린 SSE 연결은 새 티켓으로 다시 연결한다. 두 주소에서 로그인과 여행 조회가 되는지 확인한다.

   ```sh
   api=https://193-123-161-217.sslip.io
   curl -fsS "$api/actuator/health" | jq -e '.status == "UP"'
   for origin in https://trova.duckdns.org https://app.193-123-161-217.sslip.io; do
     curl -sSi -X OPTIONS "$api/api/trips" -H "Origin: $origin" -H 'Access-Control-Request-Method: GET' | head -20
   done
   ```

   두 응답은 각각 요청한 출처를 허용해야 한다. 새 웹에서 Google·카카오 로그인을 각각 마치고 본인 여행에서 링크를 만들어 비로그인 창으로 열어 본다. 기존 공유 링크와 같은 여행인지, 여행 변경이 다른 창에 반영되는지도 본다. 실제 공유 토큰·로그인 코드·SSE 티켓은 보고서에 붙이지 않는다.

4. **웹을 배포하고 옛 링크를 넘긴다.** 승인된 앱 소스의 `scripts/deploy-web-oracle.sh`를 기존 빌드 환경에서 실행한다. 웹 배포는 정적 파일과 보안 헤더를 함께 올린다. 새 주소에서 웹 열기, 공유 담기·참여, 카카오 지도, 설치 안내, 압축 JS가 정상인지 확인한다. 이어 아래 최종 설정을 적용한다. Caddy 재적용은 서비스 재시작을 요구하지 않는다. 301은 브라우저가 기억할 수 있으므로 두 웹의 기능 점검이 끝난 뒤에 켠다.

   ```sh
   sudo caddy validate --config /tmp/Caddyfile.share-domain --adapter caddyfile
   sudo install -m 644 /tmp/Caddyfile.share-domain /etc/caddy/Caddyfile
   sudo systemctl reload caddy
   curl -sSI 'https://app.193-123-161-217.sslip.io/t/domain-check?copy=1'
   curl -sSI 'https://193-123-161-217.sslip.io/t/domain-check?copy=1'
   curl -fsS https://app.193-123-161-217.sslip.io/.well-known/assetlinks.json | jq -e '.[0].target.package_name == "com.trovapp.trova"'
   ```

   두 공유 요청의 기대값은 `301` 및 `Location: https://trova.duckdns.org/t/domain-check?copy=1`이다. 옛 API의 상태 확인도 다시 한다. `domain-check`는 HTTP 경로 점검용이므로 실제 여행 내용은 앞 단계의 유효한 링크로 확인한다.
5. **앱 무선 업데이트를 마지막에 보낸다.** 앱 레포의 `scripts/publish-update.sh production "공유 웹 주소 이전"`으로 기존 Android 런타임에 JS를 보낸다. 설치 앱을 다시 열어 링크 복사·공유, 옛 링크로 앱 열기, 새 링크로 웹 열기를 실제 기기에서 확인한다. 기존 사용자에게 다음 실행 때 순차 적용된다. 스토어 빌드로 새 호스트가 등록될 때까지 새 HTTPS 링크는 브라우저에서 볼 수 있다. 옛 출처에서 API를 쓰는 기기가 사라진 뒤에만 `APP_WEB_APP_LEGACY_ORIGIN`을 비운다.

## 되돌리기

- **사용자에게 새 링크가 전달되기 전**에는 기존 Caddyfile·환경 파일·JAR를 그대로 복원할 수 있다. **새 링크 발급이나 301 이후**에는 이미 전달한 새 링크와 브라우저에 기억된 이동을 위해 양쪽 주소가 웹을 직접 여는 중간 설정으로 먼저 돌아간다. 서로를 향한 301을 만들면 브라우저가 반복 이동한다.
- 아래는 서버에서 앞서 기록한 백업 디렉터리를 `backup`으로 지정한 뒤 실행한다. 웹 파일 복구가 필요하면 디렉터리와 보안 헤더를 같은 버전으로 돌리고, 설치할 Caddyfile을 검증한 뒤 다시 읽는다.

```sh
sudo rsync -a --delete "$backup/dist/" /srv/trova-app/dist/
sudo cp -p "$backup/headers.caddy" /srv/trova-app/headers.caddy
sudo caddy validate --config "$backup/Caddyfile.dual" --adapter caddyfile
sudo install -m 644 "$backup/Caddyfile.dual" /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

- **새 링크 배포 후의 기본 복구**는 이번 백엔드를 두고 `sudoedit /opt/trova-backend/.env`에서 기본 웹을 옛 웹으로, 임시 허용 출처를 새 웹으로 맞춘 뒤 `sudo systemctl restart trova-backend`를 실행한다. 그러면 기존·새 링크 모두 웹과 API를 쓸 수 있다. API 상태, 두 웹의 로그인·공유를 다시 확인한다.
- **새 링크 공개 전 전면 복구**가 필요하면 아래 명령으로 원본을 복원한다. 공개 후 옛 JAR까지 내려야 하면 한 출처만 허용했던 버전이므로 두 주소의 브라우저 API 사용을 함께 보장할 수 없다. 이 경우 두 출처 지원 수정본을 우선 복구한다.

```sh
sudo caddy validate --config "$backup/Caddyfile" --adapter caddyfile
sudo install -m 644 "$backup/Caddyfile" /etc/caddy/Caddyfile
sudo systemctl reload caddy
sudo cp -p "$backup/backend.env" /opt/trova-backend/.env
sudo cp -p "$backup/app.jar" /opt/trova-backend/app.jar
sudo systemctl restart trova-backend
```

- **무선 업데이트 복구**는 같은 production 브랜치·런타임의 직전 정상 업데이트 그룹 ID를 `previous_group`에 지정하고 앱 작업 디렉터리에서 `npx eas-cli update:republish --group "$previous_group" --destination-channel production --platform android`를 실행한다. 기기가 업데이트를 받고 앱을 다시 열면 적용된다. 웹·앱에는 별도의 계획 중단 시간이 없고, 파일 복사 중에는 열려 있던 탭에서 한 번 새로고침이 필요할 수 있다. [Expo 업데이트 복구 명령](https://docs.expo.dev/eas/cli/#eas-updaterepublish)
- **스토어 후속 작업**은 Android `app.json`의 기존 호스트와 새 호스트를 함께 등록하고 Play 새 빌드를 배포하는 일이다. 각 호스트의 `/.well-known/assetlinks.json`은 이동 없이 JSON 200이어야 한다. iOS에서 HTTPS 링크로 네이티브 앱을 직접 열려면 `applinks:trova.duckdns.org` 권한과 `apple-app-site-association` 파일을 추가하고 새 iOS 빌드를 배포한다. iPhone에서 웹/PWA로 열어 쓰는 데에는 스토어 작업이 필요 없다. [Android 앱 링크 검증](https://developer.android.com/training/app-links/configure-assetlinks)
