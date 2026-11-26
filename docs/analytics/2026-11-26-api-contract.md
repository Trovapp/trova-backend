# 앱 행동 지표 계약 구현 — #547

기준은 [앱 계약 9f5babd](https://github.com/Trovapp/trova-app/blob/9f5babd/docs/analytics/2026-11-26-api-contract.md)이다.
계획은 앱 작업 브랜치에 없어 [계획 PR #283](https://github.com/Trovapp/trova-app/pull/283)의 문서를 확인했다.

## 동의

`GET /api/me/analytics-consent` 기본 응답(200):

```json
{"consentVersion":"analytics-2026-11-26-v1","revision":0,"usageConsent":false,"transferConsent":false,"analyticsId":null,"noticeReady":false}
```

PUT 요청은 `consentVersion`, `expectedRevision`, `usageConsent`, `transferConsent`를 모두 요구한다.
버전/누락/음수 또는 JS 안전 정수 초과 revision은 400, CAS 충돌과 고지 미완료 상태의 동의는 409다.
고지가 활성화된 테스트에서 양쪽 true 저장은 revision 1과 UUID v4를 반환하며 동일 요청은 같은 응답을 반환한다.
부분 동의에서도 UUID는 null이다. DELETE는 조건 없이 양쪽 false, UUID 폐기, 삭제 큐 등록 후 204를 반환한다.
미인증은 401이고 정상 응답에는 `Cache-Control: no-store`가 있다. 기존 필수 동의 API는 변경하지 않는다.

### signup 생략 사유

현재 JWT/세션은 이번 가입 흐름을 증명하는 표식을 전달하지 않는다. 가입 시각 추정은 사용하지 않는다.
계약의 판정 불가 시 생략 규정과 코디네이터의 인증 변경 제한(JWT 구조·만료·세션 정책 유지, 넓은 인증 수정이면 생략)에 따라 `signup`을 생략한다.
따라서 신규 가입 이벤트 및 그 퍼널의 시작 단계는 이번 백엔드로 활성화할 수 없다.
별도 가입 흐름 표식 계약/구현이 필요하며 기존 로그인·약관 재동의·과거 가입자의 늦은 동의를 신규 가입으로 보내지 않는다.

## 공유 판정

기존 경로 `POST /api/trips/{id}/share-link`에 추가한다(계약이 허용한 기존 경로 사용).
신규 생성은 기존 201, 재사용은 기존 200을 유지한다.

```json
{"token":"기존 공유 토큰","url":"기존 공유 URL","createdAt":"2026-11-26T12:00:00","newlyCreated":true}
```

`POST /api/public/trips/{token}/join`:

```json
{"tripId":1,"newlyJoined":true}
```

응답에 `analyticsEventId` 필드는 없다. 재사용/기존 멤버면 신규 필드는 false이며, `GET /api/trips/{id}/share-link`와 `POST /api/trips/{id}/share-link/rotate`도 `newlyCreated=false`다.
중복 집계 방지가 필요하면 앱이 요청마다 무작위 ID를 만들고 같은 요청의 이벤트 전송 재시도에 재사용한다. 사용자·여행 ID나 공유 토큰은 이벤트 ID로 사용하지 않는다. 서버는 이벤트 ID를 받거나 저장하지 않는다.
공개 조회에는 분석 ID와 이벤트 ID를 추가하지 않는다. 공유 권한 위반은 기존 403을 유지한다.

## 영상 결과

`GET /api/places/videos/{jobId}/analysis-result`, 인증·소유권 필수:

```json
{"status":"SUCCEEDED","placeCount":0,"failureReason":null}
```

상태는 PENDING/PROCESSING/SUCCEEDED/FAILED다. 분석 완료 상태를 우선하므로 이후 일정 생성 실패를 분석 실패로 세지 않는다.
완료 상태와 장소 수는 같은 읽기 스냅샷에서 조회한다. 실패 코드는 계약의 7개 값만 반환하며 원문 오류는 반환하지 않는다.
다른 사용자·삭제/존재하지 않는 작업은 404로 숨긴다(초기 지시의 403 대신 계약 우선이라는 코디네이터 확인).

## 저장·운영 경계

`analytics_consents`: user_id PK, revision, usage_consent, transfer_consent, analytics_id, updated_at, last_put_revision.
`analytics_deletion_jobs`: id, analytics_id UNIQUE, status=PENDING, created_at. 사용자 FK는 없다.
`trip_members`, `trip_share_links`: 분석 이벤트 ID 필드·컬럼을 추가하지 않는다. 운영 배포 전이므로 추가 DDL을 제거했고 엔티티에도 매핑하지 않는다.
DDL 참고는 [analytics_consent.sql](../../db/supabase/analytics_consent.sql), 정책은 [ADR 0059](../adr/0059-analytics-consent-and-deletion.md)다.

이번에는 삭제 큐 기록만 구현한다. `POSTHOG_PERSONAL_API_KEY` 이름만 정의하며 HTTP 삭제 작업자는 없다.
키가 없으면 호출은 0회이며 PENDING이 남는다. 실제 삭제 API 검증·운영 재시도/알림·백업 만료는 후속 작업이다.
화면 녹화, 이벤트 수집, 외부 가입/키 사용, 운영 DB 변경, 배포는 수행하지 않는다.

## 검증

- 구현 전 `AnalyticsContractTest`: 9개 중 8개 실패(새 경로/필드 부재), 기존 미인증 차단 1개 통과.
- 구현 후 동의/공유/영상 결과 및 기본 고지 테스트 10개 통과.
- `AnalyticsConsentConcurrencyTest`: 실제 별도 트랜잭션의 CAS 충돌, 철회 롤백과 삭제 큐 원자성, 키 없는 PENDING 보존을 검증한다.
- Windows 전체 `gradlew.bat build` 2회: 각각 1,570개 중 통과 1,546, 실패 1, 조건부 건너뜀 23. 신규 12개는 모두 통과했다.
- 유일한 실패는 기존 `AuxiliaryRunnerInterruptTest:110`의 리뷰요약 임시 폴더 미삭제 단언이다. pipeline 소스·테스트는 origin/main과 동일하며 단독 재실행은 통과했다. 테스트를 제외하지 않았고, 코디네이터가 이 제한을 기록한 일반 PR 생성(머지 금지)을 승인했다. 추가 단독 3회 `--rerun-tasks` 실행도 모두 통과했다(15초·11초·13초, 매회 실행 1개 통과·OS 조건 4개 건너뜀). [PR #548](https://github.com/Trovapp/trova-backend/pull/548)과 작업 기록에 남겼다.
- 외부 PostHog 삭제 호출은 검증하지 않았으며 실행하지 않는다.

### PR #548 리뷰 수정 검증

- 수정 전 계약 테스트에서 이벤트 ID 노출, 이벤트 ID 컬럼 생성, rotate 신규 집계 3개 실패를 확인했다. 부분 동의 삭제 큐 단언과 PUT/탈퇴 경합 양쪽 순서는 기존 잠금 구현에서 이미 통과했다.
- 수정 후 분석 관련 16개 테스트가 모두 통과했다. 새 테스트는 `공유_링크_회전은_신규_생성으로_집계하지_않는다`, `이벤트_ID_컬럼을_생성하지_않는다`, `동의_PUT과_탈퇴가_경합해도_발급_UUID는_삭제큐에_남는다`(PUT 우선/탈퇴 우선 2회)다.
- 기존 공유·참여 테스트는 이벤트 ID 필드 부재를 확인하도록 바꾸고, 부분 동의 테스트는 이전 UUID의 PENDING 삭제 작업 1개를 단언한다. GET 동의 조회는 ADR 0036의 `resolveUserId`를 사용한다.
- 동시성 테스트는 H2의 별도 트랜잭션으로 첫 작업의 커밋을 지연하고 두 번째 작업의 대기를 확인한다. PUT 우선이면 반환 UUID가 삭제 큐에 남고, 탈퇴 우선이면 PUT 인증이 거절되며 두 경우 모두 사용자와 동의 행은 남지 않는다.
- 전체 `./gradlew.bat build`는 5분 13초에 종료했고 1,574개 중 통과 1,550·실패 1·건너뜀 23이었다. 유일한 실패는 사전 공유된 Windows 기존 `AuxiliaryRunnerInterruptTest:110`이며 그 외 실패는 없고, 테스트를 제외하지 않았다.
