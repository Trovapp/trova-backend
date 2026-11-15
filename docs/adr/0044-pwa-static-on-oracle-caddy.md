# 0044 PWA 정적 파일은 Cloudflare 대신 운영 서버(Oracle) Caddy에서 내보낸다 — 0013의 호스팅 부분을 바꿈

- 상태: 확정(사용자 선택 "1" 2026-11-15) · 날짜: 2026-11-15 · 근거: 이슈 #434, 앱 Trovapp/trova-app#146
- 관련: [0013](0013-pwa-before-app-store.md)(PWA 결정 — 호스팅만 바뀌고 나머지는 그대로), [0002](0002-cost-zero-free-tiers.md)(비용 0원), [0015](0015-prod-metrics-via-ssh-tunnel.md)(Caddy가 actuator를 가림), [0037](0037-prod-jvm-memory-and-capacity-decisions.md)(서버 메모리 여유 150~200MiB)

## 풀려는 문제
2026-11-15 00:26 사용자가 아이폰 PWA(와이파이)로 앱을 열었는데 너무 느렸다. 운영 서버는 정상이었다 — 그 시각 앱이 보낸 요청 15건은 서버에서 평균 0.27초·p95 0.56초에 끝났고 오류 0, 재시작 뒤 31시간 동안 1초를 넘긴 요청 4건.
느린 곳은 PWA 정적 파일 호스팅 `app.trovapp.workers.dev`(Cloudflare Workers 정적 자산, 무료)였다. 같은 와이파이의 맥에서 잰 값:

| 항목 | app.trovapp.workers.dev | 비교 |
|---|---|---|
| Cloudflare 접속 지점(cf-ray) | SEA(시애틀) | speed.cloudflare.com은 ICN(서울) |
| 핑 | 230ms, 손실 25% | ICN 5ms, 손실 0 / Oracle 서버 42ms |
| HTML 첫 바이트 | 1.2~2.1초 | GitHub Pages(ICN) 0.03초, Oracle 서버 0.2초 |
| JS 번들(압축 600KB, 풀면 2.4MB) | 4~12초, 한 번은 249초 뒤 연결 끊김 | 1MB 참고 파일(ICN) 0.1초 |

PWA 서비스 워커는 새 버전 반영을 막지 않으려고 아무것도 캐시하지 않는다(0013). 그래서 열 때마다 HTML을 이 경로에서 받고, iOS가 HTTP 캐시를 비운 뒤에는 번들도 다시 받는다.
Cloudflare 무료 플랜이 한국 트래픽을 멀리 보내는 것은 우리가 바꿀 수 없다.

## 후보
1. **운영 서버 Caddy에서 정적 파일 서빙** — 백엔드와 같은 Oracle 서버(서울 경로). 정적 파일이라 서버 메모리 부담은 거의 없고(Caddy RSS 32MiB), 비용 0원 유지. 배포는 rsync + Caddy 재적용.
2. GitHub Pages(trovapp.github.io) — 서울 캐시 0.03초로 가장 빠르지만 SPA 경로(404 → index.html)와 푸시 서비스 워커 경로를 손봐야 하고, 호스팅이 셋으로 갈린다.
3. 서비스 워커에 앱 셸 캐시 — 두 번째 열기부터만 빨라지고 첫 설치·새 버전은 여전히 느리다.
4. 기록만 남김.

## 결정 (1번)
- 새 PWA 주소 `https://app.193-123-161-217.sslip.io`(sslip.io가 자동 해석, Caddy가 Let's Encrypt 인증서 자동 발급 — 2026-11-15 11:58 발급 확인).
- 운영 `/etc/caddy/Caddyfile`에 아래 블록(백업 `Caddyfile.bak-20261115-pwa`). 정적 파일은 `/srv/trova-app/dist`(ubuntu 소유, 앱 레포 `scripts/deploy-web-oracle.sh`가 rsync).

```caddyfile
app.193-123-161-217.sslip.io {
    root * /srv/trova-app/dist
    encode zstd gzip
    import /srv/trova-app/headers.caddy      # CSP 등 보안 헤더 — 앱 빌드마다 dist/_headers에서 변환해 올림
    @immutable path /_expo/static/*
    header @immutable Cache-Control "public, max-age=31536000, immutable"
    @entry path / /index.html /manifest.json
    header @entry Cache-Control "no-cache"
    try_files {path} {path}/index.html /index.html   # 단일 페이지 앱
    file_server {
        precompressed br gzip                 # 앱 레포가 만든 .br/.gz를 그대로 내보냄
    }
}
```
- 보안 헤더(CSP 해시·Referrer-Policy·nosniff·Permissions-Policy)는 Cloudflare `_headers`와 같은 내용을 Caddy `header`로 옮긴다. CSP 해시가 빌드마다 바뀌므로 PWA 배포마다 Caddy를 다시 읽는다(`caddy validate` 뒤 `systemctl reload caddy`, 앱이 끊기지 않음).
- 주소가 바뀌어 함께 바꾸는 것: 운영 `.env`의 `APP_WEB_APP_URL`(웹 로그인 콜백·CORS, `SecurityConfig`·`WebAppLoginService`는 주소 하나만 받는다), 카카오 디벨로퍼스 JavaScript SDK 도메인(지도), 웹 푸시 구독(출처별이라 새 주소에서 다시 켬 — 운영 구독자 1명·2기기).
  옛 주소 `app.trovapp.workers.dev`는 새 주소로 302 넘기는 페이지만 남긴다(앱 레포 `wrangler.redirect.jsonc`).
- 0013의 나머지(PWA 자체, 1회용 코드 로그인, 단축어, Web Share Target)는 그대로다.

## 확인 (운영, 2026-11-15 12:00, 맥 Chrome·같은 와이파이)
새 주소 첫 로딩 TTFB 47ms, 페이지 load 0.97초, 번들 br 478KB 0.23초(이전 3~7초), HTML 다시 받기 46ms(이전 600~2,100ms). 보안 헤더 동일, `/_expo/static/*` immutable, `/auth/callback/?code=` 200.
서버 쪽 추가 부담: 정적 파일 3MB(압축본 포함 5MB), Caddy 프로세스 변화 없음.

## 영향·되돌리기
- PWA 배포 절차가 바뀐다: `wrangler deploy` 대신 앱 레포 `scripts/deploy-web-oracle.sh`(서버 SSH 필요). 운영 서버가 멈추면 API와 함께 PWA도 못 연다 — 어차피 API 없이는 쓸 수 없던 앱이라 받아들인다.
- 되돌리기: `.env`의 `APP_WEB_APP_URL`을 옛 주소로, Caddyfile 블록 제거(또는 백업으로 복원) 뒤 `systemctl reload caddy`, 옛 주소에 `wrangler.jsonc`로 다시 배포.
- 다시 검토할 때: 사용자 망에서 Cloudflare가 ICN으로 붙게 되거나(cf-ray로 확인) 서버를 옮길 때.
