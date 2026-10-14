# PWA 웹 푸시 알림 설계

- 날짜: 2026-10-14 · 이슈: 백엔드 #185(앱 이슈는 구현 때 연다) · 결정 기록: [ADR 0016](../../adr/0016-pwa-web-push.md)
- 범위: 백엔드(이 레포) + 앱(Trovapp/trova-app) 웹 버전. 네이티브 앱 푸시(#137, Expo)는 하지 않는다.

## 왜
- 단축어로 영상을 보내면 단축어가 "Trova에 보냈어요"만 띄우고, 분석·일정 초안이 끝난 건 앱을 열어야 안다
  (2026-10-13 사용자: "단축어로 링크 보내고 다 됐을때 알림은 원래 안뜨는건가?").
- 사용자 결정(2026-10-13~14): 알릴 일은 **일정 초안 준비됨 + 여행 날 비 소식**, 대상은 **PWA 웹 푸시만**,
  권한은 **첫 영상을 보낸 직후**, 방식은 **A. 직접 보내는 표준 웹 푸시**, 설계 1~4 모두 "좋아", ADR 0013 변경 "응 ADR 바꾸고 진행해줘".

## 성공 기준
- 홈 화면에 설치한 아이폰 PWA(iOS 16.4+)에서 알림을 허용한 뒤, 단축어로 영상을 보내면 앱을 열지 않아도
  "일정 초안이 준비됐어요" 알림이 오고, 누르면 그 초안 화면이 열린다.
- 알림 키가 없는 환경(로컬·테스트)에서도 서버와 앱이 지금처럼 돈다.
- 비용 0원, 새 외부 가입 없음.

## 전체 흐름
```
[앱] 첫 영상 보냄 → 안내 카드 [알림 받기] → 브라우저 권한 창 → 허용
      → GET /api/push/public-key → pushManager.subscribe → POST /api/push/subscriptions
[서버] 자동 초안 READY 또는 비 알림 생성 → (커밋 뒤, @Async) PushNotificationService
      → 사용자 구독마다 암호화 + VAPID 서명 → 애플/구글/모질라 푸시 서버
[기기] 서비스 워커 push → 알림 표시 → 누름 → notificationclick → /?open=... 로 앱 열기·이동
```

## 1. 서버

### 저장: `push_subscriptions`
| 칸 | 내용 |
|---|---|
| id | |
| user_id | 사용자(여러 기기 = 여러 행) |
| endpoint | 푸시 서버 주소, **unique** |
| p256dh, auth | 브라우저가 준 암호화 키 두 개 |
| created_at, last_seen_at, last_success_at | 만든 때 / 앱이 마지막으로 다시 등록한 때 / 마지막 발송 성공 |

- JPA 엔티티 `PushSubscription`, 리포지토리 `PushSubscriptionRepository`(`findByUser`, `findByEndpoint`, `deleteByEndpoint`).
- 탈퇴 때 사용자 구독도 지운다(기존 탈퇴 정리 목록에 추가).

### API
| 메서드·경로 | 내용 |
|---|---|
| `GET /api/push/public-key` | `{ "publicKey": "..." }`. 키가 없으면 404(앱은 카드를 숨긴다) |
| `POST /api/push/subscriptions` | `{ endpoint, keys: { p256dh, auth } }`. endpoint가 이미 있으면 사용자·키·`last_seen_at`을 덮어쓴다(같은 기기에서 다른 사람이 로그인한 경우 포함). 204 |
| `DELETE /api/push/subscriptions` | `{ endpoint }`. 내 구독만 지운다(남의 것이면 아무것도 안 하고 204) |
| `POST /api/admin/push/test` | 관리자 본인 구독에 테스트 알림(비 알림·초안 알림 문구 중 선택). 운영에서 날씨를 만들 수 없어 같은 문구를 확인하는 용도 |

- 요청·응답은 `record`. endpoint는 `https://`만 받고 길이를 제한한다(1,000자).

### 발송
- `PushNotificationService`(외부 연동이라 분리, CLAUDE.md 규칙): 보낼 내용 `PushMessage(title, body, openPath, tag)`를 받아 사용자의 구독마다 보낸다.
  - 라이브러리: `com.zerodeplibs:zerodep-web-push-java` 2.1.5(의존성 없음). HTTP는 Java 기본 `HttpClient`.
  - 결과 처리: 2xx → `last_success_at`, **404·410 → 구독 삭제**, **429·5xx → 2초 뒤 한 번만 다시**, 그래도 실패면 WARN 로그. 그 외 4xx는 WARN.
  - TTL 24시간(기기가 꺼져 있어도 하루 안에 켜지면 받음), urgency normal.
  - 로그: `웹 푸시 {종류} user={id} 구독 {n}개 → 성공 {s}·삭제 {d}·실패 {f}` (endpoint·키는 남기지 않는다).
- 보내는 때(둘 다 DB 커밋 뒤 `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`)
  - `TripPlannerService`에서 **자동 초안**(`draft.isAutoCreated()`)이 READY가 될 때 → `TripDraftReadyEvent(draftId)`.
    수동 초안·실패한 초안은 보내지 않는다.
  - `WeatherRecoveryService`에서 비 알림을 **새로 저장할 때** → `RainNotificationCreatedEvent(notificationId)`.
    이미 있는 알림(같은 일차)은 지금처럼 새로 만들지 않으므로 다시 보내지 않는다.
- 나중에 Expo 푸시를 더할 때는 `PushNotificationService` 안에 보내는 방법만 더한다(내용 `PushMessage`는 그대로).

### 날씨 확인 시각
- `WeatherCheckScheduler`: `@Scheduled(fixedRate = 4h)` → `@Scheduled(cron = "0 0 8,12,16,20 * * *", zone = "Asia/Seoul")`.
  하루 4번은 같고, 알림이 밤에 가지 않는다.

### 설정
- `.env`: `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT`(mailto:trovapp26@gmail.com).
  운영 키와 로컬 개발 키는 따로 만든다. 키 값은 레포·채팅·외부에 두지 않는다.
- 키가 비어 있으면 `PushNotificationService`는 아무것도 보내지 않고 시작 때 INFO 한 줄만 남긴다. 공개 키 API는 404.

## 2. 앱(PWA)

### 권한 묻기
- 안내 카드: "분석이 끝나고 일정이 준비되면 알려 드릴까요?" [알림 받기] [나중에]
  - 앱에서 영상을 보낸 뒤 → 분석 진행 화면(`Processing`)에.
  - 단축어로 보낸 사람 → 다음에 앱을 열 때 홈 화면 위쪽에(보낸 영상이 하나 이상 있을 때).
- [알림 받기]를 눌렀을 때만 `Notification.requestPermission()`(iOS는 사용자 동작 안에서만 물을 수 있다).
- [나중에] → 7일 동안 다시 묻지 않음(기기 저장소, 읽기·쓰기는 try/catch).
- 카드를 보이는 조건: 웹 + 설치한 PWA(standalone) + `PushManager` 지원 + 권한이 `default` + 서버 공개 키 있음 + 로그인.
  거절(`denied`)·미지원이면 숨긴다.
- 네이티브 앱은 바꾸지 않는다: 웹 전용 파일(`src/lib/web/push.web.ts`, 앱 쪽은 아무것도 안 하는 `push.ts`)에 둔다.

### 구독
- 허용 → 공개 키로 `pushManager.subscribe({ userVisibleOnly: true, applicationServerKey })` → `POST /api/push/subscriptions`.
- 앱을 열 때마다(로그인 상태, 권한 `granted`) 지금 구독을 다시 보낸다. 없으면 새로 만든다.
- 로그아웃 → `DELETE /api/push/subscriptions` 후 `subscription.unsubscribe()`.

### 서비스 워커(`public/sw.js`)
- 캐시하지 않는 지금 원칙은 그대로.
- `push`: 데이터 `{ title, body, open, tag }`로 `showNotification(title, { body, tag, icon, badge, data: { open } })`.
- `notificationclick`: 알림을 닫고, 열린 Trova 창이 있으면 그 창에 `postMessage({ type: "open", open })` + focus,
  없으면 `clients.openWindow("/?open=" + open)`.

### 설정 화면
- "알림" 줄: 켜짐 / 꺼짐 / 브라우저에서 막힘. 켜기는 위 구독 흐름, 끄기는 로그아웃과 같은 해제.
- 막힘이면 "iPhone 설정 → 알림 → Trova에서 켤 수 있어요" 안내.

## 3. 알림 문구와 이동

| 종류 | 제목 | 내용 | 이동(`open`) | tag |
|---|---|---|---|---|
| 일정 초안 준비 | 일정 초안이 준비됐어요 | 영상 속 장소 {n}곳으로 {기간} 일정을 짰어요. 확인하고 여행에 담아 보세요. | `draft:{draftId}` → `PlanTrip` | `draft-{draftId}` |
| 여행 날 비 소식 | {여행 이름}에 비 소식이 있어요 | {M월 d일(E)} 강수확률 {p}%예요. {장소} 대신 갈 실내 장소를 볼까요? | `trip:{tripId}:place:{tripPlaceId}` → `TripDetail(weatherAlertTripPlaceId)` | `rain-{itineraryId}` |

- {기간}: 1일 "당일", n일 "{n-1}박 {n}일". {n}은 초안 일정에 든 장소 수.
- 앱 안 비 알림 문구("1일차(2026-10-20)에…")는 이번에 바꾸지 않는다(푸시 문구만 새로).
- 앱 시작 때 주소의 `open`을 읽어 이동하고 주소에서 지운다. 열린 창은 서비스 워커 메시지로 이동한다.
  로그인이 필요하면 로그인 뒤 이동, 초안·여행이 없거나 남의 것이면(404) 홈으로.
- 같은 tag는 기기에서 하나로 바뀌어 보인다.

## 4. 테스트

### 서버(`./gradlew build`)
- 구독 API: 등록 저장, 같은 endpoint 덮어쓰기(사용자 바뀜 포함), 해제, 남의 구독 해제 불가, 비로그인 401, `http://` endpoint 400.
- 보내는 때: 자동 초안 READY에 1번, 수동·실패 초안 0번, 비 알림 새로 생성에 1번·이미 있으면 0번, 롤백되면 0번.
- 결과 처리(가짜 HTTP 응답): 410·404 구독 삭제, 429 한 번 재시도, 500 두 번이면 WARN·구독 유지.
- 키 없음: 앱 시작 정상, 발송 0번, 공개 키 404.
- 문구: 날짜(`10월 20일(월)`), 기간(당일/1박 2일), 장소 수.
- 스케줄: cron이 8·12·16·20시, 시간대 Asia/Seoul.
- 관리자 테스트 발송: 관리자만(일반 사용자 403), 본인 구독에만.

### 앱(로컬 서버 + 개발 DB, PC 크롬)
- 개발용 VAPID 키로 로컬 서버 실행 → 카드 표시 → [알림 받기] → 허용 → 구독 저장 확인(DB).
- 관리자 테스트 발송으로 알림 표시, 누르면 맞는 화면(앱 열림·닫힘 둘 다).
- [나중에] 7일 숨김, 거절 상태 설정 안내, 로그아웃 시 구독 삭제.

### 운영(실제 아이폰, 사용자)
- 배포 후 홈 화면 PWA에서 허용 → 단축어로 영상 보냄 → 초안 알림 도착·눌러서 초안 화면, 서버 로그에 발송 결과.
- 비 알림은 관리자 테스트 발송으로 같은 문구 확인.

## 배포 체크리스트
1. 개인정보처리방침(trovapp.github.io): 수집 항목 "푸시 구독 정보(알림 허용 시)", 4항 위탁 표에 애플·구글·모질라 푸시 서버.
2. 운영 `.env`에 VAPID 키 3개(백업 후), 재시작.
3. 백엔드 배포(devflow deploy) → 공개 키 API 200 확인.
4. PWA 배포(Cloudflare, 운영 API 주소로 빌드).
5. 실제 아이폰 확인, `docs/experience-notes.md`에 로컬·운영 결과를 나눠 기록.

## 하지 않는 것
- 네이티브 앱 Expo 푸시(#137), 안드로이드 앱.
- 초안 실패·분석 완료(초안 없이) 알림, 알림 종류별 켜고 끄기, 조용한 시간 설정.
- 앱 안 비 알림 문구 개선(별도 후보).
