# React Native 모바일 앱 2단계(일정/여행) 설계

날짜: 2026-09-06
상태: 승인됨 (브레인스토밍 완료)

## 배경

1단계 스펙(`2026-09-06-mobile-app-phase1-design.md`)에서 "내 여행/일정
관리"는 별도 스펙으로 명시적으로 미뤄뒀다(비목표). 1단계 구현 및
시뮬레이터 검증 도중, 장소 추출이 끝나면 앱이 전역 "저장한 장소" 목록으로
이동하는 임시 동작이 들어갔는데, 사용자가 이를 웹과 다르다고 지적했다:
웹(`trova-frontend`)은 추출이 끝나면 곧장 해당 영상의 장소 그룹 화면으로
이동하고, 거기서 "일정 짜기" → 일정 편집 → "여행으로 만들기" → 여행 상세
플로우로 이어진다. 이번 스펙은 이 전체 플로우를 모바일에 그대로
이식한다.

## 범위

**포함**: 영상 장소 그룹 화면, 일정 생성 트리거(기존 `ProcessingScreen`
재사용), 일정 편집(일자별 탭/순서변경/요일이동/동선최적화/날짜
추가), 여행 확정, 여행 목록/직접생성/상세(순서변경/삭제/방문시간·
이동수단·메모 편집/날씨체크/검색으로 장소추가/찜 탭/AI 리뷰 요약).

**비목표**:
- 백엔드 변경 — 필요한 엔드포인트가 이미 다 존재함(`PlacesController`,
  `TripController`). 이번 스펙은 모바일 전용 작업이다.
- 여행 상세의 동선 최적화 — 웹에도 없다(백엔드에 confirm된 Trip용
  최적화 엔드포인트 자체가 없음). 만들지 않는다.
- 드래그 제스처 기반 순서 변경 — 웹도 드래그가 아니라 위/아래 버튼 +
  요일 선택 드롭다운이다. 모바일도 동일하게 버튼 기반으로 만든다(별도
  제스처 라이브러리 불필요).
- 오프라인 캐싱, 푸시 알림 — 1단계와 동일하게 범위 밖.

## 결정 사항

- **네비게이션 개편**: `PlaceDetail`/`Map` 라우트를 폐기한다. 웹에는
  개별 장소 하나만 보여주는 페이지가 없다(`/places/[id]`의 `id`는 실제로
  URL 인코딩된 `sourceUrl`이지 개별 장소 id가 아니다). 대신 지도는 화면
  안에 인라인 WebView로 내장한다.
- **`VideoGroupScreen`이 `PlaceDetail`/`Map`을 대체**: 저장한 장소
  목록에서 항목을 탭하면 이제 해당 장소가 속한 영상의 전체 그룹
  화면(`VideoGroup{jobId}`)으로 이동한다.
- **`ProcessingScreen` 재사용**: 최초 추출과 일정 생성 둘 다 같은
  화면을 쓴다(웹도 동일 — `/processing/[jobId]`를 두 번 재사용).
  완료 시 도착지를 `PlacesList`에서 `VideoGroup{jobId}`로 바꾸는 것으로
  충분하다. 별도의 "일정 생성 중" 화면을 새로 만들지 않는다.
- **일정 편집은 낙관적 업데이트, 여행 상세는 재조회**: 웹의 실제 구현을
  그대로 따른다. `ItineraryView`(일정 편집, confirm 이전)는 로컬 상태를
  먼저 바꾸고 실패 시 롤백한다. `TripDetailView`(confirm 이후)는
  낙관적 업데이트 없이 매 변경마다 전체 재조회(`reload`)한다. 두 화면의
  동작 방식이 원래 다르므로 인위적으로 통일하지 않는다.
- **개별 `Place` 타입에 필드 추가**: 모바일 `Place` 타입에 `dayNumber`,
  `orderInDay`, `phone`, `roadAddress`, `kakaoCategoryName`,
  `kakaoPlaceUrl`을 추가한다(백엔드 `PlaceResponse`엔 이미 있음, 모바일
  클라이언트 타입만 누락돼 있었음).
- **신규 의존성**: `@react-native-community/datetimepicker`
  (`npx expo install`로 설치) — 여행 생성 폼의 시작일/종료일 선택용.
  MIT/BSD 계열 무료 라이브러리, 비용 원칙에 저촉 없음.
- **홈 화면에 "내 여행 보기" 버튼 추가** — 그렇지 않으면 여행 목록
  화면에 진입할 방법이 없다(여행 확정 후 자동 이동 외에는).

## 아키텍처

### 네비게이션 (`src/navigation/types.ts`)

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

`PlaceDetail`, `Map` 제거. `src/screens/PlaceDetailScreen.tsx`,
`src/screens/MapScreen.tsx` 삭제(대체된 로직은 아래 `InlineMap`
컴포넌트로 이동).

### `ProcessingScreen.tsx` 수정

완료 분기(`useEffect`에서 `navigation.replace("PlacesList")` 호출하는
부분)를 `navigation.replace("VideoGroup", { jobId })`로 변경. 이 한
줄이 오늘 사용자가 보고한 문제(추출 후 저장한 장소로 가버림)의 직접적인
수정이며, 동시에 "일정 짜기" 재요청 시에도 같은 화면이 재사용되므로
추가 분기 로직은 필요 없다.

### `src/lib/itinerary.ts` (신규)

웹 `src/lib/itinerary.ts`를 그대로 이식:

```ts
import type { Place } from "@/lib/api/places";

export function isItineraryGroup(places: Place[]): boolean {
  return places.some((p) => p.dayNumber !== null);
}

export function groupByDay(places: Place[]): Map<number, Place[]> {
  const days = new Map<number, Place[]>();
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

모바일의 `Place`는 개별 저장 완료된 항목만 존재하므로(`status` 필드
자체가 백엔드 `PlaceResponse`에 없음 — 파이프라인이 끝까지 성공해야만
행이 생성됨) "이 영상의 일정을 생성할 수 있는가" 판단은
`places.length > 0 && !isItineraryGroup(places)`로 충분하다. 웹처럼
개별 장소의 `status === "DONE"`을 확인할 필요가 없다.

### `src/lib/api/places.ts` 확장

`Place` 타입에 필드 추가:

```ts
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
  dayNumber: number | null;
  orderInDay: number | null;
  phone: string | null;
  roadAddress: string | null;
  kakaoCategoryName: string | null;
  kakaoPlaceUrl: string | null;
};
```

새 함수:

```ts
export async function generateItinerary(jobId: number): Promise<void> {
  const res = await apiFetch(`/api/places/videos/${jobId}/itinerary`, { method: "POST" });
  if (!res.ok) throw new Error(`POST /api/places/videos/${jobId}/itinerary failed: ${res.status}`);
}

export async function moveToDay(placeId: number, dayNumber: number): Promise<Place> {
  const res = await apiFetch(`/api/places/${placeId}/day`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ dayNumber }),
  });
  if (!res.ok) throw new Error(`PATCH /api/places/${placeId}/day failed: ${res.status}`);
  return res.json();
}

export async function reorderPlace(placeId: number, direction: "UP" | "DOWN"): Promise<Place> {
  const res = await apiFetch(`/api/places/${placeId}/order`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ direction }),
  });
  if (!res.ok) throw new Error(`PATCH /api/places/${placeId}/order failed: ${res.status}`);
  return res.json();
}

export async function optimizeRoute(jobId: number, day: number): Promise<Place[]> {
  const res = await apiFetch(`/api/places/videos/${jobId}/days/${day}/optimize-route`, { method: "POST" });
  if (!res.ok) throw new Error(`POST .../optimize-route failed: ${res.status}`);
  return res.json();
}
```

### `src/lib/api/trips.ts` (신규, 웹 그대로 이식 — `apiFetch` 사용으로만 교체)

```ts
export type Trip = { id: number; title: string; startDate: string | null; endDate: string | null };

export type TripPlace = {
  id: number; placeName: string; region: string | null; category: string | null;
  latitude: number | null; longitude: number | null; phone: string | null; address: string | null;
  visitOrder: number; source: "VIDEO" | "NORMAL"; googlePlaceId: string | null;
  visitStartTime: string | null; visitEndTime: string | null;
  arrivalTransportMode: "WALK" | "TRANSIT" | "CAR" | null; memo: string | null;
};

export type TripDay = { id: number; day: number; date: string | null; places: TripPlace[] };
export type TripDetail = Trip & { days: TripDay[] };

export async function confirmTrip(jobId: number, title: string, startDate: string | null): Promise<Trip>;
// POST /api/places/videos/{jobId}/confirm-trip  body: { title, startDate }
export async function createTrip(title: string, startDate: string, endDate: string): Promise<Trip>;
// POST /api/trips  body: { title, startDate, endDate }
export async function listTrips(): Promise<Trip[]>;
// GET /api/trips
export async function getTrip(id: number): Promise<TripDetail>;
// GET /api/trips/{id}
export async function deleteTrip(id: number): Promise<void>;
// DELETE /api/trips/{id}
export async function addTripPlace(tripId: number, day: number, googlePlaceId: string): Promise<TripPlace>;
// POST /api/trips/{tripId}/days/{day}/places  body: { googlePlaceId }
export async function removeTripPlace(id: number): Promise<void>;
// DELETE /api/trip-places/{id}
export async function reorderTripPlace(id: number, direction: "UP" | "DOWN"): Promise<TripPlace>;
// PATCH /api/trip-places/{id}/order  body: { direction }
export async function checkWeather(tripId: number, day: number): Promise<{ notified: boolean; message: string }>;
// POST /api/trips/{tripId}/days/{day}/weather-check
export async function updateTripPlaceDetails(
  id: number,
  patch: { visitStartTime?: string; visitEndTime?: string; arrivalTransportMode?: "WALK" | "TRANSIT" | "CAR"; memo?: string }
): Promise<TripPlace>;
// PATCH /api/trip-places/{id}/details  body: patch
```

### `src/lib/api/recommendations.ts` (신규)

```ts
export type RecommendedPlace = {
  id: number; googlePlaceId: string; name: string; category: string | null;
  mood: string | null; space: string | null; rating: number | null;
  userRatingCount: number | null; priceLevel: string | null;
  latitude: number | null; longitude: number | null; address: string | null;
};

export async function searchPlaces(query: string): Promise<RecommendedPlace[]>;
// GET /api/places/search?query=...

export type PlaceReviewSummary = Omit<RecommendedPlace, "mood" | "space"> & {
  highlights: string; pros: string[]; cons: string[]; hours: string | null;
  fee: string | null; tips: string[]; checklist: string[]; reviewSnippets: string[];
};

export async function getPlaceDetails(id: number): Promise<PlaceReviewSummary>;
// GET /api/places/{id}/details
```

(모바일에는 위치 기반 `recommend()`는 이번 스코프에서 쓰이지 않으므로
포함하지 않는다 — YAGNI.)

### `src/lib/api/bookmarks.ts` (신규)

```ts
export type Bookmark = {
  id: number; placeId: number; placeName: string; googlePlaceId: string;
  mood: string | null; space: string | null; latitude: number | null; longitude: number | null;
  createdAt: string;
};

export async function listBookmarks(): Promise<Bookmark[]>;      // GET /api/bookmarks
export async function addBookmark(placeId: number): Promise<Bookmark>; // POST /api/bookmarks {placeId}
export async function removeBookmark(id: number): Promise<void>; // DELETE /api/bookmarks/{id}
```

### 공용 컴포넌트

- **`src/components/InlineMap.tsx`** — `MapScreen`을 대체. 여러 개의
  `{id, latitude, longitude}` 핀 배열을 props로 받아 `WebView` +
  `buildKakaoMapHtml`(수정 없음 — 이미 여러 핀 렌더링을 지원함)를
  감싼다. `VideoGroupScreen`, `TripDetailScreen` 둘 다에서 활성 날짜의
  장소만 넘겨서 재사용한다.
- **`src/components/PlaceRow.tsx`** — 장소 카드 한 줄. 웹
  `PlaceMapSection`의 행 렌더링에 대응. props로 순서(index), 장소
  데이터, 다음 장소까지 거리(옵션), 그리고 편집 모드일 때만 보이는
  `onMoveUp`/`onMoveDown`(경계에서 비활성화)와 `onMoveDay`(옵션)를
  받는다. 거리 계산은 웹의 `haversineDistanceKm`을 그대로 포팅한 헬퍼
  함수(`src/lib/geo.ts`)로 뺀다.
- **`src/components/DayPickerSheet.tsx`** — "다른 날로 이동" 액션.
  Modal + 날짜 번호 버튼 리스트(현재 날짜는 비활성화 표시)로 구현.
  웹의 `<select>` 드롭다운과 동일한 정보만 다른 형태로 제공한다.
- **`src/components/PlaceReviewModal.tsx`** — `getPlaceDetails` 응답을
  보여주는 모달. 이 세션에서 이미 만든 웹의 구조화된 리뷰 요약
  팝업(하이라이트/장단점/운영시간/요금/팁/체크리스트)과 동일한 정보
  구조를 그대로 사용한다.

### `VideoGroupScreen.tsx` (신규) — 웹 `/places/[id]`에 대응

- `useQuery(["places"], getPlaces)`로 전체 장소를 가져와
  `places.filter(p => p.jobId === route.params.jobId)`로 이 영상의
  장소만 추출한다(전역 캐시를 공유하므로 `PlacesListScreen`과 별도
  요청을 만들지 않는다).
- **일정 없음** (`!isItineraryGroup(group)`): `InlineMap`(전체 핀) +
  `PlaceRow` 리스트(편집 컨트롤 없음, 순서는 배열 순서 그대로) +
  "일정 짜기" 버튼(`group.length > 0`일 때만 노출) → `generateItinerary(jobId)`
  → `navigation.navigate("Processing", { jobId })`.
- **일정 있음** (`isItineraryGroup(group)`): 웹 `ItineraryView`의
  로직을 그대로 포팅.
  - 로컬 상태: `localPlaces`(낙관적 사본), `emptyDayNumbers`(클라이언트
    전용 빈 날짜), `activeDay`, `actionPending`(변경 중 전체 버튼
    비활성화), `error`.
  - 날짜 탭: `groupByDay(localPlaces)` ∪ `emptyDayNumbers`.
  - "+ 날짜 추가": `emptyDayNumbers`에 다음 번호 추가, 활성 탭으로 전환
    (API 호출 없음).
  - 빈 날짜 탭 삭제: 로컬에서만 제거(API 호출 없음).
  - 순서 변경(`PlaceRow`의 위/아래 버튼): 인접 스왑만 허용(경계는
    비활성화), 로컬 상태 먼저 바꾸고 `reorderPlace(id, direction)` 호출,
    실패 시 이전 스냅샷으로 롤백 + "순서를 바꾸지 못했어요. 다시
    시도해주세요." 에러.
  - 요일 이동(`DayPickerSheet`): 로컬 상태 먼저 바꾸고
    `moveToDay(id, dayNumber)` 호출, 실패 시 롤백 + "장소를 옮기지
    못했어요. 다시 시도해주세요." 에러. 원래 날짜가 비게 되면
    활성 탭을 이동 대상 날짜로 전환(웹과 동일).
  - 동선 최적화 버튼: `activePlaces.length >= 2`일 때만 활성화,
    `optimizeRoute(jobId, activeDay)` 호출 후 반환된 순서로 로컬 상태
    갱신. 실패 시 "동선을 최적화하지 못했어요. 다시 시도해주세요."
  - 미배정 장소(`dayNumber === null`)는 날짜 탭 아래 별도 섹션에
    표시(요일 이동만 가능, 순서 변경 없음).
  - "여행으로 만들기" 버튼: 인라인 폼(제목 텍스트 입력 + 시작일 선택,
    `@react-native-community/datetimepicker`) → `confirmTrip(jobId, title, startDate)`
    → 성공 시 `navigation.replace("TripDetail", { id: trip.id })`.

### `TripsListScreen.tsx` (신규) — 웹 `/trips`

- `useQuery(["trips"], listTrips)` → `Trip[]`을 행으로 렌더링(제목 +
  `startDate`가 있으면 `startDate ~ endDate`). 탭하면
  `navigation.navigate("TripDetail", { id: trip.id })`.
- "새 여행 만들기" 버튼 → `navigation.navigate("NewTrip")`.

### `NewTripScreen.tsx` (신규) — 웹 `/trips/new`

- 필드: 제목(TextInput), 시작일/종료일(`DateTimePicker`, 기본값
  오늘/오늘, 종료일이 시작일보다 빠르면 자동으로 시작일에 맞춤 —
  웹과 동일한 보정 로직).
- 제출: `!title.trim()`이거나 제출 중이면 비활성화. `createTrip(title, startDate, endDate)`
  → 성공 시 `navigation.replace("TripDetail", { id: trip.id })`.

### `TripDetailScreen.tsx` (신규, 가장 큰 화면) — 웹 `/trips/[id]` + `TripDetailView`

- `useQuery(["trip", id], () => getTrip(id))`. 모든 변경 후
  `queryClient.invalidateQueries(["trip", id])`로 재조회(`reload`) —
  낙관적 업데이트를 쓰지 않는다(웹과 동일한 선택).
- 날짜 탭: `trip.days`에서 고정 목록(추가/삭제 없음, 날짜 범위로
  생성 시점에 이미 확정됨).
- 활성 날짜의 `InlineMap` + `PlaceRow` 리스트. 각 행에 위/아래 순서
  변경(`reorderTripPlace`), 삭제 버튼(확인 `Alert` 후
  `removeTripPlace`), 탭하면 펼쳐지는 인라인 편집 영역(방문 시작/종료
  시간, 이동 수단 `WALK`/`TRANSIT`/`CAR` 선택, 메모 텍스트) →
  `updateTripPlaceDetails`(변경된 필드만 patch로 전송).
- 동선 최적화 버튼 없음(비목표 참고).
- 날씨 체크 버튼 → `checkWeather(trip.id, activeDay)` → 결과 메시지를
  `Alert.alert`로 표시.
- 상단 탭 두 개:
  - **검색**: TextInput + 검색 버튼 → `searchPlaces(query)` →
    `RecommendedPlace[]` 리스트, 각 행에 "추가" 버튼
    (`addTripPlace(trip.id, activeDay, item.googlePlaceId)` → 재조회)와
    찜 토글(`addBookmark(item.id)`), 행 탭 시 `PlaceReviewModal`(내부에서
    `getPlaceDetails(item.id)` 호출)로 AI 리뷰 요약 표시.
  - **찜**: `listBookmarks()` → 같은 카드 UI, "추가" 버튼(같은 흐름) +
    제거 버튼(`removeBookmark(id)`).

### `PlacesListScreen.tsx` 수정 (기존 파일, 최소 변경)

행 탭 대상만 변경: `navigation.navigate("PlaceDetail", { id: item.id })`
→ `navigation.navigate("VideoGroup", { jobId: item.jobId })`. 나머지
(펜딩 잡 카드, 폴링)는 그대로 유지.

### `HomeScreen.tsx` 수정 (기존 파일, 최소 변경)

"저장한 장소 보기" 버튼 아래에 "내 여행 보기" 버튼 추가 →
`navigation.navigate("TripsList")`.

## 에러 처리

- 네트워크/서버 오류는 화면별로 로컬 `error` 상태에 담아 카드/폼 위에
  빨간 텍스트로 표시(1단계와 동일한 패턴). 전역 토스트 라이브러리는
  도입하지 않는다.
- `VideoGroupScreen`의 일정 편집 동작은 실패 시 반드시 이전 로컬 상태로
  롤백한다(위 아키텍처 절 참고) — 화면과 서버 상태가 어긋난 채로 남지
  않도록 하는 것이 핵심이다.
- `TripDetailScreen`은 롤백 대신 재조회이므로, 실패해도 서버 상태
  그대로를 다시 보여주는 것으로 일관성이 자동 확보된다.
- 401은 기존 `apiFetch`의 `unauthorizedHandler`가 전역으로 처리(변경
  없음).

## 테스트 전략

- `npx tsc --noEmit` — 매 작업 후 필수(1단계와 동일한 기준).
- 자동화된 RN 컴포넌트 테스트는 만들지 않는다(1단계 결정 유지 — 테스트
  라이브러리를 별도 작업으로 먼저 추가하지 않는 한).
- 수동 검증: 이미 켜져 있는 iOS 시뮬레이터 + `xcrun simctl io booted
  screenshot`로 실제 화면을 캡처해 확인하는 방식을 이번에도 사용한다.
  최소 확인 시나리오:
  1. 링크 제출 → 분석 화면 → `VideoGroupScreen`(일정 없음, 지도+목록)
     도착
  2. "일정 짜기" → 분석 화면 재사용 → `VideoGroupScreen`(일정 있음,
     날짜 탭) 도착
  3. 순서 변경/요일 이동/동선 최적화가 실제로 서버에 반영되는지(새로고침
     후에도 유지되는지)
  4. "여행으로 만들기" → `TripDetailScreen` 도착, 날짜 탭/장소 표시 확인
  5. 검색 탭에서 장소 추가, 찜 탭에서 추가, 리뷰 요약 모달 확인
  6. `TripsListScreen`에서 방금 만든 여행이 보이는지, `NewTripScreen`으로
     직접 여행 생성이 되는지

## 사용자가 직접 해야 하는 일 (Claude가 대신 할 수 없음)

없음 — 백엔드 변경이 없고, 신규 의존성도 무료 오픈소스
라이브러리(`@react-native-community/datetimepicker`)라 별도 계정/키
설정이 필요 없다.

## 커밋 전 확인

`npx tsc --noEmit` (trova-app). 백엔드는 변경하지 않으므로
`./gradlew build`는 이번 스펙 범위에서 해당 없음.
