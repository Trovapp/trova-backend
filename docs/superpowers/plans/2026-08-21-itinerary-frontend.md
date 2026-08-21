# 일정형 영상 자동 일정 뷰 — 프론트엔드 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /api/places` 응답의 `dayNumber`/`orderInDay`를 이용해서,
같은 영상에서 나온 장소가 일정형이면 "1일차/2일차" 탭 UI로, 각 탭
안에서는 카카오맵에 순서대로 핀을 찍고 직선으로 연결해서 보여준다.
일정형이 아니면 기존 플랫 리스트 그대로 보인다.

**Architecture:** 순수 함수(그룹핑 로직) + 프레젠테이션 컴포넌트
(`KakaoMap`, `ItineraryView`)로 분리하고, `/places` 페이지는 이걸
조합만 한다. 카카오맵 JS SDK는 이 계획에서 처음 연동한다(도메인 등록
전제).

**Tech Stack:** Next.js(App Router) + TypeScript + Tailwind, 카카오맵
JavaScript SDK(무료, 이미 활성화된 카카오 앱의 첫 번째 앱 무료 쿼터
적용). 작업은 `trova-frontend` 레포에서 진행한다.

**Spec:** `docs/superpowers/specs/2026-08-21-itinerary-view-design.md`
(trova-backend 레포에 있음 — 프론트/백엔드 공통 스펙)

**의존성:** 이 계획은 `docs/superpowers/plans/2026-08-21-itinerary-backend.md`
(Task 1~5)가 완료되어 `GET /api/places` 응답에 `dayNumber`/`orderInDay`가
포함된 뒤에 실행한다. Task F1~F4(타입/로직)는 백엔드 완료 전에도
`npm run lint`/`npm run build`만으로 작성·검증 가능하지만, Task
F5~F7(지도/탭 UI)의 브라우저 확인은 실제 일정형 영상이 처리된 데이터가
있어야 의미가 있다.

## Global Constraints

- 카카오 길찾기(경로) API는 쓰지 않는다 — 핀을 순서대로 잇는 **직선
  (Polyline)** 만 그린다(비용 0원 원칙, 스펙 참고).
- 새 프론트엔드 브랜치(`feat/itinerary-view`)를 `main`에서 새로 딴다
  (`feat/connect-places-api`, `feat/mypage`는 각자 별도 PR 진행 중이라
  섞지 않음).
- **이 레포에는 자동화 테스트 프레임워크가 없다**(기존 관례 — Jest/
  Vitest 등 미설치, `npm run lint`/`npm run build`만 있음). 새로
  도입하지 않는다. 순수 로직(Task F3)도 포함해서 모든 태스크는
  `npm run lint`와 `npm run build`(타입체크 포함)로 검증하고, 화면
  요소(지도/탭)는 브라우저로 수동 확인한다. 이건 placeholder가 아니라
  이 레포 다른 모든 컴포넌트(PlaceCard, CategoryBadge 등)와 동일한
  검증 방식이다.
- 카카오맵 JS 키는 **REST API 키와 다른 키**(콘솔의 "JavaScript 키")다.
  Kakao Developers 콘솔에서 새로 확인/발급해서 `.env.local`에
  `NEXT_PUBLIC_KAKAO_MAP_JS_KEY`로 추가해야 한다 — 이건 사용자만 할 수
  있는 작업(콘솔 접근 필요)이므로 Task F5 시작 전에 확인한다.
- 커밋 전 `npm run lint && npm run build` 통과 확인(CLAUDE.md 규칙).

---

### Task F1: `SavedPlace` 타입에 dayNumber/orderInDay 추가

**Files:**
- Modify: `src/lib/types.ts`

**Interfaces:**
- Produces: `SavedPlace.dayNumber: number | null`,
  `SavedPlace.orderInDay: number | null` — 이후 모든 태스크가 사용

- [ ] **Step 1: 타입 필드 추가**

`src/lib/types.ts`의 `SavedPlace` 타입에 두 필드 추가:

```typescript
export type SavedPlace = {
  id: string;
  sourceUrl: string;
  sourcePlatform: "INSTAGRAM" | "YOUTUBE";
  placeName: string;
  region: string;
  category: string;
  latitude: number;
  longitude: number;
  status: "PENDING" | "PROCESSING" | "DONE" | "FAILED";
  createdAt: string;
  dayNumber: number | null;
  orderInDay: number | null;
};
```

- [ ] **Step 2: 타입체크 확인**

Run: `npm run build`
Expected: 에러 발생 — `src/lib/api/places.ts`의 `fromPlaceResponse`/
`fromPendingJobResponse`가 새 필드를 채우지 않아서 `SavedPlace` 타입에
안 맞음(Task F2에서 고침). 지금은 이 에러가 나는 게 정상이다.

- [ ] **Step 3: 커밋은 Task F2와 함께**

타입만 바꾸면 빌드가 깨지는 상태이므로, 이 태스크는 커밋하지 않고
바로 Task F2로 이어간다.

---

### Task F2: `places.ts`가 dayNumber/orderInDay를 매핑

**Files:**
- Modify: `src/lib/api/places.ts`

**Interfaces:**
- Consumes: `SavedPlace.dayNumber`/`orderInDay`(Task F1), 백엔드
  `PlaceResponse`의 `dayNumber`/`orderInDay`(백엔드 계획 Task 5)
- Produces: `getPlaces()`가 반환하는 모든 `SavedPlace`에
  dayNumber/orderInDay가 채워짐(pending 항목은 항상 null)

- [ ] **Step 1: `PlaceResponse` 타입에 필드 추가**

`src/lib/api/places.ts`의 `PlaceResponse` 타입 교체:

```typescript
type PlaceResponse = {
  id: number;
  placeName: string;
  region: string | null;
  category: string | null;
  latitude: number | null;
  longitude: number | null;
  sourceUrl: string;
  sourcePlatform: "INSTAGRAM" | "YOUTUBE";
  createdAt: string;
  dayNumber: number | null;
  orderInDay: number | null;
};
```

- [ ] **Step 2: `fromPlaceResponse`/`fromPendingJobResponse` 수정**

```typescript
function fromPlaceResponse(place: PlaceResponse): SavedPlace {
  return {
    id: String(place.id),
    sourceUrl: place.sourceUrl,
    sourcePlatform: place.sourcePlatform,
    placeName: place.placeName,
    region: place.region ?? "",
    category: toCategoryLabel(place.category),
    latitude: place.latitude ?? 0,
    longitude: place.longitude ?? 0,
    status: "DONE",
    createdAt: place.createdAt,
    dayNumber: place.dayNumber,
    orderInDay: place.orderInDay,
  };
}

function fromPendingJobResponse(job: PendingJobResponse): SavedPlace {
  return {
    id: String(job.jobId),
    sourceUrl: job.sourceUrl,
    sourcePlatform: job.sourcePlatform,
    placeName: "",
    region: "",
    category: "",
    latitude: 0,
    longitude: 0,
    status: job.status,
    createdAt: job.createdAt,
    dayNumber: null,
    orderInDay: null,
  };
}
```

- [ ] **Step 3: 빌드로 확인**

Run: `npm run lint && npm run build`
Expected: 통과(Task F1에서 났던 타입 에러가 사라짐)

- [ ] **Step 4: 커밋**

```bash
git add src/lib/types.ts src/lib/api/places.ts
git commit -m "feat: SavedPlace에 dayNumber/orderInDay 추가 및 API 매핑"
```

---

### Task F3: 일정 그룹핑 순수 함수

**Files:**
- Create: `src/lib/itinerary.ts`

**Interfaces:**
- Consumes: `SavedPlace[]`(Task F1/F2)
- Produces: `groupBySourceUrl(places): Map<string, SavedPlace[]>`,
  `isItineraryGroup(group): boolean`, `groupByDay(group): Map<number, SavedPlace[]>`
  (정렬된 상태로 반환) — Task F6(`ItineraryView`), F7(`places/page.tsx`)에서 사용

- [ ] **Step 1: 파일 작성**

`src/lib/itinerary.ts` 새로 작성:

```typescript
import type { SavedPlace } from "@/lib/types";

export function groupBySourceUrl(places: SavedPlace[]): Map<string, SavedPlace[]> {
  const groups = new Map<string, SavedPlace[]>();
  for (const place of places) {
    const existing = groups.get(place.sourceUrl) ?? [];
    existing.push(place);
    groups.set(place.sourceUrl, existing);
  }
  return groups;
}

export function isItineraryGroup(places: SavedPlace[]): boolean {
  return places.some((place) => place.dayNumber !== null);
}

export function groupByDay(places: SavedPlace[]): Map<number, SavedPlace[]> {
  const days = new Map<number, SavedPlace[]>();
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
```

- [ ] **Step 2: 브라우저 콘솔로 수동 검증**

자동화 테스트가 없으므로(Global Constraints 참고), 개발 서버를 띄우고
브라우저 개발자 도구 콘솔에서 직접 import해서 확인하기는 어려우니
(ESM 모듈이라), 대신 Task F7에서 실제 `/places` 페이지에 연결한 뒤
정렬/그룹핑이 맞는지 눈으로 확인한다. 이 태스크 자체는 아래로 검증:

Run: `npm run lint && npm run build`
Expected: 통과(타입 에러 없음, 미사용 export 경고 없음 — 아직 아무
데서도 import 안 했으므로 lint가 미사용 파일에 대해선 통과해야 함)

- [ ] **Step 3: 커밋**

```bash
git add src/lib/itinerary.ts
git commit -m "feat: 일정 그룹핑 순수 함수(groupBySourceUrl/isItineraryGroup/groupByDay) 추가"
```

---

### Task F4: 카카오맵 SDK 타입 선언 + 로더

**Files:**
- Create: `src/lib/kakao.d.ts`
- Create: `src/lib/kakaoMapLoader.ts`

**Interfaces:**
- Produces: `loadKakaoMaps(): Promise<void>`(SDK 로드 완료 시
  resolve) — Task F5(`KakaoMap`)에서 사용. 전역 `window.kakao.maps`
  타입도 이 태스크에서 선언.

- [ ] **Step 1: 카카오맵 SDK 최소 타입 선언**

`src/lib/kakao.d.ts` 새로 작성:

```typescript
export {};

declare global {
  interface Window {
    kakao: {
      maps: {
        load: (callback: () => void) => void;
        LatLng: new (lat: number, lng: number) => unknown;
        LatLngBounds: new () => { extend: (latlng: unknown) => void };
        Map: new (
          container: HTMLElement,
          options: { center: unknown; level: number }
        ) => { setBounds: (bounds: unknown) => void };
        Marker: new (options: { position: unknown; map?: unknown }) => {
          setMap: (map: unknown | null) => void;
        };
        Polyline: new (options: {
          path: unknown[];
          strokeWeight?: number;
          strokeColor?: string;
          strokeOpacity?: number;
        }) => { setMap: (map: unknown | null) => void };
      };
    };
  }
}
```

- [ ] **Step 2: SDK 로더 작성**

`src/lib/kakaoMapLoader.ts` 새로 작성:

```typescript
let loadPromise: Promise<void> | null = null;

export function loadKakaoMaps(): Promise<void> {
  if (loadPromise) {
    return loadPromise;
  }

  loadPromise = new Promise((resolve, reject) => {
    const appKey = process.env.NEXT_PUBLIC_KAKAO_MAP_JS_KEY;
    if (!appKey) {
      reject(new Error("NEXT_PUBLIC_KAKAO_MAP_JS_KEY가 설정되지 않았어요."));
      return;
    }

    const existing = document.querySelector<HTMLScriptElement>(
      "script[data-kakao-maps-sdk]"
    );
    if (existing) {
      existing.addEventListener("load", () => window.kakao.maps.load(() => resolve()));
      return;
    }

    const script = document.createElement("script");
    script.dataset.kakaoMapsSdk = "true";
    script.src = `//dapi.kakao.com/v2/maps/sdk.js?appkey=${appKey}&autoload=false`;
    script.async = true;
    script.onload = () => window.kakao.maps.load(() => resolve());
    script.onerror = () => reject(new Error("카카오맵 SDK 로드 실패"));
    document.head.appendChild(script);
  });

  return loadPromise;
}
```

- [ ] **Step 3: 빌드로 확인**

Run: `npm run lint && npm run build`
Expected: 통과

- [ ] **Step 4: 커밋**

```bash
git add src/lib/kakao.d.ts src/lib/kakaoMapLoader.ts
git commit -m "feat: 카카오맵 JS SDK 타입 선언 및 로더 추가"
```

---

### Task F5: `KakaoMap` 컴포넌트 (마커 + 직선 연결)

**Files:**
- Create: `src/components/KakaoMap.tsx`

**Interfaces:**
- Consumes: `loadKakaoMaps()`(Task F4)
- Produces: `<KakaoMap pins={MapPin[]} />` 컴포넌트,
  `MapPin = { id: string; latitude: number; longitude: number }` —
  Task F6(`ItineraryView`)에서 사용

**시작 전 확인:** Kakao Developers 콘솔(https://developers.kakao.com/console/app)
에서 이 프로젝트 앱의 "JavaScript 키"를 확인하고, "플랫폼 > Web"에
`http://localhost:3001`(로컬 개발 도메인, 실제 포트에 맞게)이 등록돼
있는지 확인한다. 안 돼 있으면 추가한다. 확인한 키를 `.env.local`에
추가:

```
NEXT_PUBLIC_KAKAO_MAP_JS_KEY=여기에_JavaScript_키
```

(`.env.local`은 gitignore돼 있으므로 커밋되지 않음 — 각자 로컬에
설정)

- [ ] **Step 1: 컴포넌트 작성**

`src/components/KakaoMap.tsx` 새로 작성:

```tsx
"use client";

import { useEffect, useRef, useState } from "react";
import { loadKakaoMaps } from "@/lib/kakaoMapLoader";

export type MapPin = {
  id: string;
  latitude: number;
  longitude: number;
};

export function KakaoMap({ pins }: { pins: MapPin[] }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    loadKakaoMaps()
      .then(() => {
        if (cancelled || !containerRef.current) return;

        const validPins = pins.filter(
          (pin) => pin.latitude !== 0 || pin.longitude !== 0
        );
        if (validPins.length === 0) return;

        const center = new window.kakao.maps.LatLng(
          validPins[0].latitude,
          validPins[0].longitude
        );
        const map = new window.kakao.maps.Map(containerRef.current, {
          center,
          level: 6,
        });

        const bounds = new window.kakao.maps.LatLngBounds();
        const path = validPins.map((pin) => {
          const position = new window.kakao.maps.LatLng(pin.latitude, pin.longitude);
          new window.kakao.maps.Marker({ position, map });
          bounds.extend(position);
          return position;
        });

        if (path.length > 1) {
          new window.kakao.maps.Polyline({
            path,
            strokeWeight: 3,
            strokeColor: "#FF6B4A",
            strokeOpacity: 0.8,
          }).setMap(map);
        }

        map.setBounds(bounds);
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : "지도를 불러오지 못했어요.");
        }
      });

    return () => {
      cancelled = true;
    };
  }, [pins]);

  if (error) {
    return (
      <div className="flex h-64 items-center justify-center rounded-xl border border-border-subtle text-sm text-ink-muted">
        {error}
      </div>
    );
  }

  return (
    <div
      ref={containerRef}
      className="h-64 w-full rounded-xl border border-border-subtle"
    />
  );
}
```

- [ ] **Step 2: 빌드로 확인**

Run: `npm run lint && npm run build`
Expected: 통과

- [ ] **Step 3: 브라우저로 수동 확인**

아직 이 컴포넌트를 쓰는 화면이 없으므로(Task F6/F7에서 연결), 이
단계에서는 임시로 아무 페이지(예: `src/app/page.tsx`)에
`<KakaoMap pins={[{ id: "1", latitude: 35.1587, longitude: 129.1604 }, { id: "2", latitude: 35.1532, longitude: 129.1186 }]} />`
를 잠깐 추가해서 `npm run dev`로 띄우고 브라우저에서 지도가 뜨고 핀
2개 + 직선이 보이는지 확인한 뒤, **확인이 끝나면 그 임시 코드는
지운다**(커밋에 포함하지 않음).

- [ ] **Step 4: 커밋**

```bash
git add src/components/KakaoMap.tsx
git commit -m "feat: 카카오맵 마커/직선 연결 컴포넌트(KakaoMap) 추가"
```

---

### Task F6: `ItineraryView` 컴포넌트 (일자별 탭)

**Files:**
- Create: `src/components/ItineraryView.tsx`

**Interfaces:**
- Consumes: `groupByDay`(Task F3), `KakaoMap`(Task F5), `PlaceCard`
  (기존 `src/components/PlaceCard.tsx`)
- Produces: `<ItineraryView places={SavedPlace[]} />` — 한 영상(같은
  sourceUrl)에서 나온 일정형 장소 목록을 받아 탭 UI로 렌더링. Task
  F7(`places/page.tsx`)에서 사용.

- [ ] **Step 1: 컴포넌트 작성**

`src/components/ItineraryView.tsx` 새로 작성:

```tsx
"use client";

import { useState } from "react";
import type { SavedPlace } from "@/lib/types";
import { groupByDay } from "@/lib/itinerary";
import { KakaoMap } from "@/components/KakaoMap";
import { PlaceCard } from "@/components/PlaceCard";

export function ItineraryView({ places }: { places: SavedPlace[] }) {
  const days = groupByDay(places);
  const dayNumbers = Array.from(days.keys()).sort((a, b) => a - b);
  const [activeDay, setActiveDay] = useState(dayNumbers[0]);

  const activePlaces = days.get(activeDay) ?? [];

  return (
    <div className="flex flex-col gap-4 rounded-xl border border-border-subtle p-4">
      <div className="flex flex-wrap gap-2">
        {dayNumbers.map((day) => (
          <button
            key={day}
            type="button"
            onClick={() => setActiveDay(day)}
            className={`rounded-full px-4 py-1.5 text-sm font-medium transition-colors ${
              day === activeDay
                ? "bg-accent text-white"
                : "bg-bg-muted text-ink-muted hover:text-ink"
            }`}
          >
            {day}일차
          </button>
        ))}
      </div>

      <KakaoMap
        pins={activePlaces.map((place) => ({
          id: place.id,
          latitude: place.latitude,
          longitude: place.longitude,
        }))}
      />

      <ul className="flex flex-col gap-3">
        {activePlaces.map((place) => (
          <PlaceCard key={place.id} place={place} />
        ))}
      </ul>
    </div>
  );
}
```

- [ ] **Step 2: 빌드로 확인**

Run: `npm run lint && npm run build`
Expected: 통과

- [ ] **Step 3: 커밋**

```bash
git add src/components/ItineraryView.tsx
git commit -m "feat: 일자별 탭 + 지도 + 리스트를 보여주는 ItineraryView 추가"
```

---

### Task F7: `/places` 페이지에 일정 뷰 연결

**Files:**
- Modify: `src/app/places/page.tsx`

**Interfaces:**
- Consumes: `groupBySourceUrl`, `isItineraryGroup`(Task F3),
  `ItineraryView`(Task F6)

- [ ] **Step 1: 렌더링 부분을 그룹 기반으로 교체**

`src/app/places/page.tsx`의 import에 추가:

```typescript
import { groupBySourceUrl, isItineraryGroup } from "@/lib/itinerary";
import { ItineraryView } from "@/components/ItineraryView";
```

`places.length === 0 ? (...) : (` 이후의 리스트 렌더링 블록을 아래로
교체:

```tsx
      ) : (
        <ul className="flex flex-col gap-3">
          {Array.from(groupBySourceUrl(places).entries()).map(([sourceUrl, group]) =>
            isItineraryGroup(group) ? (
              <li key={sourceUrl}>
                <ItineraryView places={group} />
              </li>
            ) : (
              group.map((place) => <PlaceCard key={place.id} place={place} />)
            )
          )}
        </ul>
      )}
```

(파일 전체를 손대는 게 아니라, 기존 `places.map((place) => <PlaceCard key={place.id} place={place} />)`
한 줄만 위 블록으로 바꾸는 것 — 나머지 로딩/비로그인/빈 상태 분기는
그대로 둔다)

**알려진 한계(구현 안 함, YAGNI):** 같은 `sourceUrl`을 두 번 제출해서
하나는 완료(일정형)되고 다른 하나는 아직 pending인 극히 드문 경우,
pending 항목이 그룹핑 로직상 일시적으로 안 보일 수 있다. 실사용
빈도가 낮고 완료되면 정상적으로 나타나므로 이번 범위에서는 처리하지
않는다.

- [ ] **Step 2: 빌드로 확인**

Run: `npm run lint && npm run build`
Expected: 통과

- [ ] **Step 3: 브라우저로 end-to-end 확인**

전제: 백엔드 계획(`2026-08-21-itinerary-backend.md`)이 완료돼서 로컬
백엔드가 떠 있고, 실제 일정형 영상 URL을 하나 제출해서 처리 완료된
상태(또는 Task 1에서 썼던 가짜 자막으로 로컬 파이프라인을 재현해
DB에 데이터가 있는 상태).

`npm run dev`로 프론트를 띄우고(백엔드도 같이 기동), 로그인 후
`/places`에서:
1. 일정형으로 처리된 영상은 "1일차/2일차" 탭이 보이고, 탭 전환 시
   해당 날짜 장소만 리스트/지도에 나오는지
2. 지도에 핀이 순서대로 찍히고 핀 사이에 직선이 그어지는지
3. 일정형이 아닌 기존 장소는 그대로 플랫 리스트로 보이는지

셋 다 확인한다.

- [ ] **Step 4: 커밋**

```bash
git add src/app/places/page.tsx
git commit -m "feat: /places에 일자별 일정 뷰 연결"
```

---

### Task F8: 문서 갱신

**Files:**
- Modify: `TODO.md`
- Modify: `PROGRESS.md`

- [ ] **Step 1: TODO.md/PROGRESS.md 갱신**

`TODO.md`에 이 계획으로 완료된 항목을 `[x]`로 추가/이동(카카오맵 SDK
연동, 일정 자동 생성 프론트 부분). `PROGRESS.md`에 오늘 날짜로 한
것/왜 이렇게 했는지/막힌 것(예: 실배포 도메인 등록은 아직 안 함,
배포 시 콘솔에서 추가 필요) 섹션 추가.

- [ ] **Step 2: 커밋 및 push 여부는 사용자에게 확인**

```bash
git add TODO.md PROGRESS.md
git commit -m "docs: 일정형 영상 프론트엔드 작업 내역 기록"
```

push, PR 생성 여부는 CLAUDE.md 관례대로 사용자에게 먼저 물어보고
진행한다.
