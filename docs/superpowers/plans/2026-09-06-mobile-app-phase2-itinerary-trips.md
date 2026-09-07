# React Native 모바일 앱 2단계(일정/여행) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 모바일 앱(`trova-app`)에서 장소 추출이 끝난 뒤 웹과 동일하게
영상 그룹 화면 → 일정 생성/편집 → 여행 확정 → 여행 상세(검색/찜/리뷰
요약/날씨체크 포함)까지 전체 플로우를 동작시킨다.

**Architecture:** 순수 모바일 작업이다(백엔드 변경 없음 — 필요한
엔드포인트가 이미 다 존재함을 브레인스토밍 단계에서 확인함). 기존
`ProcessingScreen`(진행률 폴링 화면)을 최초 추출과 일정 생성 두 경우
모두에 재사용하고, 완료 후 도착지를 `PlaceDetail`/`Map`(폐기)이 아닌
새 `VideoGroupScreen`으로 통일한다. 일정 편집은 낙관적 업데이트+롤백,
여행 상세는 매 변경 후 재조회 — 웹의 실제 구현을 그대로 따른다.

**Tech Stack:** Expo(TypeScript) + React Navigation + TanStack Query +
`react-native-webview`(기존, 카카오맵) + `@react-native-community/datetimepicker`(신규)

**Spec:** `docs/superpowers/specs/2026-09-06-mobile-app-phase2-itinerary-trips-design.md`

## Global Constraints

- 이 계획은 **trova-app 저장소에서만** 작업한다
  (`/Users/gimtaehyeong/Desktop/trova-app`, branch `main`). 백엔드
  변경은 없다 — 어떤 태스크에서든 백엔드 변경이 필요하다고 판단되면
  구현을 멈추고 그 사실을 보고한다.
- 커밋 메시지는 `타입: 내용` 형식만 사용, AI 서명/트레일러/이모지
  절대 금지 (trova-app의 기존 커밋 로그 관례).
- `npx tsc --noEmit`가 매 태스크 후 에러 없이 통과해야 한다.
- **모든 신규/수정 API 클라이언트 파일은 반드시 `src/lib/api/client.ts`의
  `apiFetch`를 사용한다.** 참고하는 웹 소스(`trova-frontend`)는 쿠키
  기반 인증이라 `fetch(url, { credentials: "include" })`를 직접
  쓰지만, 모바일은 Bearer 토큰 인증이므로 그 패턴을 그대로 베끼면 안
  된다 — `apiFetch(path, options)`만 사용하고 `API_BASE_URL`을 직접
  붙이지 않는다(이미 `apiFetch` 내부에서 처리함).
- 자동화된 RN 컴포넌트 테스트는 만들지 않는다(1단계와 동일한 결정 —
  테스트 프레임워크 자체가 이 프로젝트에 없음). 검증은 `tsc` +
  이미 켜져 있는 iOS 시뮬레이터에서 `xcrun simctl io booted
  screenshot`로 캡처 후 Read 도구로 확인하는 방식을 쓴다(시뮬레이터를
  실시간으로 보는 도구가 없으므로).
- 신규 의존성은 `@react-native-community/datetimepicker` 하나뿐이며
  `npx expo install`로 설치해 Expo SDK와 호환되는 버전을 자동
  선택한다. 이 라이브러리는 MIT 라이선스, 무료 — 비용 원칙에 저촉
  없음.
- **디자인 토큰**: 이 플랜의 태스크별 코드 블록은 색상/폰트 정리
  작업(커밋 `9cde75c`, "style: 웹 디자인 토큰(색상/폰트) 그대로 이식")
  이전에 작성됐다. 아래 코드 블록에 나오는 하드코딩된 값은 전부 실행
  시점에 `src/lib/theme.ts`의 `colors` 토큰으로 바꿔서 적용한다(필요한
  파일 상단에 `import { colors } from "@/lib/theme";` 추가):
  - `"#FF6B4A"` → `colors.accent`
  - `"#8C8C86"` → `colors.inkMuted`
  - `"#DEDED8"` → `colors.border`
  - `"#EFEFEA"` / `"#F5F5F0"` / `"#FAFAF7"` → `colors.bgMuted`
  - `"#FFF1EC"` → `colors.accentBg`
  - 배경으로 쓰인 `"#fff"` → `colors.bg`
  - `"#FEE500"` → `colors.kakao`

  본문 폰트는 IBM Plex Mono에서 Noto Sans KR로 바뀌었다. `AppText`
  컴포넌트는 이미 기본값이 Noto Sans KR이므로 그대로 쓰면 되고, `AppText`를
  거치지 않는 `TextInput` 등에 직접 적은 `fontFamily: "IBMPlexMono_400Regular"`/
  `"IBMPlexMono_500Medium"`은 각각 `"NotoSansKR_400Regular"`/
  `"NotoSansKR_500Medium"`로 바꾼다. `IBMPlexMono_400Regular`(또는
  `AppText`의 `mono` prop)는 주소·시간·퍼센트 같은 작은 메타 텍스트에만
  남겨둔다 — 예: `PlaceRow`의 주소 텍스트, `ProcessingScreen`의 퍼센트
  숫자와 "AI ANALYSIS" 라벨은 `mono`를 유지한다(`ProcessingScreen.tsx`엔
  이미 적용되어 있음 — 그대로 둔다).

  카드 그림자는 기존 `CARD_SHADOW = Platform.select({ios:..., android:...})`
  패턴을 그대로 재사용한다.
- `PlaceDetailScreen.tsx`/`MapScreen.tsx`와 `PlaceDetail`/`Map` 라우트는
  이 계획에서 완전히 삭제된다 — 웹에는 개별 장소 하나만 보여주는
  페이지가 없고(`/places/[id]`의 `id`는 실제로는 `sourceUrl`), 지도는
  화면 안에 인라인으로 내장하는 것으로 대체하기 때문이다. 삭제 후
  아무 파일도 이 두 파일을 import하지 않아야 한다.

---

### Task 1: `haversineDistanceKm` 포팅

**Files:**
- Create: `src/lib/geo.ts`

**Interfaces:**
- Produces: `haversineDistanceKm(lat1: number, lng1: number, lat2: number, lng2: number) -> number`
  (km 단위 직선거리)

- [ ] **Step 1: 구현**

`trova-frontend`의 `src/lib/geo.ts`를 그대로 포팅한다(로직 변경 없음):

```ts
const EARTH_RADIUS_KM = 6371;

function toRadians(degrees: number): number {
  return (degrees * Math.PI) / 180;
}

// Haversine 공식 — 두 좌표 사이의 직선거리(km). 실제 이동 경로 거리가 아니다.
export function haversineDistanceKm(
  lat1: number,
  lng1: number,
  lat2: number,
  lng2: number
): number {
  const dLat = toRadians(lat2 - lat1);
  const dLng = toRadians(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRadians(lat1)) * Math.cos(toRadians(lat2)) * Math.sin(dLng / 2) ** 2;
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return EARTH_RADIUS_KM * c;
}
```

- [ ] **Step 2: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add src/lib/geo.ts
git commit -m "feat: 좌표 간 직선거리 계산 유틸 추가"
```

---

### Task 2: 일정 그룹핑 유틸 포팅

**Files:**
- Create: `src/lib/itinerary.ts`

**Interfaces:**
- Consumes: `Place`(Task 3에서 `dayNumber`/`orderInDay` 필드가
  추가되지만, 이 태스크는 그 필드만 참조하는 제네릭 타입을 자체
  정의해서 먼저 진행 가능 — Task 3과 순서 의존성 없음)
- Produces: `isItineraryGroup(places) -> boolean`,
  `groupByDay(places) -> Map<number, T[]>`, `getDayColor(dayNumber) -> string`

- [ ] **Step 1: 구현**

`trova-frontend`의 `src/lib/itinerary.ts`(`isItineraryGroup`/`groupByDay`/
`getDayColor`)를 포팅한다. 웹의 `SavedPlace`엔 있지만 모바일 `Place`엔
없는 `status` 필드는 쓰지 않으므로 그대로 옮기면 된다. 제네릭으로
만들어 `Place`(Task 3)와 이후 다른 타입에도 재사용할 수 있게 한다:

```ts
type DayAssignable = {
  dayNumber: number | null;
  orderInDay: number | null;
};

export function isItineraryGroup(places: DayAssignable[]): boolean {
  return places.some((place) => place.dayNumber !== null);
}

export function groupByDay<T extends DayAssignable>(places: T[]): Map<number, T[]> {
  const days = new Map<number, T[]>();
  for (const place of places) {
    if (place.dayNumber === null) continue;
    const existing = days.get(place.dayNumber) ?? [];
    existing.push(place);
    days.set(place.dayNumber, existing);
  }
  for (const dayPlaces of days.values()) {
    dayPlaces.sort((a, b) => (a.orderInDay ?? 0) - (b.orderInDay ?? 0));
  }
  return days;
}

const DAY_COLORS = ["#FF6B4A", "#4A90D9", "#4AC98F", "#D9A94A", "#9B6BD9", "#D94A8C"];

export function getDayColor(dayNumber: number): string {
  return DAY_COLORS[(dayNumber - 1) % DAY_COLORS.length];
}
```

- [ ] **Step 2: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add src/lib/itinerary.ts
git commit -m "feat: 일자별 장소 그룹핑 유틸 추가"
```

---

### Task 3: `places.ts` 확장 — 일정 관련 필드/함수 추가

**Files:**
- Modify: `src/lib/api/places.ts`

**Interfaces:**
- Consumes: `apiFetch` (기존, `src/lib/api/client.ts`)
- Produces: `Place` 타입에 `dayNumber`/`orderInDay`/`phone`/`roadAddress`/
  `kakaoCategoryName`/`kakaoPlaceUrl` 필드 추가. 새 함수
  `generateItinerary(jobId) -> Promise<void>`,
  `moveToDay(placeId, dayNumber) -> Promise<Place>`,
  `reorderPlace(placeId, direction) -> Promise<Place>`,
  `optimizeRoute(jobId, day) -> Promise<Place[]>`

현재 파일(`src/lib/api/places.ts`) 전체:

```ts
import { apiFetch } from "@/lib/api/client";

export type Place = {
  id: number;
  jobId: number;
  placeName: string;
  region: string | null;
  category: string | null;
  latitude: number | null;
  longitude: number | null;
  sourceUrl: string;
  title: string | null;
  sourcePlatform: "INSTAGRAM" | "YOUTUBE";
  createdAt: string;
  address: string | null;
};

export type PendingJob = {
  jobId: number;
  sourceUrl: string;
  title: string | null;
  sourcePlatform: "INSTAGRAM" | "YOUTUBE";
  status: "PENDING" | "PROCESSING" | "FAILED";
  createdAt: string;
  currentStage: "EXTRACTING" | "GEOCODING" | "SELECTING" | "VERIFYING" | "SAVING" | null;
  progressPercent: number | null;
  stageMessage: string | null;
};

export async function createShare(url: string): Promise<{ jobId: number }> { /* ... */ }
export async function getPendingJobs(): Promise<PendingJob[]> { /* ... */ }
export async function getPlaces(): Promise<Place[]> { /* ... */ }
export async function getPlace(id: number): Promise<Place | null> { /* ... */ }
```

(`createShare`/`getPendingJobs`/`getPlaces`/`getPlace`의 실제 구현
본문은 건드리지 않는다 — 아래 diff에 없는 부분은 그대로 둔다.)

- [ ] **Step 1: `Place` 타입에 필드 추가**

`export type Place = { ... };` 블록의 `address: string | null;` 바로
아래에 추가:

```ts
  dayNumber: number | null;
  orderInDay: number | null;
  phone: string | null;
  roadAddress: string | null;
  kakaoCategoryName: string | null;
  kakaoPlaceUrl: string | null;
```

- [ ] **Step 2: 신규 함수 추가**

파일 맨 끝(`getPlace` 함수 뒤)에 추가:

```ts
export async function generateItinerary(jobId: number): Promise<void> {
  const res = await apiFetch(`/api/places/videos/${jobId}/itinerary`, { method: "POST" });
  if (!res.ok) {
    throw new Error(`POST /api/places/videos/${jobId}/itinerary failed: ${res.status}`);
  }
}

export async function moveToDay(placeId: number, dayNumber: number): Promise<Place> {
  const res = await apiFetch(`/api/places/${placeId}/day`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ dayNumber }),
  });
  if (!res.ok) {
    throw new Error(`PATCH /api/places/${placeId}/day failed: ${res.status}`);
  }
  return res.json();
}

export async function reorderPlace(placeId: number, direction: "UP" | "DOWN"): Promise<Place> {
  const res = await apiFetch(`/api/places/${placeId}/order`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ direction }),
  });
  if (!res.ok) {
    throw new Error(`PATCH /api/places/${placeId}/order failed: ${res.status}`);
  }
  return res.json();
}

export async function optimizeRoute(jobId: number, day: number): Promise<Place[]> {
  const res = await apiFetch(`/api/places/videos/${jobId}/days/${day}/optimize-route`, { method: "POST" });
  if (!res.ok) {
    throw new Error(`POST /api/places/videos/${jobId}/days/${day}/optimize-route failed: ${res.status}`);
  }
  return res.json();
}
```

- [ ] **Step 3: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음(`PlaceDetailScreen.tsx`/`MapScreen.tsx`가 아직
`Place`를 쓰고 있지만 필드 추가는 기존 사용처를 깨지 않는다)

- [ ] **Step 4: 커밋**

```bash
git add src/lib/api/places.ts
git commit -m "feat: 장소 API에 일정 관련 필드와 함수 추가"
```

---

### Task 4: `trips.ts` 신규 작성

**Files:**
- Create: `src/lib/api/trips.ts`

**Interfaces:**
- Consumes: `apiFetch`
- Produces: `Trip`, `TripPlace`, `TripDay`, `TripDetail` 타입.
  `confirmTrip`, `createTrip`, `listTrips`, `getTrip`, `deleteTrip`,
  `addTripPlace`, `removeTripPlace`, `reorderTripPlace`, `checkWeather`,
  `updateTripPlaceDetails`

- [ ] **Step 1: 구현**

`trova-frontend`의 `src/lib/api/trips.ts`를 포팅하되, 모든 호출을
`apiFetch`로 바꾼다(원본은 `fetch(url, {credentials:"include"})`를
직접 쓰지만 모바일은 Bearer 토큰이라 그대로 베끼면 안 됨):

```ts
import { apiFetch } from "@/lib/api/client";

export type Trip = { id: number; title: string; startDate: string | null; endDate: string | null };

export type TripPlace = {
  id: number;
  placeName: string;
  region: string | null;
  category: string | null;
  latitude: number | null;
  longitude: number | null;
  phone: string | null;
  address: string | null;
  visitOrder: number;
  source: "VIDEO" | "NORMAL";
  googlePlaceId: string | null;
  visitStartTime: string | null;
  visitEndTime: string | null;
  arrivalTransportMode: "WALK" | "TRANSIT" | "CAR" | null;
  memo: string | null;
};

export type TripDay = { id: number; day: number; date: string | null; places: TripPlace[] };
export type TripDetail = Trip & { days: TripDay[] };

export async function confirmTrip(jobId: number, title: string, startDate: string | null): Promise<Trip> {
  const res = await apiFetch(`/api/places/videos/${jobId}/confirm-trip`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ title, startDate }),
  });
  if (!res.ok) {
    throw new Error(`POST /api/places/videos/${jobId}/confirm-trip failed: ${res.status}`);
  }
  return res.json();
}

export async function createTrip(title: string, startDate: string, endDate: string): Promise<Trip> {
  const res = await apiFetch(`/api/trips`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ title, startDate, endDate }),
  });
  if (!res.ok) {
    throw new Error(`POST /api/trips failed: ${res.status}`);
  }
  return res.json();
}

export async function listTrips(): Promise<Trip[]> {
  const res = await apiFetch(`/api/trips`);
  if (!res.ok) {
    throw new Error(`GET /api/trips failed: ${res.status}`);
  }
  return res.json();
}

export async function getTrip(id: number): Promise<TripDetail> {
  const res = await apiFetch(`/api/trips/${id}`);
  if (!res.ok) {
    throw new Error(`GET /api/trips/${id} failed: ${res.status}`);
  }
  return res.json();
}

export async function deleteTrip(id: number): Promise<void> {
  const res = await apiFetch(`/api/trips/${id}`, { method: "DELETE" });
  if (!res.ok) {
    throw new Error(`DELETE /api/trips/${id} failed: ${res.status}`);
  }
}

export async function addTripPlace(tripId: number, day: number, googlePlaceId: string): Promise<TripPlace> {
  const res = await apiFetch(`/api/trips/${tripId}/days/${day}/places`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ googlePlaceId }),
  });
  if (!res.ok) {
    throw new Error(`POST /api/trips/${tripId}/days/${day}/places failed: ${res.status}`);
  }
  return res.json();
}

export async function removeTripPlace(id: number): Promise<void> {
  const res = await apiFetch(`/api/trip-places/${id}`, { method: "DELETE" });
  if (!res.ok) {
    throw new Error(`DELETE /api/trip-places/${id} failed: ${res.status}`);
  }
}

export async function reorderTripPlace(id: number, direction: "UP" | "DOWN"): Promise<TripPlace> {
  const res = await apiFetch(`/api/trip-places/${id}/order`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ direction }),
  });
  if (!res.ok) {
    throw new Error(`PATCH /api/trip-places/${id}/order failed: ${res.status}`);
  }
  return res.json();
}

export async function checkWeather(tripId: number, day: number): Promise<{ notified: boolean; message: string }> {
  const res = await apiFetch(`/api/trips/${tripId}/days/${day}/weather-check`, { method: "POST" });
  if (!res.ok) {
    throw new Error(`POST /api/trips/${tripId}/days/${day}/weather-check failed: ${res.status}`);
  }
  return res.json();
}

export async function updateTripPlaceDetails(
  id: number,
  patch: {
    visitStartTime?: string;
    visitEndTime?: string;
    arrivalTransportMode?: "WALK" | "TRANSIT" | "CAR";
    memo?: string;
  }
): Promise<TripPlace> {
  const res = await apiFetch(`/api/trip-places/${id}/details`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(patch),
  });
  if (!res.ok) {
    throw new Error(`PATCH /api/trip-places/${id}/details failed: ${res.status}`);
  }
  return res.json();
}
```

- [ ] **Step 2: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add src/lib/api/trips.ts
git commit -m "feat: 여행 API 클라이언트 추가"
```

---

### Task 5: `recommendations.ts` 신규 작성

**Files:**
- Create: `src/lib/api/recommendations.ts`

**Interfaces:**
- Consumes: `apiFetch`
- Produces: `RecommendedPlace`, `PlaceReviewSummary` 타입.
  `searchPlaces(query) -> Promise<RecommendedPlace[]>`,
  `getPlaceDetails(id) -> Promise<PlaceReviewSummary>`

- [ ] **Step 1: 구현**

`trova-frontend`의 `src/lib/api/recommendations.ts`에서
`searchPlaces`/`getPlaceDetails`(와 그 타입들)만 포팅한다.
위치 기반 `recommend()`는 이번 스코프에서 쓰이지 않으므로 옮기지
않는다(YAGNI). 원본 타입 이름 `PlaceDetail`은 이 프로젝트의
`src/lib/api/places.ts`가 이미 쓰는 `Place`와 헷갈리므로
`PlaceReviewSummary`로 이름을 바꾼다:

```ts
import { apiFetch } from "@/lib/api/client";

export type RecommendedPlace = {
  id: number;
  googlePlaceId: string;
  name: string;
  category: string | null;
  mood: string | null;
  space: string | null;
  rating: number | null;
  userRatingCount: number | null;
  priceLevel: string | null;
  latitude: number | null;
  longitude: number | null;
  address: string | null;
};

export async function searchPlaces(query: string): Promise<RecommendedPlace[]> {
  const res = await apiFetch(`/api/places/search?query=${encodeURIComponent(query)}`);
  if (!res.ok) {
    throw new Error(`GET /api/places/search failed: ${res.status}`);
  }
  return res.json();
}

export type PlaceReviewSummary = Omit<RecommendedPlace, "mood" | "space"> & {
  highlights: string;
  pros: string[];
  cons: string[];
  hours: string | null;
  fee: string | null;
  tips: string[];
  checklist: string[];
  reviewSnippets: string[];
};

export async function getPlaceDetails(id: number): Promise<PlaceReviewSummary> {
  const res = await apiFetch(`/api/places/${id}/details`);
  if (!res.ok) {
    throw new Error(`GET /api/places/${id}/details failed: ${res.status}`);
  }
  return res.json();
}
```

- [ ] **Step 2: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add src/lib/api/recommendations.ts
git commit -m "feat: 장소 검색/리뷰 요약 API 클라이언트 추가"
```

---

### Task 6: `bookmarks.ts` 신규 작성

**Files:**
- Create: `src/lib/api/bookmarks.ts`

**Interfaces:**
- Consumes: `apiFetch`
- Produces: `Bookmark` 타입. `listBookmarks() -> Promise<Bookmark[]>`,
  `addBookmark(placeId) -> Promise<Bookmark>`,
  `removeBookmark(id) -> Promise<void>`

- [ ] **Step 1: 구현**

```ts
import { apiFetch } from "@/lib/api/client";

export type Bookmark = {
  id: number;
  placeId: number;
  placeName: string;
  googlePlaceId: string;
  mood: string | null;
  space: string | null;
  latitude: number | null;
  longitude: number | null;
  createdAt: string;
};

export async function listBookmarks(): Promise<Bookmark[]> {
  const res = await apiFetch(`/api/bookmarks`);
  if (!res.ok) {
    throw new Error(`GET /api/bookmarks failed: ${res.status}`);
  }
  return res.json();
}

export async function addBookmark(placeId: number): Promise<Bookmark> {
  const res = await apiFetch(`/api/bookmarks`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ placeId }),
  });
  if (!res.ok) {
    throw new Error(`POST /api/bookmarks failed: ${res.status}`);
  }
  return res.json();
}

export async function removeBookmark(id: number): Promise<void> {
  const res = await apiFetch(`/api/bookmarks/${id}`, { method: "DELETE" });
  if (!res.ok) {
    throw new Error(`DELETE /api/bookmarks/${id} failed: ${res.status}`);
  }
}
```

- [ ] **Step 2: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add src/lib/api/bookmarks.ts
git commit -m "feat: 찜한 장소 API 클라이언트 추가"
```

---

### Task 7: 날짜/시간 선택 의존성 설치 + 포맷 유틸

**Files:**
- Modify: `package.json` (via `npx expo install`)
- Create: `src/lib/date.ts`

**Interfaces:**
- Produces: `toDateString(date: Date) -> string`("YYYY-MM-DD"),
  `toTimeString(date: Date) -> string`("HH:MM"),
  `parseTimeToDate(hhmm: string | null) -> Date`(파싱 실패/없음 시
  현재 시각 반환)

- [ ] **Step 1: 라이브러리 설치**

Run: `npx expo install @react-native-community/datetimepicker`

Expected: `package.json`의 `dependencies`에
`@react-native-community/datetimepicker`가 Expo SDK 57과 호환되는
버전으로 추가됨.

- [ ] **Step 2: 날짜/시간 포맷 유틸 작성**

`Date.toISOString()`은 UTC 기준이라 자정 근처에서 한국 시간과 날짜가
어긋날 수 있으므로, 로컬 타임존 기준으로 직접 포맷한다:

```ts
function pad(n: number): string {
  return String(n).padStart(2, "0");
}

export function toDateString(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

export function toTimeString(date: Date): string {
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function parseTimeToDate(hhmm: string | null): Date {
  const base = new Date();
  if (!hhmm) return base;
  const [hours, minutes] = hhmm.split(":").map(Number);
  if (Number.isNaN(hours) || Number.isNaN(minutes)) return base;
  base.setHours(hours, minutes, 0, 0);
  return base;
}
```

- [ ] **Step 3: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add package.json package-lock.json src/lib/date.ts
git commit -m "chore: 날짜/시간 선택 라이브러리 설치 및 포맷 유틸 추가"
```

(`package-lock.json`이 없고 다른 락파일을 쓰는 경우 실제 락파일
이름으로 대체한다.)

---

### Task 8: `InlineMap` 컴포넌트

**Files:**
- Create: `src/components/InlineMap.tsx`

**Interfaces:**
- Consumes: `buildKakaoMapHtml`(기존, `src/lib/kakaoMapHtml.ts` —
  수정 없이 그대로 재사용, 이미 여러 핀 렌더링을 지원함)
- Produces: `<InlineMap pins={{id, latitude, longitude}[]} height?={number} />`

기존 `MapScreen.tsx`(단일 장소 전용, 이번 계획에서 삭제 예정)의
WebView 로직을 일반화한다.

- [ ] **Step 1: 구현**

```tsx
import { useEffect, useRef, useState } from "react";
import { View } from "react-native";
import { WebView, type WebViewMessageEvent } from "react-native-webview";
import { AppText } from "@/components/AppText";
import { buildKakaoMapHtml } from "@/lib/kakaoMapHtml";

const KAKAO_MAP_JS_KEY = process.env.EXPO_PUBLIC_KAKAO_MAP_JS_KEY ?? "";

type Pin = { id: string; latitude: number; longitude: number };

export function InlineMap({ pins, height = 200 }: { pins: Pin[]; height?: number }) {
  const webviewRef = useRef<WebView>(null);
  const [isMapLoaded, setIsMapLoaded] = useState(false);
  const [mapError, setMapError] = useState<string | null>(null);

  useEffect(() => {
    if (!isMapLoaded || mapError || pins.length === 0) return;
    webviewRef.current?.postMessage(JSON.stringify(pins));
  }, [isMapLoaded, mapError, pins]);

  function handleMessage(event: WebViewMessageEvent) {
    try {
      const data = JSON.parse(event.nativeEvent.data);
      if (data?.type === "sdk-load-error" || data?.type === "render-error") {
        setMapError("지도를 불러오지 못했어요.");
      }
    } catch {
      // 무시 — 지도 쪽에서 보낸 다른 형식의 메시지일 수 있음
    }
  }

  if (pins.length === 0) return null;

  if (!KAKAO_MAP_JS_KEY) {
    return (
      <View style={{ height, justifyContent: "center", alignItems: "center", backgroundColor: "#F5F5F0" }}>
        <AppText style={{ color: "#8C8C86" }}>지도 키가 설정되지 않았어요.</AppText>
      </View>
    );
  }

  if (mapError) {
    return (
      <View style={{ height, justifyContent: "center", alignItems: "center", backgroundColor: "#F5F5F0" }}>
        <AppText style={{ color: "#8C8C86" }}>{mapError}</AppText>
      </View>
    );
  }

  return (
    <WebView
      ref={webviewRef}
      source={{ html: buildKakaoMapHtml(KAKAO_MAP_JS_KEY), baseUrl: "https://localhost" }}
      style={{ height, borderRadius: 12, overflow: "hidden" }}
      onLoadEnd={() => setIsMapLoaded(true)}
      onMessage={handleMessage}
      onError={() => {
        setIsMapLoaded(false);
        setMapError("지도를 불러오지 못했어요.");
      }}
      onHttpError={() => {
        setIsMapLoaded(false);
        setMapError("지도를 불러오지 못했어요.");
      }}
    />
  );
}
```

- [ ] **Step 2: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음(아직 아무 화면도 이 컴포넌트를 쓰지 않으므로
시각적 확인은 Task 12에서 진행)

- [ ] **Step 3: 커밋**

```bash
git add src/components/InlineMap.tsx
git commit -m "feat: 여러 핀을 표시하는 인라인 지도 컴포넌트 추가"
```

---

### Task 9: `PlaceRow` 컴포넌트

**Files:**
- Create: `src/components/PlaceRow.tsx`

**Interfaces:**
- Consumes: `haversineDistanceKm`(Task 1), `AppText`
- Produces: `<PlaceRow place index isLast distanceKm editable? disabled?
  onMoveUp? onMoveDown? onOpenDayPicker? color? children? />`

웹 `PlaceMapSection`의 행 렌더링(번호 원, 카드, 위/아래 버튼, 요일
이동 버튼, 다음 장소까지 거리)에 대응. `children`은 `TripDetailScreen`
(Task 16)이 삭제 버튼/방문시간/이동수단/메모 편집 UI를 아래에 끼워
넣기 위한 슬롯이다.

- [ ] **Step 1: 구현**

```tsx
import type { ReactNode } from "react";
import { Pressable, View } from "react-native";
import { AppText } from "@/components/AppText";

type PlaceRowItem = {
  id: number;
  placeName: string;
  address: string | null;
};

type PlaceRowProps = {
  place: PlaceRowItem;
  index: number;
  isLast: boolean;
  distanceKm: number | null;
  editable?: boolean;
  disabled?: boolean;
  onMoveUp?: () => void;
  onMoveDown?: () => void;
  onOpenDayPicker?: () => void;
  color?: string;
  children?: ReactNode;
};

export function PlaceRow({
  place,
  index,
  isLast,
  distanceKm,
  editable = false,
  disabled = false,
  onMoveUp,
  onMoveDown,
  onOpenDayPicker,
  color = "#FF6B4A",
  children,
}: PlaceRowProps) {
  return (
    <View style={{ gap: 4 }}>
      <View
        style={{
          flexDirection: "row",
          gap: 12,
          padding: 12,
          borderRadius: 12,
          borderWidth: 1,
          borderColor: "#DEDED8",
          backgroundColor: "#fff",
        }}
      >
        <View
          style={{
            width: 26,
            height: 26,
            borderRadius: 13,
            backgroundColor: color,
            justifyContent: "center",
            alignItems: "center",
          }}
        >
          <AppText weight="medium" style={{ color: "#fff", fontSize: 12 }}>
            {index + 1}
          </AppText>
        </View>
        <View style={{ flex: 1, gap: 6 }}>
          <View>
            <AppText weight="medium" numberOfLines={1}>
              {place.placeName}
            </AppText>
            {place.address && (
              <AppText style={{ fontSize: 12, color: "#8C8C86" }} numberOfLines={1}>
                {place.address}
              </AppText>
            )}
          </View>
          {editable && (
            <View style={{ flexDirection: "row", alignItems: "center", gap: 12 }}>
              {(onMoveUp || onMoveDown) && (
                <View style={{ flexDirection: "row", gap: 6 }}>
                  <Pressable
                    onPress={onMoveUp}
                    disabled={disabled || index === 0 || !onMoveUp}
                    style={{ opacity: disabled || index === 0 ? 0.3 : 1 }}
                  >
                    <AppText style={{ fontSize: 13, color: "#8C8C86" }}>↑</AppText>
                  </Pressable>
                  <Pressable
                    onPress={onMoveDown}
                    disabled={disabled || isLast || !onMoveDown}
                    style={{ opacity: disabled || isLast ? 0.3 : 1 }}
                  >
                    <AppText style={{ fontSize: 13, color: "#8C8C86" }}>↓</AppText>
                  </Pressable>
                </View>
              )}
              {onOpenDayPicker && (
                <Pressable onPress={onOpenDayPicker} disabled={disabled}>
                  <AppText style={{ fontSize: 13, color: "#8C8C86" }}>다른 날로 이동</AppText>
                </Pressable>
              )}
            </View>
          )}
          {children}
        </View>
      </View>
      {!isLast && distanceKm !== null && (
        <AppText style={{ fontSize: 11, color: "#8C8C86", marginLeft: 38 }}>
          다음 장소까지 {distanceKm.toFixed(1)}km
        </AppText>
      )}
    </View>
  );
}
```

- [ ] **Step 2: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add src/components/PlaceRow.tsx
git commit -m "feat: 장소 카드 행 컴포넌트 추가"
```

---

### Task 10: `DayPickerSheet` 컴포넌트

**Files:**
- Create: `src/components/DayPickerSheet.tsx`

**Interfaces:**
- Produces: `<DayPickerSheet visible dayNumbers={number[]} currentDay={number
  | null} onSelect={(day: number) => void} onClose={() => void} />`

웹의 `<select>` 요일 이동 드롭다운을, RN에는 네이티브 select가 없으므로
모달 + 버튼 리스트로 구현한다.

- [ ] **Step 1: 구현**

```tsx
import { Modal, Pressable, View } from "react-native";
import { AppText } from "@/components/AppText";

type DayPickerSheetProps = {
  visible: boolean;
  dayNumbers: number[];
  currentDay: number | null;
  onSelect: (day: number) => void;
  onClose: () => void;
};

export function DayPickerSheet({ visible, dayNumbers, currentDay, onSelect, onClose }: DayPickerSheetProps) {
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <Pressable
        style={{ flex: 1, backgroundColor: "rgba(0,0,0,0.3)", justifyContent: "flex-end" }}
        onPress={onClose}
      >
        <Pressable
          style={{ backgroundColor: "#fff", borderTopLeftRadius: 16, borderTopRightRadius: 16, padding: 16, gap: 4 }}
          onPress={(e) => e.stopPropagation()}
        >
          <AppText weight="medium" style={{ fontSize: 15, marginBottom: 8 }}>
            어느 날로 옮길까요?
          </AppText>
          {dayNumbers.map((day) => (
            <Pressable
              key={day}
              onPress={() => {
                onSelect(day);
                onClose();
              }}
              disabled={day === currentDay}
              style={{
                paddingVertical: 12,
                paddingHorizontal: 8,
                borderRadius: 8,
                backgroundColor: day === currentDay ? "#F5F5F0" : "transparent",
              }}
            >
              <AppText style={{ color: day === currentDay ? "#8C8C86" : "#000" }}>
                {day}일차{day === currentDay ? " (현재)" : ""}
              </AppText>
            </Pressable>
          ))}
        </Pressable>
      </Pressable>
    </Modal>
  );
}
```

- [ ] **Step 2: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add src/components/DayPickerSheet.tsx
git commit -m "feat: 요일 이동 선택 모달 컴포넌트 추가"
```

---

### Task 11: `PlaceReviewModal` 컴포넌트

**Files:**
- Create: `src/components/PlaceReviewModal.tsx`

**Interfaces:**
- Consumes: `getPlaceDetails`(Task 5)
- Produces: `<PlaceReviewModal visible placeId={number | null} onClose={() =>
  void} />`

웹 `TripDetailView`의 리뷰 요약 펼침 영역(하이라이트/장단점/운영시간/
요금/팁/체크리스트/원문 리뷰 토글)을 모달로 이식한다.
`renderHighlightedText`(웹 `src/lib/richText.tsx`)의 `**굵게**` 파싱도
함께 이식한다.

- [ ] **Step 1: 구현**

```tsx
import { useState } from "react";
import { Modal, Pressable, ScrollView, View } from "react-native";
import { useQuery } from "@tanstack/react-query";
import { AppText } from "@/components/AppText";
import { getPlaceDetails } from "@/lib/api/recommendations";

function HighlightedText({ text }: { text: string }) {
  const parts = text.split(/\*\*(.+?)\*\*/g);
  return (
    <AppText style={{ fontSize: 13, lineHeight: 19 }}>
      {parts.map((part, i) =>
        i % 2 === 1 ? (
          <AppText key={i} weight="medium" style={{ backgroundColor: "#FFF1EC" }}>
            {part}
          </AppText>
        ) : (
          part
        )
      )}
    </AppText>
  );
}

export function PlaceReviewModal({
  visible,
  placeId,
  onClose,
}: {
  visible: boolean;
  placeId: number | null;
  onClose: () => void;
}) {
  const [showRawReviews, setShowRawReviews] = useState(false);
  const detailQuery = useQuery({
    queryKey: ["placeDetails", placeId],
    queryFn: () => getPlaceDetails(placeId as number),
    enabled: placeId !== null,
  });
  const detail = detailQuery.data;

  return (
    <Modal
      visible={visible}
      transparent
      animationType="slide"
      onRequestClose={onClose}
      onDismiss={() => setShowRawReviews(false)}
    >
      <Pressable style={{ flex: 1, backgroundColor: "rgba(0,0,0,0.3)", justifyContent: "flex-end" }} onPress={onClose}>
        <Pressable
          style={{ maxHeight: "75%", backgroundColor: "#fff", borderTopLeftRadius: 16, borderTopRightRadius: 16 }}
          onPress={(e) => e.stopPropagation()}
        >
          <ScrollView contentContainerStyle={{ padding: 20, gap: 12 }}>
            {detailQuery.isLoading || !detail ? (
              <AppText style={{ color: "#8C8C86" }}>리뷰 요약을 불러오는 중...</AppText>
            ) : (
              <>
                <AppText weight="medium" style={{ fontSize: 17 }}>
                  {detail.name}
                </AppText>
                <HighlightedText text={detail.highlights} />

                {(detail.pros.length > 0 || detail.cons.length > 0) && (
                  <View style={{ flexDirection: "row", gap: 16 }}>
                    {detail.pros.length > 0 && (
                      <View style={{ flex: 1, gap: 2 }}>
                        <AppText weight="medium" style={{ fontSize: 11, color: "#8C8C86" }}>
                          👍 좋은 점
                        </AppText>
                        {detail.pros.map((p, i) => (
                          <AppText key={i} style={{ fontSize: 12 }}>
                            {p}
                          </AppText>
                        ))}
                      </View>
                    )}
                    {detail.cons.length > 0 && (
                      <View style={{ flex: 1, gap: 2 }}>
                        <AppText weight="medium" style={{ fontSize: 11, color: "#8C8C86" }}>
                          👎 아쉬운 점
                        </AppText>
                        {detail.cons.map((c, i) => (
                          <AppText key={i} style={{ fontSize: 12 }}>
                            {c}
                          </AppText>
                        ))}
                      </View>
                    )}
                  </View>
                )}

                {(detail.hours || detail.fee) && (
                  <View style={{ gap: 2 }}>
                    {detail.hours && <AppText style={{ fontSize: 12 }}>🕐 {detail.hours}</AppText>}
                    {detail.fee && <AppText style={{ fontSize: 12 }}>💰 {detail.fee}</AppText>}
                  </View>
                )}

                {detail.tips.length > 0 && (
                  <View style={{ padding: 10, borderRadius: 8, backgroundColor: "#FFF1EC", gap: 2 }}>
                    <AppText weight="medium" style={{ fontSize: 11, color: "#FF6B4A" }}>
                      💡 꿀팁
                    </AppText>
                    {detail.tips.map((tip, i) => (
                      <AppText key={i} style={{ fontSize: 12 }}>
                        {tip}
                      </AppText>
                    ))}
                  </View>
                )}

                {detail.checklist.length > 0 && (
                  <View style={{ gap: 2 }}>
                    {detail.checklist.map((item, i) => (
                      <AppText key={i} style={{ fontSize: 12 }}>
                        ☐ {item}
                      </AppText>
                    ))}
                  </View>
                )}

                {detail.reviewSnippets.length > 0 && (
                  <View style={{ gap: 4 }}>
                    <Pressable onPress={() => setShowRawReviews((current) => !current)}>
                      <AppText style={{ fontSize: 11, color: "#8C8C86" }}>
                        {showRawReviews ? "실제 리뷰 원문 접기 ▲" : "실제 리뷰 원문 보기 ▼"}
                      </AppText>
                    </Pressable>
                    {showRawReviews &&
                      detail.reviewSnippets.slice(0, 3).map((snippet, i) => (
                        <AppText
                          key={i}
                          style={{ fontSize: 12, color: "#8C8C86", borderLeftWidth: 2, borderLeftColor: "#DEDED8", paddingLeft: 8 }}
                        >
                          &ldquo;{snippet}&rdquo;
                        </AppText>
                      ))}
                  </View>
                )}
              </>
            )}
          </ScrollView>
        </Pressable>
      </Pressable>
    </Modal>
  );
}
```

- [ ] **Step 2: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add src/components/PlaceReviewModal.tsx
git commit -m "feat: AI 리뷰 요약 모달 컴포넌트 추가"
```

---

### Task 12: 네비게이션 개편 + `VideoGroupScreen`(기본) — `PlaceDetail`/`Map` 대체

가장 핵심적인 태스크다: 오늘 사용자가 보고한 "추출 후 저장한 장소로
가버리는" 문제를 여기서 고친다.

**Files:**
- Modify: `src/navigation/types.ts`
- Modify: `src/navigation/RootNavigator.tsx`
- Modify: `src/screens/ProcessingScreen.tsx`
- Modify: `src/screens/PlacesListScreen.tsx`
- Delete: `src/screens/PlaceDetailScreen.tsx`
- Delete: `src/screens/MapScreen.tsx`
- Create: `src/screens/VideoGroupScreen.tsx`

**Interfaces:**
- Consumes: `InlineMap`(Task 8), `PlaceRow`(Task 9), `getPlaces`/
  `generateItinerary`(Task 3), `isItineraryGroup`(Task 2)
- Produces: `<VideoGroupScreen route navigation>` —
  `route.params.jobId`로 진입, 일정이 없으면 "일정 짜기" 버튼으로
  `navigation.navigate("Processing", {jobId})`. 일정 편집 UI는
  Task 13에서 이 파일에 추가된다.

현재 `src/navigation/types.ts` 전체:

```ts
export type RootStackParamList = {
  Login: undefined;
  Home: undefined;
  Processing: { jobId: number };
  PlacesList: undefined;
  PlaceDetail: { id: number };
  Map: { id: number };
};
```

- [ ] **Step 1: `RootStackParamList` 수정**

```ts
export type RootStackParamList = {
  Login: undefined;
  Home: undefined;
  Processing: { jobId: number };
  PlacesList: undefined;
  VideoGroup: { jobId: number };
};
```

(`PlaceDetail`/`Map` 제거. `TripsList`/`NewTrip`/`TripDetail`은 각
화면이 만들어지는 Task 14/15/16에서 추가한다.)

- [ ] **Step 2: `VideoGroupScreen.tsx` 작성(기본 버전)**

```tsx
import { useState } from "react";
import { ScrollView, View } from "react-native";
import { useQuery } from "@tanstack/react-query";
import { AppText } from "@/components/AppText";
import { InlineMap } from "@/components/InlineMap";
import { PlaceRow } from "@/components/PlaceRow";
import { haversineDistanceKm } from "@/lib/geo";
import { generateItinerary, getPlaces } from "@/lib/api/places";
import { isItineraryGroup } from "@/lib/itinerary";
import type { NativeStackScreenProps } from "@react-navigation/native-stack";
import type { RootStackParamList } from "@/navigation/types";

type Props = NativeStackScreenProps<RootStackParamList, "VideoGroup">;

export function VideoGroupScreen({ route, navigation }: Props) {
  const { jobId } = route.params;
  const [generating, setGenerating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const placesQuery = useQuery({ queryKey: ["places"], queryFn: getPlaces });
  const group = (placesQuery.data ?? []).filter((p) => p.jobId === jobId);

  async function handleGenerateItinerary() {
    if (group.length === 0 || generating) return;
    setGenerating(true);
    setError(null);
    try {
      await generateItinerary(jobId);
      navigation.navigate("Processing", { jobId });
    } catch {
      setError("일정 생성 요청에 실패했어요. 다시 시도해주세요.");
      setGenerating(false);
    }
  }

  if (placesQuery.isLoading) {
    return (
      <View style={{ flex: 1, justifyContent: "center", alignItems: "center" }}>
        <AppText>불러오는 중...</AppText>
      </View>
    );
  }

  if (group.length === 0) {
    return (
      <View style={{ flex: 1, justifyContent: "center", alignItems: "center" }}>
        <AppText>해당 영상을 찾을 수 없어요.</AppText>
      </View>
    );
  }

  const title = group.find((p) => p.title)?.title ?? "제목 없음";

  // Task 13에서 isItineraryGroup(group)이 true인 경우를 일자별 편집
  // UI로 교체한다. 지금은 두 경우 모두 같은 평면 리스트로 보여준다 —
  // 일정이 있으면 dayNumber/orderInDay 순, 없으면 API가 준 순서 그대로.
  const ordered = isItineraryGroup(group)
    ? [...group].sort((a, b) => {
        if (a.dayNumber !== b.dayNumber) return (a.dayNumber ?? 0) - (b.dayNumber ?? 0);
        return (a.orderInDay ?? 0) - (b.orderInDay ?? 0);
      })
    : group;

  return (
    <ScrollView contentContainerStyle={{ padding: 16, gap: 12 }}>
      <AppText weight="medium" style={{ fontSize: 18 }} numberOfLines={2}>
        {title}
      </AppText>

      <InlineMap
        pins={ordered
          .filter((p) => p.latitude !== null && p.longitude !== null)
          .map((p) => ({ id: String(p.id), latitude: p.latitude as number, longitude: p.longitude as number }))}
      />

      {!isItineraryGroup(group) && (
        <View>
          <View
            style={{
              height: 48,
              borderRadius: 12,
              backgroundColor: "#FF6B4A",
              justifyContent: "center",
              alignItems: "center",
              opacity: generating ? 0.6 : 1,
            }}
          >
            <AppText
              weight="medium"
              style={{ color: "#fff" }}
              onPress={handleGenerateItinerary}
            >
              {generating ? "일정 생성 중..." : "일정 짜기"}
            </AppText>
          </View>
          {error && <AppText style={{ marginTop: 8, color: "#FF6B4A" }}>{error}</AppText>}
        </View>
      )}

      <View style={{ gap: 12 }}>
        {ordered.map((place, index) => {
          const isLast = index === ordered.length - 1;
          const next = ordered[index + 1];
          const distanceKm =
            !isLast &&
            place.latitude !== null &&
            place.longitude !== null &&
            next?.latitude !== null &&
            next?.longitude !== null
              ? haversineDistanceKm(place.latitude, place.longitude, next!.latitude!, next!.longitude!)
              : null;
          return (
            <PlaceRow key={place.id} place={place} index={index} isLast={isLast} distanceKm={distanceKm} />
          );
        })}
      </View>
    </ScrollView>
  );
}
```

(`onPress`를 `<AppText>`에 직접 얹은 것은 이 파일 안에서 버튼 스타일을
빠르게 재사용하기 위함이다 — 실제 탭 영역은 `Pressable`로 감싸는 게
더 안전하므로, 아래처럼 `Pressable`로 감싸도록 수정한다:)

- [ ] **Step 2-1: "일정 짜기" 버튼을 `Pressable`로 감싸기**

위 코드의 버튼 부분을 아래로 교체한다(`Pressable` import 추가):

```tsx
import { Pressable, ScrollView, View } from "react-native";
```

```tsx
      {!isItineraryGroup(group) && (
        <View>
          <Pressable
            onPress={handleGenerateItinerary}
            disabled={generating}
            style={{
              height: 48,
              borderRadius: 12,
              backgroundColor: "#FF6B4A",
              justifyContent: "center",
              alignItems: "center",
              opacity: generating ? 0.6 : 1,
            }}
          >
            <AppText weight="medium" style={{ color: "#fff" }}>
              {generating ? "일정 생성 중..." : "일정 짜기"}
            </AppText>
          </Pressable>
          {error && <AppText style={{ marginTop: 8, color: "#FF6B4A" }}>{error}</AppText>}
        </View>
      )}
```

- [ ] **Step 3: `RootNavigator.tsx` 수정**

현재 파일:

```tsx
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { useAuth } from "@/lib/auth/AuthContext";
import { LoginScreen } from "@/screens/LoginScreen";
import { HomeScreen } from "@/screens/HomeScreen";
import { ProcessingScreen } from "@/screens/ProcessingScreen";
import { PlacesListScreen } from "@/screens/PlacesListScreen";
import { PlaceDetailScreen } from "@/screens/PlaceDetailScreen";
import { MapScreen } from "@/screens/MapScreen";
import { AppText } from "@/components/AppText";
import { View } from "react-native";
import type { RootStackParamList } from "@/navigation/types";

export type { RootStackParamList };

const Stack = createNativeStackNavigator<RootStackParamList>();

export function RootNavigator() {
  const { user, loading } = useAuth();

  if (loading) {
    return (
      <View style={{ flex: 1, justifyContent: "center", alignItems: "center" }}>
        <AppText>불러오는 중...</AppText>
      </View>
    );
  }

  return (
    <Stack.Navigator screenOptions={{ headerTitleAlign: "center" }}>
      {!user ? (
        <Stack.Screen name="Login" component={LoginScreen} options={{ headerShown: false }} />
      ) : (
        <>
          <Stack.Screen name="Home" component={HomeScreen} options={{ title: "Trova" }} />
          <Stack.Screen name="Processing" component={ProcessingScreen} options={{ headerShown: false }} />
          <Stack.Screen name="PlacesList" component={PlacesListScreen} options={{ title: "저장한 장소" }} />
          <Stack.Screen name="PlaceDetail" component={PlaceDetailScreen} options={{ title: "장소 상세" }} />
          <Stack.Screen name="Map" component={MapScreen} options={{ title: "지도" }} />
        </>
      )}
    </Stack.Navigator>
  );
}
```

`PlaceDetailScreen`/`MapScreen` import와 등록을 제거하고
`VideoGroupScreen`으로 교체:

```tsx
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { useAuth } from "@/lib/auth/AuthContext";
import { LoginScreen } from "@/screens/LoginScreen";
import { HomeScreen } from "@/screens/HomeScreen";
import { ProcessingScreen } from "@/screens/ProcessingScreen";
import { PlacesListScreen } from "@/screens/PlacesListScreen";
import { VideoGroupScreen } from "@/screens/VideoGroupScreen";
import { AppText } from "@/components/AppText";
import { View } from "react-native";
import type { RootStackParamList } from "@/navigation/types";

export type { RootStackParamList };

const Stack = createNativeStackNavigator<RootStackParamList>();

export function RootNavigator() {
  const { user, loading } = useAuth();

  if (loading) {
    return (
      <View style={{ flex: 1, justifyContent: "center", alignItems: "center" }}>
        <AppText>불러오는 중...</AppText>
      </View>
    );
  }

  return (
    <Stack.Navigator screenOptions={{ headerTitleAlign: "center" }}>
      {!user ? (
        <Stack.Screen name="Login" component={LoginScreen} options={{ headerShown: false }} />
      ) : (
        <>
          <Stack.Screen name="Home" component={HomeScreen} options={{ title: "Trova" }} />
          <Stack.Screen name="Processing" component={ProcessingScreen} options={{ headerShown: false }} />
          <Stack.Screen name="PlacesList" component={PlacesListScreen} options={{ title: "저장한 장소" }} />
          <Stack.Screen name="VideoGroup" component={VideoGroupScreen} options={{ title: "영상 속 장소" }} />
        </>
      )}
    </Stack.Navigator>
  );
}
```

- [ ] **Step 4: 옛 화면 삭제**

```bash
rm src/screens/PlaceDetailScreen.tsx src/screens/MapScreen.tsx
```

- [ ] **Step 5: `ProcessingScreen.tsx` 완료 목적지 변경**

`useEffect`의 다음 부분을 찾는다:

```tsx
  useEffect(() => {
    // FAILED 작업도 /api/places/pending 목록에 남아있으므로, 목록에서 사라졌다는
    // 것은 처리가 끝나 저장까지 완료됐다는 뜻이다.
    if (pendingQuery.isSuccess && !job) {
      navigation.replace("PlacesList");
    }
  }, [pendingQuery.isSuccess, job, navigation]);
```

`navigation.replace("PlacesList")`를 `navigation.replace("VideoGroup", { jobId })`로
바꾼다:

```tsx
  useEffect(() => {
    // FAILED 작업도 /api/places/pending 목록에 남아있으므로, 목록에서 사라졌다는
    // 것은 처리가 끝나 저장까지 완료됐다는 뜻이다. 완료 후에는 항상 이 영상의
    // 그룹 화면으로 이동한다 — 최초 추출과 일정 생성 재요청 둘 다 같은 화면을
    // 거치므로 분기 없이 이 한 줄이면 충분하다.
    if (pendingQuery.isSuccess && !job) {
      navigation.replace("VideoGroup", { jobId });
    }
  }, [pendingQuery.isSuccess, job, jobId, navigation]);
```

FAILED 상태 화면의 "홈으로 돌아가기" 버튼(`navigation.replace("Home")`)은
그대로 둔다 — 실패한 작업은 보여줄 그룹이 없다.

- [ ] **Step 6: `PlacesListScreen.tsx` 행 탭 대상 변경**

`renderItem`의 `Pressable`에서:

```tsx
        <Pressable
          onPress={() => navigation.navigate("PlaceDetail", { id: item.id })}
```

를 다음으로 바꾼다:

```tsx
        <Pressable
          onPress={() => navigation.navigate("VideoGroup", { jobId: item.jobId })}
```

- [ ] **Step 7: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음(`PlaceDetail`/`Map`을 참조하는 곳이 하나도 없어야
함 — `grep -rn "PlaceDetail\|MapScreen" src/`로 한 번 더 확인)

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "feat: 장소 추출 완료 후 영상 그룹 화면으로 이동하도록 변경"
```

- [ ] **Step 9: 시뮬레이터로 수동 확인**

이미 켜져 있는 iOS 시뮬레이터는 Metro와 연결돼 있으므로 저장 시
자동으로 변경분을 받는다(필요하면
`xcrun simctl terminate booted com.trovapp.trova && xcrun simctl launch
booted com.trovapp.trova`로 완전히 재시작해 확실히 새 번들을 받게
한다). 홈에서 새 링크를 제출 → 분석 화면 → 완료 시 "저장한 장소"가
아니라 이 영상의 장소만 보이는 화면으로 도착하는지
`xcrun simctl io booted screenshot <path>.png` 후 Read로 확인한다.

---

### Task 13: `VideoGroupScreen` 일정 편집 UI

**Files:**
- Modify: `src/screens/VideoGroupScreen.tsx`

**Interfaces:**
- Consumes: `groupByDay`(Task 2), `moveToDay`/`reorderPlace`/
  `optimizeRoute`(Task 3), `DayPickerSheet`(Task 10)
- Produces: `isItineraryGroup(group) === true`일 때 일자별 탭 +
  순서변경 + 요일이동 + 동선최적화 + 날짜 추가/삭제 UI. "여행으로
  만들기" 버튼은 Task 18에서 이 파일에 추가된다(TripDetail 라우트가
  아직 없으므로).

Task 12에서 만든 평면 리스트 렌더링 부분(`isItineraryGroup(group)`이
`true`인 경우도 그냥 정렬만 해서 보여주던 부분)을 아래처럼 완전한
일정 편집 UI로 교체한다.

- [ ] **Step 1: 로컬 상태와 파생 데이터 추가**

`export function VideoGroupScreen(...)` 안, 기존 `placesQuery` 선언
바로 아래에 추가:

```tsx
  const [localPlaces, setLocalPlaces] = useState<Place[] | null>(null);
  const [emptyDayNumbers, setEmptyDayNumbers] = useState<number[]>([]);
  const [actionPending, setActionPending] = useState(false);
  const [itineraryError, setItineraryError] = useState<string | null>(null);
  const [dayPickerFor, setDayPickerFor] = useState<Place | null>(null);
  const [activeDay, setActiveDay] = useState<number | null>(null);
```

(React Hooks 규칙상 `useState` 호출은 모두 컴포넌트 최상단에 함께
있어야 하므로, `activeDay`도 다른 `useState` 호출들과 나란히 이
블록에 함께 선언한다 — 아래 파생 데이터 블록에는 `useState` 호출을
넣지 않는다.)

`Place` 타입을 import한다: `import type { Place } from "@/lib/api/places";`
(기존엔 값만 import했다면 타입도 함께: `import { generateItinerary,
getPlaces, type Place } from "@/lib/api/places";`)

서버에서 새로 받은 `group`이 로컬 편집 상태보다 우선하지 않도록,
`localPlaces`가 없을 때만 서버 데이터로 초기화한다. `group` 계산
바로 아래에 파생 데이터를 추가한다(여기엔 `useState`가 없다 — 위에서
이미 선언한 `activeDay`를 읽기만 한다):

```tsx
  const itineraryPlaces = localPlaces ?? group;
  const hasItinerary = isItineraryGroup(itineraryPlaces);
  const days = groupByDay(itineraryPlaces);
  const dayNumbers = Array.from(new Set([...days.keys(), ...emptyDayNumbers])).sort((a, b) => a - b);
  const currentActiveDay = activeDay ?? dayNumbers[0] ?? null;
  const activePlaces = currentActiveDay !== null ? days.get(currentActiveDay) ?? [] : [];
  const unassignedPlaces = itineraryPlaces.filter((p) => p.dayNumber === null);
```

- [ ] **Step 2: 낙관적 업데이트 핸들러 작성**

```tsx
  async function handleMoveDay(place: Place, dayNumber: number) {
    if (actionPending) return;
    setActionPending(true);
    const previous = itineraryPlaces;
    const previousEmptyDays = emptyDayNumbers;
    const sourceDay = place.dayNumber;
    setItineraryError(null);
    setLocalPlaces(previous.map((p) => (p.id === place.id ? { ...p, dayNumber } : p)));
    setEmptyDayNumbers((current) => current.filter((d) => d !== dayNumber));

    const sourceDayNowEmpty =
      sourceDay !== null &&
      sourceDay === currentActiveDay &&
      !previous.some((p) => p.id !== place.id && p.dayNumber === sourceDay);
    if (sourceDayNowEmpty) {
      setActiveDay(dayNumber);
    }

    try {
      const updated = await moveToDay(place.id, dayNumber);
      setLocalPlaces((current) => (current ?? previous).map((p) => (p.id === updated.id ? updated : p)));
    } catch {
      setLocalPlaces(previous);
      setEmptyDayNumbers(previousEmptyDays);
      setItineraryError("장소를 옮기지 못했어요. 다시 시도해주세요.");
    } finally {
      setActionPending(false);
    }
  }

  async function handleReorder(place: Place, direction: "UP" | "DOWN") {
    if (actionPending || place.dayNumber === null) return;
    const dayPlaces = days.get(place.dayNumber) ?? [];
    const index = dayPlaces.findIndex((p) => p.id === place.id);
    const swapIndex = direction === "UP" ? index - 1 : index + 1;
    if (index < 0 || swapIndex < 0 || swapIndex >= dayPlaces.length) return;
    const neighbor = dayPlaces[swapIndex];

    setActionPending(true);
    const previous = itineraryPlaces;
    setItineraryError(null);
    const placeOrder = place.orderInDay;
    const neighborOrder = neighbor.orderInDay;
    setLocalPlaces(
      previous.map((p) => {
        if (p.id === place.id) return { ...p, orderInDay: neighborOrder };
        if (p.id === neighbor.id) return { ...p, orderInDay: placeOrder };
        return p;
      })
    );

    try {
      const updated = await reorderPlace(place.id, direction);
      setLocalPlaces((current) => (current ?? previous).map((p) => (p.id === updated.id ? updated : p)));
    } catch {
      setLocalPlaces(previous);
      setItineraryError("순서를 바꾸지 못했어요. 다시 시도해주세요.");
    } finally {
      setActionPending(false);
    }
  }

  function handleAddDay() {
    const nextDay = (dayNumbers[dayNumbers.length - 1] ?? 0) + 1;
    setEmptyDayNumbers((current) => [...current, nextDay]);
    setActiveDay(nextDay);
  }

  function handleDeleteDay(day: number) {
    setEmptyDayNumbers((current) => current.filter((d) => d !== day));
    if (currentActiveDay === day) {
      setActiveDay(dayNumbers.find((d) => d !== day) ?? null);
    }
  }

  async function handleOptimizeRoute() {
    if (actionPending || activePlaces.length < 2 || currentActiveDay === null) return;
    const jobIdForOptimize = activePlaces[0]?.jobId;
    if (jobIdForOptimize === undefined) return;

    setActionPending(true);
    setItineraryError(null);
    try {
      const updated = await optimizeRoute(jobIdForOptimize, currentActiveDay);
      const updatedById = new Map(updated.map((p) => [p.id, p]));
      setLocalPlaces((current) => (current ?? itineraryPlaces).map((p) => updatedById.get(p.id) ?? p));
    } catch {
      setItineraryError("동선을 최적화하지 못했어요. 다시 시도해주세요.");
    } finally {
      setActionPending(false);
    }
  }
```

`moveToDay`/`reorderPlace`/`optimizeRoute`를 import에 추가:

```tsx
import { generateItinerary, getPlaces, moveToDay, optimizeRoute, reorderPlace, type Place } from "@/lib/api/places";
import { groupByDay, isItineraryGroup } from "@/lib/itinerary";
```

- [ ] **Step 3: 렌더링 교체**

Task 12에서 만든 `const title = ...` 아래, `const ordered = ...`부터
함수 끝의 `return (...)`까지 전체(평면 정렬 렌더링 + `ordered` 변수
계산)를 삭제하고:

```tsx
  const ordered = isItineraryGroup(group)
    ? [...group].sort((a, b) => {
        if (a.dayNumber !== b.dayNumber) return (a.dayNumber ?? 0) - (b.dayNumber ?? 0);
        return (a.orderInDay ?? 0) - (b.orderInDay ?? 0);
      })
    : group;

  return (
    <ScrollView contentContainerStyle={{ padding: 16, gap: 12 }}>
      <AppText weight="medium" style={{ fontSize: 18 }} numberOfLines={2}>
        {title}
      </AppText>

      <InlineMap
        pins={ordered
          .filter((p) => p.latitude !== null && p.longitude !== null)
          .map((p) => ({ id: String(p.id), latitude: p.latitude as number, longitude: p.longitude as number }))}
      />

      {!isItineraryGroup(group) && (
        <View>
          <Pressable
            onPress={handleGenerateItinerary}
            disabled={generating}
            style={{
              height: 48,
              borderRadius: 12,
              backgroundColor: "#FF6B4A",
              justifyContent: "center",
              alignItems: "center",
              opacity: generating ? 0.6 : 1,
            }}
          >
            <AppText weight="medium" style={{ color: "#fff" }}>
              {generating ? "일정 생성 중..." : "일정 짜기"}
            </AppText>
          </Pressable>
          {error && <AppText style={{ marginTop: 8, color: "#FF6B4A" }}>{error}</AppText>}
        </View>
      )}

      <View style={{ gap: 12 }}>
        {ordered.map((place, index) => {
          const isLast = index === ordered.length - 1;
          const next = ordered[index + 1];
          const distanceKm =
            !isLast &&
            place.latitude !== null &&
            place.longitude !== null &&
            next?.latitude !== null &&
            next?.longitude !== null
              ? haversineDistanceKm(place.latitude, place.longitude, next!.latitude!, next!.longitude!)
              : null;
          return (
            <PlaceRow key={place.id} place={place} index={index} isLast={isLast} distanceKm={distanceKm} />
          );
        })}
      </View>
    </ScrollView>
  );
```

다음으로 교체한다:

```tsx
  return (
    <ScrollView contentContainerStyle={{ padding: 16, gap: 12 }}>
      <AppText weight="medium" style={{ fontSize: 18 }} numberOfLines={2}>
        {title}
      </AppText>

      {!hasItinerary && (
        <>
          <InlineMap
            pins={group
              .filter((p) => p.latitude !== null && p.longitude !== null)
              .map((p) => ({ id: String(p.id), latitude: p.latitude as number, longitude: p.longitude as number }))}
          />
          <Pressable
            onPress={handleGenerateItinerary}
            disabled={generating}
            style={{
              height: 48,
              borderRadius: 12,
              backgroundColor: "#FF6B4A",
              justifyContent: "center",
              alignItems: "center",
              opacity: generating ? 0.6 : 1,
            }}
          >
            <AppText weight="medium" style={{ color: "#fff" }}>
              {generating ? "일정 생성 중..." : "일정 짜기"}
            </AppText>
          </Pressable>
          {error && <AppText style={{ color: "#FF6B4A" }}>{error}</AppText>}
          <View style={{ gap: 12 }}>
            {group.map((place, index) => {
              const isLast = index === group.length - 1;
              const next = group[index + 1];
              const distanceKm =
                !isLast && place.latitude !== null && place.longitude !== null && next?.latitude !== null && next?.longitude !== null
                  ? haversineDistanceKm(place.latitude, place.longitude, next!.latitude!, next!.longitude!)
                  : null;
              return <PlaceRow key={place.id} place={place} index={index} isLast={isLast} distanceKm={distanceKm} />;
            })}
          </View>
        </>
      )}

      {hasItinerary && (
        <>
          <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 8 }}>
            {dayNumbers.map((day) => (
              <Pressable
                key={day}
                onPress={() => setActiveDay(day)}
                style={{
                  paddingVertical: 8,
                  paddingHorizontal: 14,
                  borderRadius: 20,
                  backgroundColor: day === currentActiveDay ? "#FF6B4A" : "#F5F5F0",
                }}
              >
                <AppText weight="medium" style={{ color: day === currentActiveDay ? "#fff" : "#8C8C86", fontSize: 13 }}>
                  {day}일차
                </AppText>
              </Pressable>
            ))}
            <Pressable
              onPress={handleAddDay}
              style={{ paddingVertical: 8, paddingHorizontal: 14, borderRadius: 20, borderWidth: 1, borderColor: "#DEDED8" }}
            >
              <AppText style={{ fontSize: 13, color: "#8C8C86" }}>+ 날짜 추가</AppText>
            </Pressable>
          </View>

          {currentActiveDay !== null && emptyDayNumbers.includes(currentActiveDay) && activePlaces.length === 0 && (
            <Pressable onPress={() => handleDeleteDay(currentActiveDay)}>
              <AppText style={{ fontSize: 12, color: "#8C8C86" }}>이 빈 날짜 삭제</AppText>
            </Pressable>
          )}

          <Pressable
            onPress={handleOptimizeRoute}
            disabled={actionPending || activePlaces.length < 2}
            style={{ opacity: actionPending || activePlaces.length < 2 ? 0.4 : 1 }}
          >
            <AppText style={{ fontSize: 13, color: "#FF6B4A" }}>동선 최적화</AppText>
          </Pressable>

          {itineraryError && <AppText style={{ color: "#FF6B4A" }}>{itineraryError}</AppText>}

          <InlineMap
            pins={activePlaces
              .filter((p) => p.latitude !== null && p.longitude !== null)
              .map((p) => ({ id: String(p.id), latitude: p.latitude as number, longitude: p.longitude as number }))}
          />

          <View style={{ gap: 12 }}>
            {activePlaces.map((place, index) => {
              const isLast = index === activePlaces.length - 1;
              const next = activePlaces[index + 1];
              const distanceKm =
                !isLast && place.latitude !== null && place.longitude !== null && next?.latitude !== null && next?.longitude !== null
                  ? haversineDistanceKm(place.latitude, place.longitude, next!.latitude!, next!.longitude!)
                  : null;
              return (
                <PlaceRow
                  key={place.id}
                  place={place}
                  index={index}
                  isLast={isLast}
                  distanceKm={distanceKm}
                  editable
                  disabled={actionPending}
                  onMoveUp={() => handleReorder(place, "UP")}
                  onMoveDown={() => handleReorder(place, "DOWN")}
                  onOpenDayPicker={() => setDayPickerFor(place)}
                />
              );
            })}
          </View>

          {unassignedPlaces.length > 0 && (
            <View style={{ gap: 12 }}>
              <AppText weight="medium" style={{ fontSize: 13, color: "#8C8C86" }}>
                아직 날짜가 없는 장소
              </AppText>
              {unassignedPlaces.map((place, index) => (
                <PlaceRow
                  key={place.id}
                  place={place}
                  index={index}
                  isLast={index === unassignedPlaces.length - 1}
                  distanceKm={null}
                  editable
                  disabled={actionPending}
                  onOpenDayPicker={() => setDayPickerFor(place)}
                />
              ))}
            </View>
          )}

          <DayPickerSheet
            visible={dayPickerFor !== null}
            dayNumbers={dayNumbers}
            currentDay={dayPickerFor?.dayNumber ?? null}
            onSelect={(day) => {
              if (dayPickerFor) handleMoveDay(dayPickerFor, day);
            }}
            onClose={() => setDayPickerFor(null)}
          />
        </>
      )}
    </ScrollView>
  );
}
```

필요한 import 추가: `DayPickerSheet`(`@/components/DayPickerSheet`).
Task 12에서 만들었던 `!isItineraryGroup(group)` 조건의 "일정 짜기"
버튼 블록은 이제 위 `!hasItinerary` 블록 안으로 이미 옮겨졌으므로
중복 블록이 남아있지 않은지 확인한다.

- [ ] **Step 4: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 5: 커밋**

```bash
git add src/screens/VideoGroupScreen.tsx
git commit -m "feat: 영상 그룹 화면에 일정 편집 UI 추가"
```

- [ ] **Step 6: 시뮬레이터로 수동 확인**

이미 일정이 있는(예전에 "일정 짜기"를 눌러 확정된) 영상이 없다면,
`VideoGroupScreen`에서 "일정 짜기"를 눌러 실제로 일정이 생성되게 한
뒤 화면을 캡처해 날짜 탭/순서변경/요일이동 모달/동선최적화 버튼이
모두 보이는지 확인한다. 순서변경 버튼을 눌러 스크린샷을 다시 찍고
순서가 실제로 바뀌었는지 확인한다.

---

### Task 14: `TripsListScreen`

**Files:**
- Modify: `src/navigation/types.ts`
- Modify: `src/navigation/RootNavigator.tsx`
- Create: `src/screens/TripsListScreen.tsx`

**Interfaces:**
- Consumes: `listTrips`(Task 4)
- Produces: `<TripsListScreen navigation>` — 행 탭 시
  `navigation.navigate("TripDetail", {id})`(Task 16에서 라우트 등록),
  "새 여행 만들기" 시 `navigation.navigate("NewTrip")`(Task 15에서
  라우트 등록)

`TripDetail`/`NewTrip` 라우트가 아직 없으므로, 이 태스크에서는 세
라우트(`TripsList`/`NewTrip`/`TripDetail`)를 한꺼번에
`RootStackParamList`에 추가해 타입 에러 없이 진행되게 한다(화면
컴포넌트 자체는 Task 15/16에서 만든다 — 그 전까지는 `RootNavigator`에
아직 등록하지 않는다).

- [ ] **Step 1: `RootStackParamList`에 세 라우트 추가**

```ts
export type RootStackParamList = {
  Login: undefined;
  Home: undefined;
  Processing: { jobId: number };
  PlacesList: undefined;
  VideoGroup: { jobId: number };
  TripsList: undefined;
  NewTrip: undefined;
  TripDetail: { id: number };
};
```

- [ ] **Step 2: `TripsListScreen.tsx` 작성**

```tsx
import { FlatList, Platform, Pressable, View } from "react-native";
import { useQuery } from "@tanstack/react-query";
import { AppText } from "@/components/AppText";
import { listTrips } from "@/lib/api/trips";
import type { NativeStackScreenProps } from "@react-navigation/native-stack";
import type { RootStackParamList } from "@/navigation/types";

type Props = NativeStackScreenProps<RootStackParamList, "TripsList">;

const CARD_SHADOW = Platform.select({
  ios: { shadowColor: "#000", shadowOffset: { width: 0, height: 1 }, shadowOpacity: 0.08, shadowRadius: 3 },
  android: { elevation: 2 },
});

export function TripsListScreen({ navigation }: Props) {
  const tripsQuery = useQuery({ queryKey: ["trips"], queryFn: listTrips });

  if (tripsQuery.isLoading) {
    return (
      <View style={{ flex: 1, justifyContent: "center", alignItems: "center" }}>
        <AppText>불러오는 중...</AppText>
      </View>
    );
  }

  const trips = tripsQuery.data ?? [];

  return (
    <FlatList
      contentContainerStyle={{ padding: 16, gap: 12 }}
      data={trips}
      keyExtractor={(item) => String(item.id)}
      ListHeaderComponent={
        <Pressable
          onPress={() => navigation.navigate("NewTrip")}
          style={{
            marginBottom: 4,
            height: 48,
            borderRadius: 12,
            backgroundColor: "#FF6B4A",
            justifyContent: "center",
            alignItems: "center",
          }}
        >
          <AppText weight="medium" style={{ color: "#fff" }}>
            새 여행 만들기
          </AppText>
        </Pressable>
      }
      ListEmptyComponent={<AppText style={{ textAlign: "center", marginTop: 32 }}>아직 만든 여행이 없어요.</AppText>}
      renderItem={({ item }) => (
        <Pressable
          onPress={() => navigation.navigate("TripDetail", { id: item.id })}
          style={{
            padding: 16,
            borderRadius: 12,
            borderWidth: 1,
            borderColor: "#DEDED8",
            backgroundColor: "#fff",
            ...CARD_SHADOW,
          }}
        >
          <AppText weight="medium">{item.title}</AppText>
          {item.startDate && (
            <AppText style={{ marginTop: 4, fontSize: 12, color: "#8C8C86" }}>
              {item.startDate} ~ {item.endDate}
            </AppText>
          )}
        </Pressable>
      )}
    />
  );
}
```

- [ ] **Step 3: `RootNavigator.tsx`에 등록**

`import { VideoGroupScreen } from "@/screens/VideoGroupScreen";` 아래에
추가:

```tsx
import { TripsListScreen } from "@/screens/TripsListScreen";
```

`<Stack.Screen name="VideoGroup" ... />` 아래에 추가:

```tsx
          <Stack.Screen name="TripsList" component={TripsListScreen} options={{ title: "내 여행" }} />
```

(`NewTrip`/`TripDetail`은 아직 컴포넌트가 없으므로 이 태스크에서는
등록하지 않는다 — Task 15/16에서 각각 등록한다. `RootStackParamList`에
타입만 미리 있으면 `tsc`는 통과한다 — 실제 `Stack.Screen`으로 아직
등록되지 않은 라우트라도 타입 체크는 막지 않기 때문이다. 단, 이
태스크에서 만든 "새 여행 만들기" 버튼은 `navigation.navigate("NewTrip")`을
실제로 호출하므로, **Task 15가 끝나기 전에 시뮬레이터에서 이 버튼을
누르면 "screen not found" 런타임 에러가 난다.** 이는 의도된 일시적
상태이며 버그가 아니다 — 이 태스크에는 수동 확인 스텝을 넣지 않고,
Task 15/16 이후에야 전체 플로우를 확인한다.)

- [ ] **Step 4: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 5: 커밋**

```bash
git add src/navigation/types.ts src/navigation/RootNavigator.tsx src/screens/TripsListScreen.tsx
git commit -m "feat: 내 여행 목록 화면 추가"
```

---

### Task 15: `NewTripScreen`

**Files:**
- Modify: `src/navigation/RootNavigator.tsx`
- Create: `src/screens/NewTripScreen.tsx`

**Interfaces:**
- Consumes: `createTrip`(Task 4), `toDateString`(Task 7)
- Produces: `<NewTripScreen navigation>` — 성공 시
  `navigation.replace("TripDetail", {id})`(Task 16에서 화면 등록,
  라우트 타입은 Task 14에서 이미 추가됨)

- [ ] **Step 1: `NewTripScreen.tsx` 작성**

```tsx
import { useState } from "react";
import { Platform, Pressable, TextInput, View } from "react-native";
import DateTimePicker from "@react-native-community/datetimepicker";
import { AppText } from "@/components/AppText";
import { createTrip } from "@/lib/api/trips";
import { toDateString } from "@/lib/date";
import type { NativeStackScreenProps } from "@react-navigation/native-stack";
import type { RootStackParamList } from "@/navigation/types";

type Props = NativeStackScreenProps<RootStackParamList, "NewTrip">;

export function NewTripScreen({ navigation }: Props) {
  const [title, setTitle] = useState("");
  const [startDate, setStartDate] = useState(new Date());
  const [endDate, setEndDate] = useState(new Date());
  const [showStartPicker, setShowStartPicker] = useState(false);
  const [showEndPicker, setShowEndPicker] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit() {
    if (!title.trim() || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const trip = await createTrip(title.trim(), toDateString(startDate), toDateString(endDate));
      navigation.replace("TripDetail", { id: trip.id });
    } catch {
      setError("여행을 만들지 못했어요. 날짜를 확인하고 다시 시도해주세요.");
      setSubmitting(false);
    }
  }

  return (
    <View style={{ flex: 1, padding: 24, gap: 16 }}>
      <AppText weight="medium" style={{ fontSize: 20 }}>
        새 여행 만들기
      </AppText>
      <TextInput
        value={title}
        onChangeText={setTitle}
        placeholder="예: 김해 당일치기"
        autoFocus
        style={{
          height: 48,
          borderWidth: 1,
          borderColor: "#DEDED8",
          borderRadius: 12,
          paddingHorizontal: 16,
          fontFamily: "IBMPlexMono_400Regular",
        }}
      />

      <View style={{ flexDirection: "row", gap: 12 }}>
        <View style={{ flex: 1, gap: 4 }}>
          <AppText style={{ fontSize: 12, color: "#8C8C86" }}>출발일</AppText>
          <Pressable
            onPress={() => setShowStartPicker(true)}
            style={{ height: 44, borderWidth: 1, borderColor: "#DEDED8", borderRadius: 12, justifyContent: "center", paddingHorizontal: 12 }}
          >
            <AppText>{toDateString(startDate)}</AppText>
          </Pressable>
        </View>
        <View style={{ flex: 1, gap: 4 }}>
          <AppText style={{ fontSize: 12, color: "#8C8C86" }}>도착일</AppText>
          <Pressable
            onPress={() => setShowEndPicker(true)}
            style={{ height: 44, borderWidth: 1, borderColor: "#DEDED8", borderRadius: 12, justifyContent: "center", paddingHorizontal: 12 }}
          >
            <AppText>{toDateString(endDate)}</AppText>
          </Pressable>
        </View>
      </View>

      {showStartPicker && (
        <DateTimePicker
          value={startDate}
          mode="date"
          display={Platform.OS === "ios" ? "spinner" : "default"}
          onChange={(_event, selected) => {
            setShowStartPicker(Platform.OS === "ios");
            if (selected) {
              setStartDate(selected);
              // 출발일이 도착일보다 늦어지면 도착일도 함께 밀어준다(웹과 동일한 보정).
              if (selected > endDate) setEndDate(selected);
            }
          }}
        />
      )}
      {showEndPicker && (
        <DateTimePicker
          value={endDate}
          mode="date"
          minimumDate={startDate}
          display={Platform.OS === "ios" ? "spinner" : "default"}
          onChange={(_event, selected) => {
            setShowEndPicker(Platform.OS === "ios");
            if (selected) setEndDate(selected);
          }}
        />
      )}

      {error && <AppText style={{ color: "#FF6B4A" }}>{error}</AppText>}

      <Pressable
        onPress={handleSubmit}
        disabled={!title.trim() || submitting}
        style={{
          height: 48,
          borderRadius: 12,
          backgroundColor: "#FF6B4A",
          justifyContent: "center",
          alignItems: "center",
          opacity: !title.trim() || submitting ? 0.6 : 1,
        }}
      >
        <AppText weight="medium" style={{ color: "#fff" }}>
          {submitting ? "만드는 중..." : "여행 만들기"}
        </AppText>
      </Pressable>
    </View>
  );
}
```

(iOS는 `display="spinner"`가 인라인으로 계속 보이는 방식이라 선택 시
바로 닫지 않는다 — `setShowStartPicker(Platform.OS === "ios")`가 iOS에서는
계속 `true`를 유지해 스피너가 열려있게 하고, Android는 네이티브
다이얼로그라 선택/취소 즉시 `false`로 닫는다. 이 플랫폼 차이는
라이브러리의 기본 동작이며 의도적으로 통일하지 않는다.)

- [ ] **Step 2: `RootNavigator.tsx`에 등록**

```tsx
import { NewTripScreen } from "@/screens/NewTripScreen";
```

```tsx
          <Stack.Screen name="NewTrip" component={NewTripScreen} options={{ title: "새 여행" }} />
```

(`TripDetail`은 아직 컴포넌트가 없으므로 등록하지 않는다 — Task 16에서
등록한다. 타입은 이미 `RootStackParamList`에 있으므로
`navigation.replace("TripDetail", ...)` 호출 자체는 `tsc`를 통과한다.
다만 이 화면의 "여행 만들기" 버튼을 **Task 16이 끝나기 전에**
시뮬레이터에서 실제로 눌러 제출하면 "screen not found" 런타임
에러가 난다 — Task 14의 "새 여행 만들기" 버튼과 같은 종류의 의도된
일시적 상태다. 이 태스크에도 수동 확인 스텝을 넣지 않는다.)

- [ ] **Step 3: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add src/navigation/RootNavigator.tsx src/screens/NewTripScreen.tsx
git commit -m "feat: 여행 직접 생성 화면 추가"
```

---

### Task 16: `TripDetailScreen`(핵심) — 일자 탭/지도/순서변경/삭제/상세편집/날씨

**Files:**
- Modify: `src/navigation/RootNavigator.tsx`
- Create: `src/screens/TripDetailScreen.tsx`

**Interfaces:**
- Consumes: `getTrip`/`reorderTripPlace`/`removeTripPlace`/
  `updateTripPlaceDetails`/`checkWeather`(Task 4), `InlineMap`(Task 8),
  `PlaceRow`(Task 9), `getDayColor`(Task 2), `toTimeString`/
  `parseTimeToDate`(Task 7)
- Produces: `<TripDetailScreen route navigation>`. 검색/찜 탭은
  Task 17에서 이 파일에 추가된다 — 이 태스크에서는 그 두 탭 버튼과
  빈 틀만 만들어 둔다(탭 전환 자체는 동작하되 내용은 Task 17이
  채운다).

일정 화면과 달리 이 화면은 웹 `TripDetailView`와 동일하게
낙관적 업데이트 없이 매 변경 후 `getTrip`으로 재조회한다.

- [ ] **Step 1: `TripDetailScreen.tsx` 작성**

```tsx
import { useState } from "react";
import { Pressable, ScrollView, TextInput, View } from "react-native";
import DateTimePicker from "@react-native-community/datetimepicker";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AppText } from "@/components/AppText";
import { InlineMap } from "@/components/InlineMap";
import { PlaceRow } from "@/components/PlaceRow";
import { getDayColor } from "@/lib/itinerary";
import { parseTimeToDate, toTimeString } from "@/lib/date";
import {
  checkWeather,
  getTrip,
  removeTripPlace,
  reorderTripPlace,
  updateTripPlaceDetails,
  type TripPlace,
} from "@/lib/api/trips";
import type { NativeStackScreenProps } from "@react-navigation/native-stack";
import type { RootStackParamList } from "@/navigation/types";

type Props = NativeStackScreenProps<RootStackParamList, "TripDetail">;

const TRANSPORT_LABEL: Record<"WALK" | "TRANSIT" | "CAR", string> = {
  WALK: "도보",
  TRANSIT: "대중교통",
  CAR: "차량",
};

type EditingField = { placeId: number; field: "time" | "transport" | "memo" } | null;

export function TripDetailScreen({ route }: Props) {
  const { id } = route.params;
  const queryClient = useQueryClient();
  const tripQuery = useQuery({ queryKey: ["trip", id], queryFn: () => getTrip(id) });

  const [activeDay, setActiveDay] = useState<number | null>(null);
  const [activeTab, setActiveTab] = useState<"search" | "bookmarks">("search");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [weatherMessage, setWeatherMessage] = useState<string | null>(null);
  const [editingField, setEditingField] = useState<EditingField>(null);
  const [showTimePicker, setShowTimePicker] = useState<"start" | "end" | null>(null);
  const [memoDraft, setMemoDraft] = useState("");

  const trip = tripQuery.data;

  if (tripQuery.isLoading || !trip) {
    return (
      <View style={{ flex: 1, justifyContent: "center", alignItems: "center" }}>
        <AppText>불러오는 중...</AppText>
      </View>
    );
  }

  const currentActiveDay = activeDay ?? trip.days[0]?.day ?? 1;
  const activeDayData = trip.days.find((d) => d.day === currentActiveDay);
  const places = activeDayData?.places ?? [];
  const dayColor = getDayColor(currentActiveDay);

  async function reload() {
    await queryClient.invalidateQueries({ queryKey: ["trip", id] });
  }

  async function handleReorder(placeId: number, direction: "UP" | "DOWN") {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await reorderTripPlace(placeId, direction);
      await reload();
    } catch {
      setError("순서를 바꾸지 못했어요.");
    } finally {
      setBusy(false);
    }
  }

  async function handleRemove(placeId: number) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await removeTripPlace(placeId);
      await reload();
    } catch {
      setError("장소를 삭제하지 못했어요.");
    } finally {
      setBusy(false);
    }
  }

  async function handleUpdateDetails(placeId: number, patch: Parameters<typeof updateTripPlaceDetails>[1]) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await updateTripPlaceDetails(placeId, patch);
      await reload();
    } catch {
      setError("저장하지 못했어요.");
    } finally {
      setBusy(false);
      setEditingField(null);
    }
  }

  async function handleCheckWeather() {
    if (busy) return;
    setBusy(true);
    setWeatherMessage(null);
    try {
      const result = await checkWeather(trip.id, currentActiveDay);
      setWeatherMessage(result.message);
    } catch {
      setWeatherMessage("날씨 확인에 실패했어요.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <ScrollView contentContainerStyle={{ padding: 16, gap: 16 }}>
      <View style={{ flexDirection: "row", justifyContent: "space-between", alignItems: "center" }}>
        <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 8, flex: 1 }}>
          {trip.days.map((d) => (
            <Pressable
              key={d.day}
              onPress={() => setActiveDay(d.day)}
              style={{
                paddingVertical: 8,
                paddingHorizontal: 14,
                borderRadius: 20,
                backgroundColor: d.day === currentActiveDay ? "#FF6B4A" : "#F5F5F0",
              }}
            >
              <AppText weight="medium" style={{ color: d.day === currentActiveDay ? "#fff" : "#8C8C86", fontSize: 13 }}>
                {d.day}일차{d.date ? ` (${d.date.slice(5)})` : ""}
              </AppText>
            </Pressable>
          ))}
        </View>
        <Pressable onPress={handleCheckWeather} disabled={busy || !activeDayData?.date}>
          <AppText style={{ fontSize: 13, color: "#FF6B4A", opacity: !activeDayData?.date ? 0.4 : 1 }}>날씨 확인</AppText>
        </Pressable>
      </View>

      {weatherMessage && (
        <View style={{ padding: 10, borderRadius: 8, backgroundColor: "#FFF1EC" }}>
          <AppText style={{ fontSize: 13 }}>{weatherMessage}</AppText>
        </View>
      )}
      {error && <AppText style={{ color: "#FF6B4A" }}>{error}</AppText>}

      <InlineMap
        pins={places
          .filter((p) => p.latitude !== null && p.longitude !== null)
          .map((p) => ({ id: String(p.id), latitude: p.latitude as number, longitude: p.longitude as number }))}
      />

      <View style={{ gap: 12 }}>
        {places.length === 0 && (
          <AppText style={{ textAlign: "center", color: "#8C8C86", padding: 16 }}>
            아직 장소가 없어요. 아래에서 검색해서 추가해보세요.
          </AppText>
        )}
        {places.map((place, index) => (
          <PlaceRow
            key={place.id}
            place={place}
            index={index}
            isLast={index === places.length - 1}
            distanceKm={null}
            editable
            disabled={busy}
            color={dayColor}
            onMoveUp={() => handleReorder(place.id, "UP")}
            onMoveDown={() => handleReorder(place.id, "DOWN")}
          >
            <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 12, alignItems: "center", marginTop: 4 }}>
              <Pressable
                onPress={() => {
                  setEditingField({ placeId: place.id, field: "time" });
                  setShowTimePicker("start");
                }}
              >
                <AppText style={{ fontSize: 12, color: "#8C8C86" }}>
                  {place.visitStartTime && place.visitEndTime
                    ? `${place.visitStartTime.slice(0, 5)}~${place.visitEndTime.slice(0, 5)}`
                    : "시간 추가"}
                </AppText>
              </Pressable>

              <Pressable
                onPress={() =>
                  setEditingField(
                    editingField?.placeId === place.id && editingField.field === "transport"
                      ? null
                      : { placeId: place.id, field: "transport" }
                  )
                }
              >
                <AppText style={{ fontSize: 12, color: "#8C8C86" }}>
                  {place.arrivalTransportMode ? TRANSPORT_LABEL[place.arrivalTransportMode] : "이동수단 추가"}
                </AppText>
              </Pressable>

              <Pressable
                onPress={() => {
                  setMemoDraft(place.memo ?? "");
                  setEditingField({ placeId: place.id, field: "memo" });
                }}
              >
                <AppText style={{ fontSize: 12, color: "#8C8C86" }} numberOfLines={1}>
                  {place.memo || "메모 추가"}
                </AppText>
              </Pressable>

              <Pressable onPress={() => handleRemove(place.id)} disabled={busy} style={{ marginLeft: "auto" }}>
                <AppText style={{ fontSize: 13, color: "#8C8C86" }}>삭제</AppText>
              </Pressable>
            </View>

            {editingField?.placeId === place.id && editingField.field === "transport" && (
              <View style={{ flexDirection: "row", gap: 8, marginTop: 6 }}>
                {(["WALK", "TRANSIT", "CAR"] as const).map((mode) => (
                  <Pressable
                    key={mode}
                    onPress={() => handleUpdateDetails(place.id, { arrivalTransportMode: mode })}
                    style={{
                      paddingVertical: 4,
                      paddingHorizontal: 10,
                      borderRadius: 14,
                      backgroundColor: place.arrivalTransportMode === mode ? "#FF6B4A" : "#F5F5F0",
                    }}
                  >
                    <AppText style={{ fontSize: 11, color: place.arrivalTransportMode === mode ? "#fff" : "#8C8C86" }}>
                      {TRANSPORT_LABEL[mode]}
                    </AppText>
                  </Pressable>
                ))}
              </View>
            )}

            {editingField?.placeId === place.id && editingField.field === "memo" && (
              <TextInput
                autoFocus
                defaultValue={memoDraft}
                onChangeText={setMemoDraft}
                onBlur={() => handleUpdateDetails(place.id, { memo: memoDraft })}
                onSubmitEditing={() => handleUpdateDetails(place.id, { memo: memoDraft })}
                style={{
                  marginTop: 6,
                  borderWidth: 1,
                  borderColor: "#DEDED8",
                  borderRadius: 8,
                  paddingHorizontal: 10,
                  paddingVertical: 6,
                  fontSize: 12,
                }}
              />
            )}

            {editingField?.placeId === place.id && editingField.field === "time" && showTimePicker && (
              <DateTimePicker
                value={parseTimeToDate(showTimePicker === "start" ? place.visitStartTime : place.visitEndTime)}
                mode="time"
                display={Platform.OS === "ios" ? "spinner" : "default"}
                onChange={(_event, selected) => {
                  if (!selected) {
                    setShowTimePicker(null);
                    return;
                  }
                  const timeStr = toTimeString(selected);
                  if (showTimePicker === "start") {
                    handleUpdateDetails(place.id, { visitStartTime: timeStr }).then(() => setShowTimePicker("end"));
                  } else {
                    handleUpdateDetails(place.id, { visitEndTime: timeStr }).then(() => setShowTimePicker(null));
                  }
                }}
              />
            )}
          </PlaceRow>
        ))}
      </View>

      <View style={{ flexDirection: "row", gap: 16, borderTopWidth: 1, borderTopColor: "#DEDED8", paddingTop: 16 }}>
        <Pressable onPress={() => setActiveTab("search")}>
          <AppText weight="medium" style={{ color: activeTab === "search" ? "#FF6B4A" : "#8C8C86" }}>
            검색
          </AppText>
        </Pressable>
        <Pressable onPress={() => setActiveTab("bookmarks")}>
          <AppText weight="medium" style={{ color: activeTab === "bookmarks" ? "#FF6B4A" : "#8C8C86" }}>
            찜한 장소
          </AppText>
        </Pressable>
      </View>
      {/* 검색/찜 탭 내용은 Task 17에서 여기에 추가된다. */}
    </ScrollView>
  );
}
```

`Platform` import 추가 필요: `import { Platform, Pressable, ScrollView,
TextInput, View } from "react-native";`

- [ ] **Step 2: `RootNavigator.tsx`에 등록**

```tsx
import { TripDetailScreen } from "@/screens/TripDetailScreen";
```

```tsx
          <Stack.Screen name="TripDetail" component={TripDetailScreen} options={{ title: "여행 상세" }} />
```

- [ ] **Step 3: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add src/navigation/RootNavigator.tsx src/screens/TripDetailScreen.tsx
git commit -m "feat: 여행 상세 화면 추가(일자 탭/지도/순서변경/삭제/상세편집/날씨)"
```

- [ ] **Step 5: 시뮬레이터로 수동 확인**

`TripsListScreen`에서 기존 여행이 있으면 진입해 확인하고, 없으면
`NewTripScreen`으로 하나 만들어 진입한다. 날짜 탭 전환, "시간 추가"로
DateTimePicker가 뜨는지, "이동수단 추가"로 3개 버튼이 뜨는지, "메모
추가"로 텍스트 입력이 되는지, 날씨 확인 버튼을 눌러 메시지가 뜨는지
스크린샷으로 확인한다(장소가 아직 없으면 Task 17까지 마친 뒤 검색으로
하나 추가하고 다시 확인).

---

### Task 17: `TripDetailScreen` 검색/찜 탭

**Files:**
- Modify: `src/screens/TripDetailScreen.tsx`

**Interfaces:**
- Consumes: `searchPlaces`(Task 5), `addBookmark`/`listBookmarks`/
  `removeBookmark`(Task 6), `addTripPlace`(Task 4), `PlaceReviewModal`
  (Task 11)

- [ ] **Step 1: 상태와 핸들러 추가**

`TripDetailScreen` 함수 안, 기존 상태 선언들 옆에 추가:

```tsx
  const [query, setQuery] = useState("");
  const [searchResults, setSearchResults] = useState<RecommendedPlace[]>([]);
  const [searching, setSearching] = useState(false);
  const [reviewModalPlaceId, setReviewModalPlaceId] = useState<number | null>(null);

  const bookmarksQuery = useQuery({ queryKey: ["bookmarks"], queryFn: listBookmarks });
  const bookmarkedPlaceIds = new Set((bookmarksQuery.data ?? []).map((b) => b.placeId));

  async function handleSearch() {
    if (!query.trim() || searching) return;
    setSearching(true);
    setError(null);
    try {
      setSearchResults(await searchPlaces(query.trim()));
    } catch {
      setError("장소를 찾지 못했어요. 다른 검색어로 시도해보세요.");
    } finally {
      setSearching(false);
    }
  }

  async function handleAddPlace(googlePlaceId: string) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await addTripPlace(trip.id, currentActiveDay, googlePlaceId);
      await reload();
    } catch {
      setError("장소를 추가하지 못했어요.");
    } finally {
      setBusy(false);
    }
  }

  async function handleToggleBookmark(placeId: number) {
    if (bookmarkedPlaceIds.has(placeId)) return;
    try {
      await addBookmark(placeId);
      await queryClient.invalidateQueries({ queryKey: ["bookmarks"] });
    } catch {
      setError("찜하기에 실패했어요.");
    }
  }

  async function handleRemoveBookmark(bookmarkId: number) {
    try {
      await removeBookmark(bookmarkId);
      await queryClient.invalidateQueries({ queryKey: ["bookmarks"] });
    } catch {
      setError("찜을 해제하지 못했어요.");
    }
  }
```

`trip`이 이 시점에 이미 로드돼 있어야 `handleAddPlace`가 안전하다 —
이 함수들은 `if (tripQuery.isLoading || !trip)` 얼리 리턴 아래쪽,
`return (...)` 이전에 위치해야 하므로(Step 1의 다른 핸들러들과 같은
위치) 실제로는 그 얼리 리턴 다음 줄부터 추가한다.

import 추가:

```tsx
import { addBookmark, listBookmarks, removeBookmark } from "@/lib/api/bookmarks";
import { searchPlaces, type RecommendedPlace } from "@/lib/api/recommendations";
import { addTripPlace } from "@/lib/api/trips";
import { PlaceReviewModal } from "@/components/PlaceReviewModal";
import { TextInput as RNTextInput } from "react-native";
```

(`addTripPlace`는 기존 `import { checkWeather, getTrip, ... } from
"@/lib/api/trips";` 줄에 함께 추가해도 된다 — 새 import 문을 따로 만들
필요는 없다.)

- [ ] **Step 2: 탭 내용 렌더링**

Task 16에서 남겨둔 주석 `{/* 검색/찜 탭 내용은 Task 17에서 여기에
추가된다. */}`을 아래로 교체한다:

```tsx
      {activeTab === "search" ? (
        <View style={{ gap: 12 }}>
          <View style={{ flexDirection: "row", gap: 8 }}>
            <TextInput
              value={query}
              onChangeText={setQuery}
              placeholder="장소 이름으로 검색 (예: 경복궁)"
              style={{
                flex: 1,
                height: 44,
                borderWidth: 1,
                borderColor: "#DEDED8",
                borderRadius: 10,
                paddingHorizontal: 12,
                fontFamily: "IBMPlexMono_400Regular",
              }}
            />
            <Pressable
              onPress={handleSearch}
              disabled={searching || !query.trim()}
              style={{
                height: 44,
                paddingHorizontal: 16,
                borderRadius: 10,
                backgroundColor: "#FF6B4A",
                justifyContent: "center",
                alignItems: "center",
                opacity: searching || !query.trim() ? 0.6 : 1,
              }}
            >
              <AppText weight="medium" style={{ color: "#fff" }}>
                {searching ? "검색 중..." : "검색"}
              </AppText>
            </Pressable>
          </View>

          {searchResults.map((place) => (
            <View key={place.id} style={{ padding: 12, borderWidth: 1, borderColor: "#DEDED8", borderRadius: 12, gap: 6 }}>
              <View style={{ flexDirection: "row", justifyContent: "space-between", gap: 8 }}>
                <View style={{ flex: 1 }}>
                  <AppText weight="medium" numberOfLines={1}>
                    {place.name}
                  </AppText>
                  {place.address && (
                    <AppText style={{ fontSize: 12, color: "#8C8C86" }} numberOfLines={1}>
                      {place.address}
                    </AppText>
                  )}
                  {place.rating !== null && (
                    <AppText style={{ fontSize: 12, color: "#8C8C86" }}>
                      ⭐ {place.rating.toFixed(1)}
                      {place.userRatingCount !== null ? ` (리뷰 ${place.userRatingCount}개)` : ""}
                    </AppText>
                  )}
                </View>
                <View style={{ flexDirection: "row", gap: 10, alignItems: "center" }}>
                  <Pressable onPress={() => handleToggleBookmark(place.id)}>
                    <AppText style={{ fontSize: 18 }}>{bookmarkedPlaceIds.has(place.id) ? "❤️" : "🤍"}</AppText>
                  </Pressable>
                  <Pressable
                    onPress={() => handleAddPlace(place.googlePlaceId)}
                    disabled={busy}
                    style={{ paddingVertical: 6, paddingHorizontal: 10, borderRadius: 8, backgroundColor: "#FF6B4A" }}
                  >
                    <AppText style={{ fontSize: 12, color: "#fff" }}>추가</AppText>
                  </Pressable>
                </View>
              </View>
              <Pressable onPress={() => setReviewModalPlaceId(place.id)}>
                <AppText style={{ fontSize: 12, color: "#FF6B4A" }}>상세보기</AppText>
              </Pressable>
            </View>
          ))}
        </View>
      ) : (
        <View style={{ gap: 12 }}>
          {(bookmarksQuery.data ?? []).length === 0 && (
            <AppText style={{ color: "#8C8C86" }}>아직 찜한 장소가 없어요.</AppText>
          )}
          {(bookmarksQuery.data ?? []).map((bookmark) => (
            <View
              key={bookmark.id}
              style={{
                flexDirection: "row",
                justifyContent: "space-between",
                alignItems: "center",
                padding: 12,
                borderWidth: 1,
                borderColor: "#DEDED8",
                borderRadius: 12,
              }}
            >
              <AppText weight="medium" numberOfLines={1} style={{ flex: 1 }}>
                {bookmark.placeName}
              </AppText>
              <View style={{ flexDirection: "row", gap: 10 }}>
                <Pressable
                  onPress={() => handleAddPlace(bookmark.googlePlaceId)}
                  disabled={busy}
                  style={{ paddingVertical: 6, paddingHorizontal: 10, borderRadius: 8, backgroundColor: "#FF6B4A" }}
                >
                  <AppText style={{ fontSize: 12, color: "#fff" }}>추가</AppText>
                </Pressable>
                <Pressable onPress={() => handleRemoveBookmark(bookmark.id)}>
                  <AppText style={{ fontSize: 12, color: "#8C8C86" }}>제거</AppText>
                </Pressable>
              </View>
            </View>
          ))}
        </View>
      )}

      <PlaceReviewModal
        visible={reviewModalPlaceId !== null}
        placeId={reviewModalPlaceId}
        onClose={() => setReviewModalPlaceId(null)}
      />
```

이 블록은 `ScrollView`의 마지막 자식(탭 버튼 다음)에 위치해야 한다.

- [ ] **Step 3: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add src/screens/TripDetailScreen.tsx
git commit -m "feat: 여행 상세 화면에 장소 검색/찜 탭 추가"
```

- [ ] **Step 5: 시뮬레이터로 수동 확인**

검색 탭에서 실제 장소를 검색해 "추가"로 활성 날짜에 들어가는지,
하트를 눌러 찜한 뒤 "찜한 장소" 탭에 나타나는지, "상세보기"로 AI
리뷰 요약 모달이 뜨는지 스크린샷으로 확인한다.

---

### Task 18: `VideoGroupScreen` — "여행으로 만들기" 연결

**Files:**
- Modify: `src/screens/VideoGroupScreen.tsx`

**Interfaces:**
- Consumes: `confirmTrip`(Task 4), `toDateString`(Task 7), `TripDetail`
  라우트(Task 16에서 이미 등록됨)

- [ ] **Step 1: 상태와 핸들러 추가**

`VideoGroupScreen` 함수 안, Task 13에서 추가한 상태들 옆에 추가:

```tsx
  const [showTripForm, setShowTripForm] = useState(false);
  const [tripTitle, setTripTitle] = useState("");
  const [confirmingTrip, setConfirmingTrip] = useState(false);
  const [tripError, setTripError] = useState<string | null>(null);

  async function handleConfirmTrip() {
    if (group.length === 0 || confirmingTrip) return;
    setConfirmingTrip(true);
    setTripError(null);
    try {
      const trip = await confirmTrip(group[0].jobId, tripTitle.trim() || title, toDateString(new Date()));
      navigation.replace("TripDetail", { id: trip.id });
    } catch {
      setTripError("여행 확정에 실패했어요. 다시 시도해주세요.");
      setConfirmingTrip(false);
    }
  }
```

import 추가: `confirmTrip`(`@/lib/api/trips`), `toDateString`(`@/lib/date`).

- [ ] **Step 2: 버튼/폼 렌더링**

Task 13에서 만든 `{hasItinerary && (<> ... </>)}` 블록의 날짜 탭
바로 아래(동선 최적화 버튼 위 또는 아래 아무 곳이나 자연스러운
위치)에 추가:

```tsx
          {!showTripForm ? (
            <Pressable
              onPress={() => {
                setTripTitle(title);
                setShowTripForm(true);
              }}
              style={{ height: 44, borderRadius: 12, borderWidth: 1, borderColor: "#FF6B4A", justifyContent: "center", alignItems: "center" }}
            >
              <AppText weight="medium" style={{ color: "#FF6B4A" }}>
                여행으로 만들기
              </AppText>
            </Pressable>
          ) : (
            <View style={{ gap: 8, padding: 12, borderWidth: 1, borderColor: "#DEDED8", borderRadius: 12 }}>
              <TextInput
                value={tripTitle}
                onChangeText={setTripTitle}
                placeholder="여행 이름"
                style={{
                  height: 40,
                  borderWidth: 1,
                  borderColor: "#DEDED8",
                  borderRadius: 8,
                  paddingHorizontal: 10,
                  fontFamily: "IBMPlexMono_400Regular",
                }}
              />
              <Pressable
                onPress={handleConfirmTrip}
                disabled={confirmingTrip}
                style={{
                  height: 40,
                  borderRadius: 8,
                  backgroundColor: "#FF6B4A",
                  justifyContent: "center",
                  alignItems: "center",
                  opacity: confirmingTrip ? 0.6 : 1,
                }}
              >
                <AppText weight="medium" style={{ color: "#fff" }}>
                  {confirmingTrip ? "확정 중..." : "확정"}
                </AppText>
              </Pressable>
              {tripError && <AppText style={{ color: "#FF6B4A" }}>{tripError}</AppText>}
            </View>
          )}
```

`TextInput`을 import에 추가: `import { Pressable, ScrollView,
TextInput, View } from "react-native";`

- [ ] **Step 3: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add src/screens/VideoGroupScreen.tsx
git commit -m "feat: 영상 그룹 화면에서 여행으로 만들기 연결"
```

- [ ] **Step 5: 시뮬레이터로 수동 확인**

일정이 있는 영상 그룹 화면에서 "여행으로 만들기" → 제목 입력 →
"확정" → `TripDetailScreen`으로 이동하며 방금 만든 일정이 그대로
보이는지 스크린샷으로 확인한다.

---

### Task 19: `HomeScreen`에 "내 여행 보기" 버튼 추가

**Files:**
- Modify: `src/screens/HomeScreen.tsx`

**Interfaces:**
- Consumes: `TripsList` 라우트(Task 14에서 이미 등록됨)

- [ ] **Step 1: 버튼 추가**

`HomeScreen.tsx`에서 "저장한 장소 보기" `Pressable` 바로 아래(로그아웃
버튼 위)에 추가:

```tsx
      <Pressable
        onPress={() => navigation.navigate("TripsList")}
        style={{ height: 48, borderRadius: 12, borderWidth: 1, borderColor: "#DEDED8", justifyContent: "center", alignItems: "center" }}
      >
        <AppText weight="medium">내 여행 보기</AppText>
      </Pressable>
```

- [ ] **Step 2: 타입 체크**

Run: `npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add src/screens/HomeScreen.tsx
git commit -m "feat: 홈 화면에 내 여행 보기 버튼 추가"
```

- [ ] **Step 4: 최종 전체 흐름 시뮬레이터 확인**

1. 홈에서 새 링크 제출 → 분석 화면 → `VideoGroupScreen`(일정 없음)
   도착 스크린샷
2. "일정 짜기" → 분석 화면 재사용 → `VideoGroupScreen`(일정 있음,
   날짜 탭) 도착 스크린샷
3. 순서 변경/요일 이동/동선 최적화 각각 실행 후 스크린샷, 화면을
   벗어났다가 돌아와도(재조회) 유지되는지 확인
4. "여행으로 만들기" → `TripDetailScreen` 도착 스크린샷
5. 검색 탭에서 장소 추가, 찜 탭에서 추가, AI 리뷰 요약 모달 확인
6. 홈 → "내 여행 보기" → 방금 만든 여행이 목록에 보이는지 확인
7. "새 여행 만들기"로 직접 여행 생성 확인

모든 단계가 정상 동작하면 계획 완료.
