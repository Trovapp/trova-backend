# 안드로이드 앱 설계 (EAS 빌드·에뮬레이터 확인·앱 알림)

- 날짜: 2026-10-14 · 이슈: 백엔드 #190(앱 이슈는 구현 때 연다), 관련 #137 · 결정 기록: [ADR 0017](../../adr/0017-android-native-app.md)
- 범위: 앱(Trovapp/trova-app, Expo) 안드로이드 빌드·확인 + 백엔드(이 레포) Expo 푸시 발송. 아이폰 네이티브 앱과 플레이 등록은 하지 않는다.

## 왜
- 사용자 결정(2026-10-14): "ios는 pwa로 가고 안드로이드는 따로 또 만들어서 앱 출시까지 가고 싶은데 안드로이드도 한번 해줘".
  범위 "빌드·설치까지 먼저", 빌드 "EAS 무료 빌드", 확인 "폰 없음 — 맥 에뮬레이터", 알림 "이번에 넣기", 설계 "진행해줘".
- 안드로이드 앱은 설치하면 공유 목록에 Trova가 뜬다 — 단축어 없이 "공유 한 번"이 된다.

## 성공 기준
- EAS로 만든 APK를 에뮬레이터(안드로이드 15, 구글 플레이 이미지)에 설치해 다음이 된다(로컬 서버·개발 DB 대상 빌드):
  1. 카카오·구글 로그인 후 앱으로 돌아온다.
  2. 크롬에서 유튜브 링크를 공유 → Trova → 분석이 시작된다.
  3. 분석 화면에서 [알림 받기] → 안드로이드 알림 권한 허용 → 서버에 Expo 토큰 저장.
  4. 관리자 테스트 발송 → 알림 표시 → 누르면 그 화면.
  5. 지도·뒤로 가기·키보드·다크 모드·큰 글자에서 화면이 깨지지 않는다.
- 비용 0원(EAS 무료, Expo 푸시 무료, Firebase 무료). 플레이 등록비는 이번에 쓰지 않는다.

## 1. 빌드
- `eas.json`
  - `preview`: `android.buildType: "apk"`, `distribution: "internal"`, `env.EXPO_PUBLIC_API_BASE_URL` = 확인 대상 서버.
  - `production`: AAB(기본), `autoIncrement: true`(버전 코드 원격 관리).
- `app.json`: `eas init`이 `extra.eas.projectId`·`owner`를 넣는다. 안드로이드 `versionCode`는 EAS 원격 관리.
- 처음 한 번 사용자가 Expo 로그인(`! npx eas login`). 서명 키는 EAS가 만들어 보관한다(분실 위험 없음, 플레이 앱 서명과 함께 쓸 수 있음).
- 에뮬레이터에서 로컬 서버를 보려면 API 주소를 `http://10.0.2.2:8080`(에뮬레이터에서 맥의 localhost)로 빌드한다.
  안드로이드는 기본으로 평문(http)을 막으므로 `preview` 빌드만 평문을 허용한다(`expo-build-properties`의 `usesCleartextTraffic`, preview 프로필에서만 켜지도록 `app.config` 분기 — 운영 빌드는 https만).

## 2. 에뮬레이터
- Homebrew로 `android-commandlinetools`(cask) 설치 → `sdkmanager`로 `platform-tools`, `emulator`, `system-images;android-35;google_apis_playstore;arm64-v8a` → `avdmanager`로 AVD "trova-qa".
- 디스크 약 5~8GB. 설치 위치 `~/Library/Android/sdk`.
- QA는 `adb install`, `adb shell input tap/text`, `adb exec-out screencap`, `adb shell am start -a android.intent.action.SEND`(공유 흉내)로 한다.
- 에뮬레이터 안 구글 로그인(플레이 서비스)은 테스트 구글 계정이 필요할 수 있다 — 필요하면 사용자에게 묻는다(비밀번호는 사용자가 직접 입력).

## 3. 안드로이드에서 따로 확인할 것
| 부분 | 확인 |
|---|---|
| 로그인 | `WebBrowser.openAuthSessionAsync(..., "trova://auth")` → 크롬 맞춤 탭 → `trova://auth?token=` 로 앱 복귀(앱 `scheme: trova`) |
| 공유 받기 | `expo-share-intent` 안드로이드 인텐트 필터 `text/*` — 유튜브·인스타·크롬 공유 |
| 카카오 지도 | WebView `baseUrl: https://localhost` — 카카오 JS 키 허용 도메인에 이미 있음(iOS와 같음) |
| 뒤로 가기 | 기기 뒤로 가기 버튼이 스택을 하나씩 닫고, 탭 첫 화면에서는 앱을 나간다 |
| 키보드 | `softwareKeyboardLayoutMode: "resize"` — 입력칸이 가려지지 않는지 |
| 보안 저장소 | 토큰은 `expo-secure-store`(안드로이드 Keystore) |
| iOS 전용 분기 | `Platform.OS === "ios"` 12곳 — 안드로이드에서 빠진 기능이 없는지 훑기 |

## 4. 앱 알림 (Expo 푸시)

### 앱
- `expo-notifications` 추가(설정 플러그인, 알림 아이콘·색). 안드로이드 알림 채널 "trova-default"(중요도 기본).
- `src/lib/push.ts`(지금은 아무것도 안 하는 네이티브 파일)를 네이티브 구현으로 바꾼다. 웹(`push.web.ts`)은 그대로. 모양(`PushStatus`, `enablePush`, `disablePush`, `refreshPushSubscription`, `isPushPromptVisible`, `snoozePushPrompt`, `getPushStatus`)은 같다.
  - `enablePush`: `requestPermissionsAsync()`(안드로이드 13+ 알림 권한 창) → `getExpoPushTokenAsync({ projectId })` → `POST /api/push/expo-tokens`.
  - `refreshPushSubscription`: 권한이 있으면 토큰을 다시 보낸다(앱 열 때).
  - `disablePush`: `DELETE /api/push/expo-tokens`(로그아웃·끄기).
  - 카드 표시 조건은 웹과 같은 `shouldShowPushPrompt`(standalone 대신 "네이티브 앱"이 참).
  - [나중에] 7일 기록은 `AsyncStorage` 대신 이미 쓰는 저장소(`expo-secure-store`)에 둔다.
- 알림 누르기: `Notifications.addNotificationResponseReceivedListener` + 앱이 꺼져 있다 열린 경우 `getLastNotificationResponseAsync()` → `data.open` → 웹과 같은 `parseOpenTarget` → `navigationRef.reset([홈, 대상])`. 로그인 전이면 `pendingOpen`에 담았다가 로그인 뒤.
- 아이폰 네이티브 빌드에는 영향이 없게 한다(iOS 빌드를 하지 않지만 코드가 깨지지 않게 `Platform.OS === "android"`에서만 권한·채널 설정).

### 서버
- 새 테이블 `expo_push_tokens`(id, user_id, token unique(200자), created_at, last_seen_at, last_success_at) — 웹 구독과 구조가 달라 따로 둔다.
- `POST /api/push/expo-tokens` `{ token }` — `ExponentPushToken[...]` 형식만, 같은 토큰이면 사용자까지 덮어쓰기(동시 등록 안전), 사람당 10개.
  `DELETE /api/push/expo-tokens` `{ token }` — 내 것만. 탈퇴 때 함께 삭제.
- `PushNotificationService.sendToUser`가 웹 구독에 더해 Expo 토큰으로도 보낸다. Expo 쪽은 `ExpoPushTransport`(별도 클래스):
  - `POST https://exp.host/--/api/v2/push/send` 본문 배열(최대 100개) `{ to, title, body, data: { open }, channelId: "trova-default", sound: "default" }`.
  - 응답 티켓 `status: "error"` + `details.error: "DeviceNotRegistered"` → 토큰 삭제. HTTP 429·5xx → 2초 뒤 한 번 다시. 그 외 실패는 WARN.
  - 영수증(receipt) 확인은 하지 않는다(티켓의 DeviceNotRegistered로 정리, 남는 오류는 로그).
  - Expo 접근 토큰(선택 기능)은 쓰지 않는다 — 키 관리가 하나 줄고, 무료 그대로.
- 결과 로그는 웹과 함께 한 줄: `웹 푸시 {kind} user={id} 구독 {n}개·앱 {m}개 → 성공 …`.

### 사용자가 할 일(콘솔)
1. Firebase 콘솔에서 프로젝트 만들기(무료 Spark) → 안드로이드 앱 등록(패키지 `com.trovapp.trova`) → `google-services.json` 받기.
2. Firebase 서비스 계정 키(JSON) 만들기 → `eas credentials`로 Expo에 FCM V1 키 올리기.
- `google-services.json`은 앱 설정 파일이라 레포에 둘 수 있지만(공개 키 성격), 서비스 계정 키는 레포·서버·채팅에 두지 않는다.

### 개인정보처리방침
- 1항 알림 줄에 "앱 알림 토큰" 추가, 4항에 "650 Industries(Expo, 미국) — 알림 전달", Google LLC FCM 줄에 안드로이드 앱 포함.

## 5. 테스트
- 서버(`./gradlew build`): 토큰 API(형식·덮어쓰기·사람당 10개·남의 것 삭제 불가·비로그인 401), Expo 발송(가짜 HTTP: 성공·DeviceNotRegistered 삭제·429 한 번 재시도·5xx 실패 유지), 웹+앱 함께 보내기, 탈퇴 삭제.
- 앱: `npx tsc --noEmit`, `npm run test:web`(순수 함수 그대로).
- 에뮬레이터 QA(로컬 서버·개발 DB, preview 빌드): 성공 기준 1~5. 결과는 `docs/experience-notes.md`에 로컬로 표시.

## 6. 순서
1. 백엔드: Expo 토큰 저장·API·발송(로컬 테스트 통과) → PR.
2. 앱: `eas.json`·`app.config` 분기·`expo-notifications`·네이티브 `push.ts`·알림 누르기 → PR.
3. 사용자: Expo 로그인, Firebase 프로젝트·키 등록.
4. EAS preview 빌드 → 에뮬레이터 설치·QA.
5. 배포: 백엔드 머지·운영 배포(새 설정 없음), 방침 갱신. 플레이 등록은 사용자가 결정할 때.

## 하지 않는 것
- 플레이 등록·비공개 테스트·스토어 문구/스크린샷(다음 단계), 아이폰 네이티브 빌드, 알림 영수증 확인, Expo 접근 토큰.
