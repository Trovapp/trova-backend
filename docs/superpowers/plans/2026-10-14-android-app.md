# 안드로이드 앱(EAS·에뮬레이터·Expo 푸시) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 같은 Expo 코드로 안드로이드 앱(APK)을 EAS에서 빌드해 맥 에뮬레이터에서 로그인·공유·알림까지 동작시키고, 서버는 웹 구독과 함께 Expo 토큰으로도 알림을 보낸다.

**Architecture:** 서버에 `expo_push_tokens`와 `ExpoPushService`(Expo 푸시 HTTP API)를 더하고, 이벤트 리스너·관리자 테스트가 웹(`PushNotificationService`)과 앱(`ExpoPushService`) 둘 다 부른다. 앱은 네이티브용 `push.ts`·`PushOpenListener.tsx`를 실제 구현으로 바꾸고(웹 파일은 그대로), `app.config.ts`로 preview 빌드에서만 http를 허용한다.

**Tech Stack:** Spring Boot 4 / Java 21 / JPA, Expo SDK 57 / React Native 0.86, `expo-notifications`, `expo-build-properties`, EAS Build(무료), Android Emulator(API 35, google_apis_playstore arm64)

**Spec:** `docs/superpowers/specs/2026-10-14-android-app-design.md` (결정: `docs/adr/0017-android-native-app.md`)

## Global Constraints
- 비용 0원: EAS 무료(월 15빌드), Expo 푸시·Firebase 무료. 플레이 등록은 하지 않는다.
- 코드 스타일(ADR 0009): 생성자 주입, record, 와일드카드 import·`var` 금지, 한글 테스트 이름. 외부 HTTP는 별도 서비스.
- Expo 토큰 형식: `ExponentPushToken[...]` 또는 `ExpoPushToken[...]`, 200자 이하. 사람당 10개(웹과 같은 규칙).
- Expo 발송: `POST https://exp.host/--/api/v2/push/send`, 본문 배열, `channelId: "trova-default"`, `data: { open }`. 티켓 `DeviceNotRegistered` → 토큰 삭제, HTTP 429·5xx → 2초 뒤 한 번.
- 알림 문구·이동 값은 웹과 같다(`PushMessages`).
- 앱: 웹 파일(`*.web.ts(x)`) 동작을 바꾸지 않는다. 운영 빌드는 https만.
- 비밀 값(Firebase 서비스 계정 키)은 레포·서버·채팅에 두지 않는다.
- 커밋 `git commit -m "타입: 내용"`만(AI 서명 금지), 백엔드는 커밋 전 `./gradlew build`.
- 작업 위치: 백엔드 `feat/#190-expo-push`, 앱 `~/orca/workspaces/trova-app/pwa-qa`에서 `origin/feat/#7-pwa` 기반 `feat/#<앱 이슈>-android`.

## Review Focus
1. 같은 안드로이드 기기에서 다른 사람이 로그인 → 같은 토큰 등록 → 토큰이 그 사람으로 옮겨진다(E1 테스트).
2. Expo가 HTTP 200인데 티켓 하나만 `DeviceNotRegistered` → 그 토큰만 지운다(E2 테스트).
3. 웹 VAPID 키가 없어도(로컬) Expo 알림은 나간다(E2 테스트).
4. 앱이 꺼진 상태에서 알림을 눌러 열기 → 로그인 확인 뒤 그 화면(N3, 에뮬레이터 확인).
5. preview가 아닌 빌드에 http 허용이 들어가지 않는다(N1 확인: `app.config.ts` 분기 출력).

---

## Part E. 백엔드

### Task E1: Expo 토큰 저장·API·탈퇴 정리

**Files:**
- Create: `entity/ExpoPushToken.java`, `repository/ExpoPushTokenRepository.java`, `push/ExpoPushTokenService.java`, `controller/ExpoPushTokenController.java`
- Modify: `service/UserAccountService.java`
- Test: `controller/ExpoPushTokenControllerTest.java`, `service/UserAccountServiceTest.java`(케이스 1개)

**Interfaces:**
- Produces: `ExpoPushToken(Long userId, String token)`, `#reassign(Long userId)`, getter; `ExpoPushTokenRepository#findByUserId`, `#findByToken`, `#findByUserIdOrderByLastSeenAtDescIdDesc`, `#deleteByUserId`, `#deleteByTokenAndUserId`, `#deleteByIdAndUserId`, `#markSuccess(Long id, Long userId, LocalDateTime at)`; `ExpoPushTokenService#upsert(Long userId, String token)`; `POST/DELETE /api/push/expo-tokens` `{ "token" }` → 204/400.

- [ ] Step 1 실패 테스트 — `ExpoPushTokenControllerTest`(PushControllerTest와 같은 준비 코드): 비로그인 401, 등록 저장, 같은 토큰 다른 사람 → 옮겨짐(count 1), 형식 틀림(`abc`, `ExponentPushToken[]`, 201자) 400, 11개 등록 → 10개(가장 오래된 것 삭제), 해제는 내 것만. `UserAccountServiceTest`에 `탈퇴하면_앱_알림_토큰도_지운다`.
- [ ] Step 2 `./gradlew test --tests '*ExpoPushTokenControllerTest' --tests '*UserAccountServiceTest'` → 컴파일 실패.
- [ ] Step 3 구현:
  - 엔티티: `@Table(name = "expo_push_tokens", indexes = @Index(name = "idx_expo_push_tokens_user", columnList = "user_id"))`, `user_id` not null, `token` unique length 200, `created_at`, `last_seen_at`, `last_success_at`. FK 없음(share_keys·push_subscriptions와 같음).
  - 서비스 `upsert`: `PushSubscriptionService.upsert`와 같은 흐름(있으면 reassign+save, 없으면 saveAndFlush, unique 충돌이면 다시 찾아 reassign, 새로 넣은 뒤 10개 넘으면 오래된 것 삭제).
  - 컨트롤러: 정규식 `^Expo(nent)?PushToken\[[A-Za-z0-9_\-]{10,180}\]$`.
  - 탈퇴: `expoPushTokenRepository.deleteByUserId(user.getId())`(웹 구독 삭제 다음 줄).
- [ ] Step 4 같은 명령 PASS. Step 5 커밋 `feat: 앱(Expo) 알림 토큰 등록·해제 API, 탈퇴 때 삭제`.

### Task E2: Expo 발송과 웹·앱 함께 보내기

**Files:**
- Create: `push/ExpoPushClient.java`(HTTP), `push/ExpoPushService.java`
- Modify: `push/PushEventListener.java`, `controller/AdminPushController.java`
- Test: `push/ExpoPushServiceTest.java`, `push/PushEventListenerTest.java`, `controller/AdminPushControllerTest.java`

**Interfaces:**
- Consumes: E1 리포지토리, `PushMessage`.
- Produces: `interface ExpoPushClient { Response send(List<Map<String, Object>> messages) throws Exception; record Response(int status, List<String> ticketErrors) }` — `ticketErrors`는 메시지 순서대로 오류 코드(성공은 null). 실제 구현 `HttpExpoPushClient`.
  `ExpoPushService#sendToUser(Long userId, String kind, PushMessage message): PushNotificationService.Result`.

- [ ] Step 1 실패 테스트 `ExpoPushServiceTest`(가짜 클라이언트): 토큰 없으면 호출 0; 200 + 모두 성공 → sent=n·markSuccess; 200 + 두 번째만 `DeviceNotRegistered` → 그 토큰만 삭제(removed 1, sent 1); 429 다음 200 → 두 번 호출; 500·500 → failed=n·삭제 없음; 메시지 본문에 `to`·`title`·`body`·`data.open`·`channelId: trova-default`. 리스너 테스트: 웹·앱 서비스 둘 다 같은 문구로 호출. 관리자 테스트: 응답이 웹+앱 합계.
- [ ] Step 2 실행 → 실패.
- [ ] Step 3 구현:
  - `HttpExpoPushClient`: Java `HttpClient`(연결 5초, 요청 10초), `POST https://exp.host/--/api/v2/push/send`, 헤더 `Accept: application/json`, `Content-Type: application/json`, 본문 JSON 배열(static ObjectMapper). 200이면 `data[i].status == "error"`일 때 `data[i].details.error`(없으면 `message`)를 모은다.
  - `ExpoPushService`: 토큰 목록(최대 10개 → 한 번 요청), 429·5xx·예외(-1)면 2초 뒤 한 번, 결과로 `markSuccess`/`deleteByIdAndUserId`(DeviceNotRegistered)/WARN. 로그 `앱 푸시 {kind} user={id} 토큰 {n}개 → 성공·삭제·실패`(토큰 값은 남기지 않는다). 생성자 2개(기본 2초, 테스트용 Duration).
  - 리스너: 두 서비스를 각각 try로 감싸 부른다(한쪽 오류가 다른 쪽을 막지 않게).
  - 관리자 테스트: 두 결과를 더해 돌려준다, 감사 기록 문구에 웹·앱 나눠 적기.
- [ ] Step 4 PASS + `./gradlew build` 전체. Step 5 커밋 `feat: 앱(Expo) 알림 발송 — 웹 구독과 함께, 등록 해제된 기기 토큰 삭제`. PR(`Refs #190`).

---

## Part N. 앱

시작: 앱 이슈 `feat: 안드로이드 앱 빌드와 앱 알림` 만들기, 브랜치 `feat/#<번호>-android` from `origin/feat/#7-pwa`.

### Task N1: 빌드 설정(EAS·app.config·의존성)
**Files:** Create `eas.json`, `app.config.ts`; Modify `package.json`(의존성), `app.json`(eas init이 쓰는 값).
- [ ] `npx expo install expo-notifications expo-build-properties`.
- [ ] `npx -y eas-cli init --non-interactive --force`(계정 taeng527) → `app.json`에 `extra.eas.projectId`, `owner`.
- [ ] `app.config.ts`:
```ts
import type { ConfigContext, ExpoConfig } from "expo/config";

// preview(에뮬레이터 확인용) 빌드만 http(로컬 서버 10.0.2.2:8080)를 허용한다 — 운영 빌드는 https만(ADR 0017).
export default ({ config }: ConfigContext): ExpoConfig => {
  const preview = process.env.EAS_BUILD_PROFILE === "preview" || process.env.TROVA_ALLOW_HTTP === "1";
  return {
    ...(config as ExpoConfig),
    plugins: [
      ...(config.plugins ?? []),
      ["expo-notifications", { color: "#E8573F", defaultChannel: "trova-default" }],
      ["expo-build-properties", { android: { usesCleartextTraffic: preview } }],
    ],
  };
};
```
(알림 아이콘 색은 `src/lib/theme.ts`의 accent와 맞춘다. `google-services.json`이 생기면 `android.googleServicesFile: "./google-services.json"`을 이 파일에서 넣는다.)
- [ ] `eas.json`:
```json
{
  "cli": { "version": ">= 16.0.0", "appVersionSource": "remote" },
  "build": {
    "preview": {
      "distribution": "internal",
      "android": { "buildType": "apk" },
      "env": { "EXPO_PUBLIC_API_BASE_URL": "http://10.0.2.2:8080" }
    },
    "production": {
      "autoIncrement": true,
      "env": { "EXPO_PUBLIC_API_BASE_URL": "https://193-123-161-217.sslip.io" }
    }
  }
}
```
- [ ] 확인: `TROVA_ALLOW_HTTP=1 npx expo config --type public | grep -c usesCleartextTraffic.*true` → 1, 없이 → `false`. `npx tsc --noEmit` 0. 커밋 `chore: 안드로이드 EAS 빌드 설정과 알림 의존성`.

### Task N2: 네이티브 알림 구독(`push.ts`)
**Files:** Modify `src/lib/push.ts`, `src/lib/api/push.ts`(Expo 토큰 API 2개 추가).
- [ ] API: `saveExpoPushToken(token)`, `deleteExpoPushToken(token)`(웹 함수와 같은 모양, 경로 `/api/push/expo-tokens`).
- [ ] `push.ts`(네이티브): `Platform.OS !== "android"`면 지금처럼 아무것도 안 함(아이폰 네이티브는 범위 밖).
  안드로이드: 채널 `trova-default` 만들기(`setNotificationChannelAsync`), `getPushStatus`(권한 granted+토큰 저장됨 → on, denied+canAskAgain false → blocked, 그 외 off),
  `enablePush`(요청 → `getExpoPushTokenAsync({ projectId: Constants.expoConfig?.extra?.eas?.projectId })` → 저장 → 마지막 토큰을 SecureStore `trova.expoPushToken`에 기억),
  `disablePush`(기억한 토큰으로 DELETE, 기억 지우기), `refreshPushSubscription`(권한 granted면 토큰 다시 받아 저장),
  `isPushPromptVisible`(로그인·`shouldShowPushPrompt({ standalone: true, supported: true, permission, hasPublicKey: true, loggedIn, subscribed, snoozedUntil, now })`), `snoozePushPrompt`(SecureStore `trova.pushPromptSnoozedUntil`).
  앱이 앞에 있을 때도 알림을 보이게 `Notifications.setNotificationHandler`(배너·소리).
- [ ] `npx tsc --noEmit` 0, 커밋 `feat: 안드로이드 앱 알림 권한·토큰 등록`.

### Task N3: 알림 눌러 화면 열기(네이티브 `PushOpenListener.tsx`)
**Files:** Modify `src/components/PushOpenListener.tsx`.
- [ ] 웹 파일의 `go(target)`와 같은 reset. `getLastNotificationResponseAsync()`(꺼진 상태에서 연 경우) + `addNotificationResponseReceivedListener` → `parseOpenTarget(data.open)` → 로그인·내비 준비면 바로, 아니면 `setPendingOpen` → user 생기면 꺼내기(웹과 같은 interval).
- [ ] `npx tsc --noEmit` 0, `npm run test:web` 통과, 커밋 `feat: 안드로이드 앱 알림을 누르면 초안·여행 화면으로`. 앱 PR(base `feat/#7-pwa`, `Closes #<앱 이슈>`).

### Task N4: 빌드·에뮬레이터 QA
- [ ] 사용자: Firebase 프로젝트·안드로이드 앱(`com.trovapp.trova`) 등록 → `google-services.json`을 앱 폴더에(내가 `app.config.ts`에 연결), 서비스 계정 키 → `npx eas-cli credentials`(Android → FCM V1)로 직접 업로드.
- [ ] `npx eas-cli build -p android --profile preview --non-interactive` → APK 받기.
- [ ] 에뮬레이터 `trova-qa` 실행, 로컬 서버(8080, 웹 푸시·Expo 브랜치 코드), `adb install`.
- [ ] 확인(스크린샷 `scratchpad/qa/android-*.png`): 로그인(카카오), 공유(`adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT <유튜브 링크> com.trovapp.trova`), 분석 화면 카드 → 권한 → 토큰 저장(DB), 관리자 테스트 발송 → 알림 → 누르기, 뒤로 가기, 키보드, 지도, 다크 모드(`adb shell cmd uimode night yes`), 큰 글자(`adb shell settings put system font_scale 1.3`).

## Part D. 배포·기록
- [ ] 백엔드 PR 머지 → devflow deploy(새 설정 없음) → `expo_push_tokens` 생성 확인.
- [ ] 개인정보처리방침: 1항 알림 줄에 "앱 알림 토큰", 4항에 "650 Industries, Inc.(미국) — Expo 푸시 | 앱 알림 토큰, 알림 내용 | 알림 전달 | 안드로이드 앱에서 알림을 허용한 경우", FCM 줄 비고에 "안드로이드 앱" 추가.
- [ ] `docs/experience-notes.md`에 웹 푸시·안드로이드 기록(로컬/운영 구분), 메모리 갱신.
