# #446 공유 링크에서 여행 복사 설계

## API

`POST /api/public/trips/{token}/copy`는 로그인한 요청자의 새 여행을 만든다. 요청 본문은 없다. 성공 응답은 201과 `{ "tripId": <새 id>, "title": "<원본 제목>" }`이며 응답은 record다.

SecurityConfig의 공개 허용은 GET `/api/public/trips/*`뿐이다. POST 복사 경로는 기존 authenticated 규칙을 따른다. TripShareLinkController에서 CurrentUserService로 요청자를 확인하고, TripShareLinkService.findPublic(token)으로 켜진 링크의 여행을 찾는다. 자기 여행도 복사할 수 있다.

## 복사 범위

TripService.copyTrip(User target, Trip source)는 하나의 트랜잭션에서 새 Trip·Itinerary·TripPlace를 저장한다. 기존 자동 초안 승인처럼 엔티티를 직접 저장하므로 날짜 없는 여행도 지원한다. 원본 엔티티는 변경하지 않는다.

| 대상 | 복사할 값 |
| --- | --- |
| 여행 | title(그대로), startDate, endDate(없으면 null), 소유자는 요청자 |
| 일차 | day, date, 장소 없는 일차도 보존. startDate가 null이면 원본 일차 수만큼 date=null |
| 장소 | placeName, region, category, latitude, longitude, phone, address, visitOrder, visitStartTime, visitEndTime, arrivalTransportMode |
| 제외 | memo, savedPlaceId, googlePlaceId, space는 null, source는 MANUAL |

사본은 독립된 여행이다. 원본의 수정·삭제나 링크 해제를 따라가지 않고, 원본 소유자의 공유 링크도 복사하지 않는다.

## 한도

- 사용자당 하루: `app.share-link.copy-per-day: ${APP_SHARE_LINK_COPY_PER_DAY:20}`. 기본 20회는 감으로 정함이며 실측 근거는 없다.
- DailyQuotaService.consumeTripCopy는 consumePlaceCall과 별도 카운터를 쓰며 한국 자정 기준으로 날짜가 바뀌면 초기화한다. 서버 재시작 시 초기화되는 단일 JVM 메모리 방식이다.
- 유효한 원본을 찾은 뒤 복사 직전에 사용자 한도를 차감한다. 잘못된 링크는 사용자 한도를 쓰지 않는다. 저장 실패 시 메모리 카운터 차감은 복원되지 않는다(기존 장소 호출 한도 패턴).
- IP당 분당: 공개 조회와 같은 RequestRateLimiter, 키 `public-trip:` + remoteAddr, 설정 `app.share-link.public-per-minute`(기본 60)을 공유한다. 모르는 토큰도 IP 한도에 포함한다.

## 오류

| 상황 | 상태 | 응답 |
| --- | --- | --- |
| 미로그인 | 401 | 기존 SecurityConfig 인증 진입점 |
| 모르는 토큰·해제된 링크·정지된 원본 소유자 | 404 | 빈 본문(findPublic 재사용) |
| 사용자 하루 한도 초과 | 429 | 기존 QuotaExceededHandler의 message 안내 |
| IP 분당 한도 초과 | 429 | 빈 본문, Retry-After: 60 |

## 확인 범위

기존 TripShareLinkControllerTest의 고유 H2 설정을 유지하고 하루 한도만 테스트 프로퍼티로 2회로 정한다. 인증·404·201 본문·요청자 소유·공개 필드 보존·비공개 필드 제외·원본 보존·날짜 없는 여행·자기 여행 복사·429·IP 한도 공유를 테스트 코드로 다룬다. TripServiceCopyTest는 직접 서비스 복사 동작을, DailyQuotaTripCopyTest는 별도 카운터와 한국 자정 초기화를 다룬다.

이번 작업의 실행 검증은 `./gradlew compileJava compileTestJava -q`까지만 한다. 테스트 실행과 전체 build는 작업 담당자가 별도로 수행한다.
