# 여행 공동 편집 API

배포 전에 만든 보기용 링크도 활성 상태이면 같은 URL로 참여할 수 있다. 로그인한 사용자는 기존 인증 헤더를 사용한다. 멤버 상한 10명은 OWNER를 포함한다.

| 요청 | 성공 응답 |
| --- | --- |
| POST /api/public/trips/{token}/join | 200 `{"tripId":12}`. 요청 본문 없음. 이미 참여했으면 같은 응답 |
| GET /api/trips/{id}/members | 200 `[{"userId":7,"nickname":"민수","profileImageUrl":null,"role":"OWNER"}]` |
| DELETE /api/trips/{id}/members/{userId} | 204. OWNER가 EDITOR를 내보내거나 EDITOR가 본인 계정으로 나간다 |
| POST /api/trips/{id}/share-link/rotate | 200. 기존 share-link 응답 형태로 새 토큰 반환. 기존 토큰은 즉시 무효 |
| POST /api/public/trips/{token}/copy | 기존 201 응답 유지. 참여와 독립적으로 복사본 생성 |

여행 목록은 기존 카드 필드에 role, memberCount, members(최대 4명)를 추가한다. 여행 상세 및 일정 장소의 version은 숫자다. 변경 요청에는 마지막으로 읽은 대상의 버전을 `If-Match: "3"` 헤더로 보낸다. 이전 앱은 헤더를 생략할 수 있다.

| 상태 | 의미 |
| --- | --- |
| 401 | 인증 필요 |
| 403 | 비멤버, OWNER 전용 작업 또는 OWNER 나가기 |
| 404 | 없는 대상 또는 무효 링크 |
| 409 | 10명 상한 `{"code":"MEMBER_LIMIT"}` 또는 버전 충돌 `{"code":"VERSION_CONFLICT","latest":{...}}` |
| 429 | 공개 링크 요청 한도 초과. Retry-After 초 뒤 재시도 |

여행 삭제 및 링크 해제·재발급은 OWNER 전용이다. 편집과 AI 요청은 EDITOR도 가능하고 한도는 요청한 사용자에게 적용한다.

## 편집 응답의 여행 버전

성공한 공동 편집 요청은 커밋한 여행의 버전을 십진수 응답 헤더 `X-Trip-Version`으로 돌려준다. 앱은 이 값을 해당 여행의 캐시에 저장한다. 예를 들어 장소 메모 수정 응답의 헤더가 `X-Trip-Version: 8`이고 장소 JSON이 `"version": 3`이면 다음 여행 제목 변경에는 `If-Match: "8"`, 같은 장소 메모 변경에는 `If-Match: "3"`을 보낸다. 연속 요청은 앞 요청의 응답을 받은 뒤 보낸다.

| 성공한 작업 | 응답에서 읽을 값 |
| --- | --- |
| 제목·날짜 변경 | 헤더와 여행 상세 본문의 version이 같은 여행 버전 |
| 장소 추가·영상 장소 추가·메모/시간·순서 변경·교체·중간 삽입 | 여행 버전은 헤더, 장소 버전은 응답 장소의 version. 대화와 대안 추천을 적용할 때도 같은 편집 API 사용 |
| 일차 최적화·복원 | 여행 버전은 헤더, 장소별 버전은 배열의 각 version |
| 장소 삭제·멤버 나가기/내보내기·링크 끄기 | 204 응답의 헤더 |
| 참여·링크 생성/재조회(POST)·재발급 | 응답 헤더. 이미 참여했거나 기존 링크를 돌려주면 현재 버전 유지 |

여행 자체를 삭제하면 버전을 보관할 대상도 삭제된다. 재구성 접수와 대화 응답은 제안을 생성한다. 같은 여행에서 같은 옵션으로 재구성을 진행 중이면 다른 멤버도 같은 jobId를 받고, 새 작업을 시작한 사용자에게만 한도를 차감한다.

대안 요청의 추천 설명은 요청자의 선호를 반영할 수 있다. 같은 재구성 jobId를 조회할 때는 실행자만 후보의 `recommendationReason`을 받는다. 다른 멤버에게도 후보 장소·일정 정보는 반환되며 앱은 설명이 비어 있으면 생략한다. 이 규칙은 이미 저장된 결과에도 적용된다.

장소 상세 GET이 영상 장소의 카탈로그 ID를 처음 연결해도 여행·장소 편집 버전은 유지된다. 영상에서 만든 여행을 조회하거나 다시 확정하면, 같은 영상 장소가 공동 여행에 있어도 요청자 소유의 기존 여행을 찾아 반환한다.

## 참여·재발급 오류 예시

| 요청과 조건 | 상태와 응답 예시 |
| --- | --- |
| join, 로그인하지 않음 | 401. 응답 본문이 비어 있을 수 있음 |
| join, 무효화된 토큰 | 404. 기본 JSON 오류 응답의 예시 필드 `{"status":404,"error":"Not Found","path":"/api/public/trips/old-token/join"}` |
| join, 열한 번째 멤버 | 409 `{"code":"MEMBER_LIMIT"}`. 이미 참여한 사용자는 200 |
| join, 공개 요청 한도 초과 | 429, `Retry-After: 60`, 빈 본문 |
| rotate, EDITOR 또는 비멤버 | 403. 기본 JSON 오류 응답의 예시 필드 `{"status":403,"error":"Forbidden","path":"/api/trips/12/share-link/rotate"}` |
| rotate, 없는 여행 | 404. 기본 JSON 오류 응답의 예시 필드 `{"status":404,"error":"Not Found","path":"/api/trips/12/share-link/rotate"}` |

인증·권한·리소스 오류는 HTTP 상태로 처리한다. 편집 충돌은 `VERSION_CONFLICT`, 인원 상한은 `MEMBER_LIMIT` 코드로 구분한다.

## 앱 요청 예시

친구가 장소 메모를 편집할 때는 여행 상세의 해당 장소에서 읽은 version을 보낸다.

```http
PATCH /api/trip-places/52/details
Authorization: Bearer <access-token>
Content-Type: application/json
If-Match: "4"

{"memo":"오후에 만나기"}
```

200이면 응답 장소의 새 version을 사용한다. 409의 `latest`는 `GET /api/trips/{id}`와 같은 여행 상세 객체다. 최신 일정을 적용한 후 사용자가 다시 시도할 때 갱신된 버전을 보낸다. 여행 이름·날짜·삭제, 일차 일정 추가·최적화·복원에는 여행 버전을 사용한다. 장소 교체·삭제·순서·시간·메모에는 해당 장소 버전, 중간 삽입에는 afterTripPlaceId의 장소 버전을 사용한다. 잘못된 형식의 버전은 400이다.

```json
[{"id":12,"title":"강릉","startDate":"2026-11-24","endDate":"2026-11-25","placeCount":1,
  "regions":["강릉"],"role":"EDITOR","memberCount":2,
  "members":[{"userId":7,"nickname":"민수","profileImageUrl":null,"role":"OWNER"},
             {"userId":8,"nickname":"지수","profileImageUrl":null,"role":"EDITOR"}]}]
```

편집자는 GET share-link 및 기존 링크를 반환하는 POST share-link를 쓸 수 있다. 꺼진 링크를 다시 켜거나 기존 링크를 rotate 하는 작업에는 OWNER가 필요하다. 링크를 끄거나 교체해도 기존 멤버는 유지된다. 나간 멤버가 유효한 링크를 다시 열면 재참여할 수 있다.

## 실시간 변경 알림

여행 상세 화면에서 `GET /api/trips/{id}/events`를 연다. 네이티브 앱은 Authorization Bearer 헤더를 사용한다. PWA는 같은 인증으로 `POST /api/trips/{id}/events/ticket`을 호출해 200 응답 `{"ticket":"…"}`을 받고, EventSource로 `/api/trips/{id}/events?ticket=…`를 연다. 표는 발급받은 여행에서 60초 동안 한 번 쓸 수 있다. 연결 시점에도 멤버여야 한다. 인증 실패·표 만료·재사용은 401, 멤버 제외는 403, 미사용 표(사용자당 8개) 또는 연결 상한 초과는 429다. 세션으로 인증했다면 로그아웃하거나 세션이 무효가 된 뒤에는 발급받은 표를 쓸 수 없고 열린 연결도 다음 전송 전에 종료된다. 느린 연결의 송신 대기열이 차면 서버가 연결을 닫는다.

```text
event:ready
data:{"version":8}

event:changed
data:{"tripId":12,"version":9,"actorId":7,"actorName":"민수","type":"PLACE_ADDED","summary":"장소를 추가했어요"}

:heartbeat

```

25초 간격의 주석은 연결 유지용이다. 앱은 changed 수신 시 상세를 다시 조회한다. 재연결·포그라운드 복귀 때도 상세를 조회하고, 웹 재연결에는 새 표를 발급받는다. 버전은 여행 단위다. CORS 응답에서 `X-Trip-Version`과 `Retry-After`를 읽을 수 있다.
