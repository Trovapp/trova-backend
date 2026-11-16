# #444 여행 읽기 전용 공유 링크 설계

## 데이터와 서비스

`TripShareLink`는 `trip_share_links`에 저장한다. id는 IDENTITY, trip은 LAZY ManyToOne이며 trip_id는 NOT NULL이다. token은 길이 64, UNIQUE, NOT NULL이다. createdAt과 revokedAt은 기존 엔티티와 같은 LocalDateTime이며 revokedAt이 null이면 켜진 링크다.

`TripShareLinkRepository`는 `findByTripAndRevokedAtIsNull`, `findByTokenAndRevokedAtIsNull`, `deleteByTrip`을 제공한다.

`TripShareLinkService`는 다음을 제공한다.

- `getActive(Trip)`: 켜진 링크를 조회한다.
- `createOrGet(Trip)`: 기존 링크가 있으면 재사용하고, 없으면 SecureRandom 32바이트를 Base64url 패딩 없이 인코딩한 43자 토큰으로 저장한다.
- `revoke(Trip)`: revokedAt을 기록한다. 켜진 링크가 없으면 false다.
- `findPublic(String token)`: 켜진 링크의 Trip을 반환한다.
- URL: 끝 슬래시를 제거한 `app.web-app-url` + `/t/` + token. 설정이 비면 `/t/` + token.

## API와 권한

| 메서드 | 경로 | 응답 |
| --- | --- | --- |
| GET | `/api/trips/{id}/share-link` | 200 ShareLinkResponse 또는 404 |
| POST | `/api/trips/{id}/share-link` | 기존 링크면 200, 새 링크면 201 |
| DELETE | `/api/trips/{id}/share-link` | 해제하면 204, 없으면 404 |
| GET | `/api/public/trips/{token}` | 200 PublicTripResponse, 없거나 해제됐으면 404, 한도 초과면 429 |

관리 API는 `CurrentUserService.resolve(authentication)` 다음 여행을 조회하고 사용자 id를 비교한다. 다른 사용자의 여행은 404다. 공개 API는 인증 객체와 CurrentUserService를 사용하지 않는다. SecurityConfig의 기존 permitAll 체인에 GET `/api/public/trips/*`만 추가한다.

공개 API는 `rateLimiter.tryAcquire("public-trip:" + http.getRemoteAddr(), publicPerMinute)`로 IP별 요청을 센다. 초과 시 `Retry-After: 60`을 포함한다. 설정은 `app.share-link.public-per-minute: ${APP_SHARE_LINK_PUBLIC_PER_MINUTE:60}`이다.

## 응답

모든 응답은 record다.

- `ShareLinkResponse(token, url, createdAt)`
- `PublicTripResponse(title, startDate, endDate, List<PublicDay> days)`
- `PublicDay(day, date, List<PublicPlace> places)`
- `PublicPlace(placeName, region, category, latitude, longitude, address, phone, visitOrder, visitStartTime, visitEndTime, arrivalTransportMode)`

id·memo·source·googlePlaceId·사용자 정보는 포함하지 않는다. 공개 응답은 컨트롤러 private 메서드에서 만든다. 일차는 `findByTripOrderByDay`, 장소는 `findByItineraryOrderByVisitOrder`로 읽는다.

## 삭제와 확인 범위

TripService에 TripShareLinkRepository를 생성자로 주입하고 여행 삭제 전에 `deleteByTrip(trip)`을 호출한다. 해제된 링크도 삭제한다.

H2 기반 `@SpringBootTest`, `@AutoConfigureMockMvc`, `@Transactional` 테스트를 작성한다. 한도는 3회, 웹앱 URL은 `https://app.example.test`로 고정한다. 생성 형식·URL, 반복 생성, 소유권, 미인증 생성 차단, 미인증 공개 조회, 비공개 필드 제외, 해제·재생성, 모르는 토큰, 여행 삭제, IP별 429와 Retry-After를 다룬다.

빌드와 시연은 사람이 한다. 의존성·기존 API·Flyway/SQL·프론트엔드는 변경하지 않으며 토큰을 로그에 남기지 않는다.
