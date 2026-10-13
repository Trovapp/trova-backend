# 관리자 조치(대시보드 2단계) 관리자 웹 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관리자 웹(Trovapp/trova-frontend `admin-web/`)에 "사용자" 탭(찾기·상세·조치)과 "조치 기록" 탭을 더해, 백엔드 #167의 API로 미리보기 → 사유·확인 → 실행을 하게 한다.

**Architecture:** API 함수는 `src/lib/api/admin.ts`(읽기)와 새 `src/lib/api/actions.ts`(조치·POST)로, 화면은 `src/components/tabs/UsersTab.tsx`(검색·목록), `src/components/users/UserDetail.tsx`(상세), `src/components/users/ActionDialog.tsx`(미리보기·실행 공통 창), `src/components/tabs/ActionLogTab.tsx`. 조치 결과 상태 판단(`REAUTH_REQUIRED`·409·400)은 순수 함수 `src/lib/actionError.ts`로 빼 `node --test`로 시험한다.

**Tech Stack:** Next.js 정적 내보내기, React, Tailwind, node --test(순수 함수만).

**Spec:** 백엔드 `docs/superpowers/specs/2026-10-11-admin-dashboard-design.md`(2단계·2단계 보강), `docs/adr/0014-admin-actions-and-suspension.md`, 백엔드 계획 `docs/superpowers/plans/2026-10-12-admin-actions.md`(API 형식)

## Global Constraints

- 토큰은 지금처럼 sessionStorage, 요청은 Bearer·`credentials: "omit"`.
- 화면 문구는 한국어 존댓말 짧게. 위험 조치 버튼은 빨간 테두리, 실행 버튼은 사유(1~300자)와 확인 글자가 맞을 때만 활성.
- 실행 응답 403 `REAUTH_REQUIRED` → 창 안에 "로그인한 지 10분이 지나 다시 로그인해야 해요" + 로그인 버튼(토큰 지우고 `/login/?error=reauth`). 로그인 화면에 `reauth` 문구 추가.
- 409·400은 창 안에 서버 `error` 문구를 그대로, 확인 토큰은 한 번 쓰면 사라지므로 "미리보기 다시" 버튼을 보인다.
- 닉네임은 서버가 가린 값 그대로 보인다(화면에서 다시 가리지 않음).
- 휴대폰 폭(390px)에서 가로 넘침 없음, 라이트·다크 둘 다.
- 커밋 `타입: 내용`만, `npm run build`·`npm test`·`npx tsc --noEmit`·`npx eslint src` 통과.

## Review Focus

1. 실행 버튼을 빠르게 두 번 누름 → 요청은 한 번(누르는 순간 비활성). Task 3에서 `busy` 상태로 막고 수동 확인.
2. 사용자 상세에서 계정 삭제 성공 → 상세를 닫고 목록에서 빠짐(다시 불렀을 때 404를 오류로 보이지 않음). Task 3.
3. 정지된 사용자 → 상세 위에 "정지됨" 표시와 "정지 풀기"만, 정지 버튼 숨김. 관리자 계정(`admin: true`) → 정지·삭제 버튼 숨김. Task 2.
4. 미리보기 5분 지나 실행 → 409 문구 + "미리보기 다시". Task 3.
5. 검색어 비움 → 최근 가입 20명. Task 2.

---

### Task 1: API 함수와 조치 오류 판단

**Files:**
- Modify: `admin-web/src/lib/api/admin.ts`(타입·읽기 함수, `adminPost` 추가)
- Create: `admin-web/src/lib/actionError.ts`, `admin-web/src/lib/actionError.test.ts`
- Modify: `admin-web/package.json`(test 목록에 새 테스트)

**Interfaces:**
```ts
export type AdminUserRow = { id: number; provider: string; nickname: string; createdAt: string; suspended: boolean; jobs: number; trips: number };
export type AdminUserDetail = {
  id: number; provider: string; nickname: string; createdAt: string; lastActiveAt: string; suspendedAt: string | null; admin: boolean;
  counts: { jobs: number; savedPlaces: number; trips: number; drafts: number };
  recentJobs: { id: number; status: string; failureReason: string | null; createdAt: string; retryable: boolean }[];
  trips: { id: number; title: string; startDate: string; endDate: string }[];
  autoDrafts: { id: number; status: string; dismissed: boolean; createdAt: string }[];
};
export type ActionName = "delete-trip" | "delete-account" | "retry-job" | "hide-draft" | "suspend-user" | "unsuspend-user";
export type ActionPreview = { action: string; targetId: number; targetUserId: number; impact: string; confirmText: string | null; confirmToken: string; expiresInSeconds: number };
export type ActionLog = { id: number; createdAt: string; actorUserId: number | null; action: string; targetId: number | null; targetUserId: number | null; reason: string | null; result: string | null };

export const searchUsers = (q: string) => adminGet<{ users: AdminUserRow[] }>(`/api/admin/users?q=${encodeURIComponent(q)}`);
export const getUserDetail = (id: number) => adminGet<AdminUserDetail>(`/api/admin/users/${id}`);
export const getActionLogs = (targetUserId?: number) => adminGet<{ logs: ActionLog[] }>(`/api/admin/actions${targetUserId ? `?targetUserId=${targetUserId}` : ""}`);
export const previewAction = (action: ActionName, targetId: number) => adminPost<ActionPreview>(`/api/admin/actions/${action}/preview`, { targetId });
export const executeAction = (action: ActionName, body: { confirmToken: string; reason: string; confirmText: string }) => adminPost<{ action: string; targetId: number; result: string }>(`/api/admin/actions/${action}/execute`, body);
```
`adminPost`는 `adminGet`과 같고 `method: "POST"`, JSON 본문, 실패 시 본문 `error`를 담은 `ApiError(status, message, code)`(ApiError에 선택 인자 `code?: string` 추가 — 서버 본문 `{"error": ...}`의 값).

`actionError.ts`:
```ts
export type ActionErrorView =
  | { kind: "reauth"; message: string }
  | { kind: "retryPreview"; message: string }
  | { kind: "fix"; message: string }
  | { kind: "other"; message: string };

/** 실행·미리보기 실패를 창 안 안내로 바꾼다. */
export function describeActionError(status: number, code: string | undefined): ActionErrorView {
  if (status === 403 && code === "REAUTH_REQUIRED") {
    return { kind: "reauth", message: "로그인한 지 10분이 지나 다시 로그인해야 해요." };
  }
  if (status === 409) {
    return { kind: "retryPreview", message: code ?? "지금은 실행할 수 없어요. 미리보기를 다시 해 주세요." };
  }
  if (status === 400) {
    return { kind: "fix", message: code ?? "입력을 확인해 주세요." };
  }
  if (status === 404) {
    return { kind: "other", message: "대상이 이미 없어요." };
  }
  if (status === 429) {
    return { kind: "other", message: "요청이 너무 많아요. 1분 뒤 다시 시도해 주세요." };
  }
  return { kind: "other", message: `실행하지 못했어요(${status}).` };
}

/** 실행 버튼 활성 조건: 사유 1~300자(앞뒤 공백 빼고), 확인 글자가 필요하면 앞뒤 공백 빼고 같을 때. */
export function canExecute(reason: string, confirmText: string | null, typed: string): boolean {
  const r = reason.trim();
  if (r.length === 0 || r.length > 300) return false;
  return confirmText === null || confirmText.trim() === typed.trim();
}
```

- [ ] **Step 1: 실패 테스트** `actionError.test.ts`:
```ts
import { test } from "node:test";
import assert from "node:assert/strict";
import { canExecute, describeActionError } from "./actionError.ts";

test("403 REAUTH_REQUIRED는 다시 로그인 안내", () => {
  assert.equal(describeActionError(403, "REAUTH_REQUIRED").kind, "reauth");
});

test("409는 서버 문구로 미리보기 다시", () => {
  const v = describeActionError(409, "확인이 만료됐어요. 미리보기부터 다시 해 주세요.");
  assert.equal(v.kind, "retryPreview");
  assert.match(v.message, /만료/);
});

test("사유가 비거나 300자를 넘으면 실행할 수 없다", () => {
  assert.equal(canExecute("  ", null, ""), false);
  assert.equal(canExecute("가".repeat(301), null, ""), false);
  assert.equal(canExecute("사용자 요청", null, ""), true);
});

test("확인 글자는 앞뒤 공백만 무시하고 같아야 한다", () => {
  assert.equal(canExecute("사유", "계정 삭제", " 계정 삭제 "), true);
  assert.equal(canExecute("사유", "계정 삭제", "계정삭제"), false);
});
```
- [ ] **Step 2:** `npm test` → 실패(모듈 없음).
- [ ] **Step 3:** 위 코드 구현, `package.json` test에 `src/lib/actionError.test.ts` 추가.
- [ ] **Step 4:** `npm test`·`npx tsc --noEmit` → 통과.
- [ ] **Step 5:** `git commit -m "feat: 관리자 조치 API 함수와 조치 오류 안내 판단"`

### Task 2: 사용자 탭(찾기·목록·상세)

**Files:**
- Create: `admin-web/src/components/tabs/UsersTab.tsx`, `admin-web/src/components/users/UserDetail.tsx`
- Modify: `admin-web/src/app/page.tsx`(탭 `users` "사용자", `log` "조치 기록" 추가 — 휴대폰 2열이라 6개가 3줄, 기간 버튼은 대시보드 탭에서만 보인다)

내용:
- `UsersTab`: 검색 입력(placeholder "번호·영상 링크·닉네임·여행 이름") + 찾기 버튼(엔터 포함), 처음 열 때 빈 검색(최근 가입 20명). 목록 줄: `#id · 가린 닉네임 · 카카오/구글 · 가입일 · 영상 n · 여행 n`, 정지면 "정지됨" 배지. 줄을 누르면 아래(휴대폰은 목록 대신)에 `UserDetail`.
- `UserDetail`: 위에 `#id 가린 닉네임 · 가입 경로 · 가입일 · 최근 활동`, 배지(정지됨/관리자), 건수 4칸, 최근 작업 표(번호·상태·실패 이유·시각·[다시 실행] — `retryable`일 때만), 여행 표(이름·기간·[삭제]), 자동 초안 표(번호·상태·[숨기기] — 숨기지 않은 것만), 계정 영역([정지]/[정지 풀기]·[계정 삭제] — `admin`이면 숨김), 이 사용자 조치 이력(`getActionLogs(id)`). 버튼은 `ActionDialog`(Task 3)를 연다. 조치 성공 뒤 상세·이력 다시 불러오기, 계정 삭제 성공이면 상세 닫고 목록 다시 검색.
- 401·403 처리는 기존 `useAdminQuery`(로그인 화면으로) 그대로.

- [ ] **Step 1:** 구현(Task 3의 `ActionDialog`가 아직 없으므로 버튼은 `onAction(action, targetId, label)` 콜백만 받게 두고 Task 3에서 연결).
- [ ] **Step 2:** `npx tsc --noEmit`·`npx eslint src`·`npm run build` 통과.
- [ ] **Step 3:** `git commit -m "feat: 관리자 웹 사용자 찾기·상세 화면"`

### Task 3: 조치 창(미리보기 → 사유·확인 → 실행)

**Files:**
- Create: `admin-web/src/components/users/ActionDialog.tsx`
- Modify: `admin-web/src/components/users/UserDetail.tsx`(연결), `admin-web/src/app/login/page.tsx`(`reauth` 문구)

내용:
- 열리면 `previewAction` → "영향 범위: {impact}", 남은 시간(5분 카운트다운), 사유 입력(300자 카운터), `confirmText`가 있으면 "아래에 「{confirmText}」를 그대로 입력해 주세요" 입력칸. 실행 버튼은 `canExecute`가 참이고 `busy`가 아닐 때만. 누르면 `busy=true` → `executeAction`.
- 성공: "완료: {result}" → 닫기 버튼 → `onDone()`.
- 실패: `describeActionError(status, code)` — `reauth`면 로그인 버튼(`clearToken()` 뒤 `/login/?error=reauth`), `retryPreview`면 "미리보기 다시"(새 토큰), `fix`면 문구만.
- `<dialog>`가 아닌 고정 위치 `div role="dialog" aria-modal="true"` + 배경, Esc·배경 누르면 닫기(실행 중엔 닫기 막음).
- 로그인 화면 `ERROR_MESSAGES.reauth = "위험한 조치는 로그인 10분 안에만 할 수 있어요. 다시 로그인해 주세요."`.

- [ ] **Step 1:** 구현.
- [ ] **Step 2:** 정적 검사·빌드 통과.
- [ ] **Step 3:** `git commit -m "feat: 관리자 조치 창(미리보기·사유·확인·실행)"`

### Task 4: 조치 기록 탭과 화면 확인

**Files:**
- Create: `admin-web/src/components/tabs/ActionLogTab.tsx`
- Modify: `admin-web/README.md`(2단계 화면 한 단락)

내용: `getActionLogs()` 최근 50개 표 — 시각·관리자 번호·조치(한글 이름 표: `DELETE_TRIP` 여행 삭제, `DELETE_ACCOUNT` 계정 삭제, `RETRY_JOB` 분석 다시 실행, `HIDE_DRAFT` 초안 숨기기, `SUSPEND_USER` 정지, `UNSUSPEND_USER` 정지 풀기, `ADMIN_USER_VIEW` 사용자 열람, `ADMIN_USER_SEARCH` 사용자 찾기, `ADMIN_WEB_LOGIN` 로그인, `ADMIN_WEB_LOGIN_DENIED` 로그인 거부)·대상·사용자·사유·결과. 휴대폰은 카드 목록.

- [ ] **Step 1:** 구현.
- [ ] **Step 2: 화면 확인(로컬 백엔드 #167 8082 + 개발 DB, 헤드리스 Chrome)** — 사용자 검색·상세, 테스트 계정 정지 → 풀기, 자동 초안 숨기기, 10분 지난 토큰으로 실행 → 재로그인 안내, 같은 미리보기 두 번 실행 → 409 안내, 데스크톱·휴대폰·다크 캡처, 콘솔 오류 0. Gemini를 쓰는 다시 실행은 미리보기까지만.
- [ ] **Step 3:** `git commit -m "feat: 관리자 웹 조치 기록 탭"` → 푸시 → PR(`Closes #7`).
