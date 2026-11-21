# 여행 공동 편집 API

로그인한 사용자는 기존 인증 헤더를 사용한다. 멤버 상한 10명은 OWNER를 포함한다.

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
