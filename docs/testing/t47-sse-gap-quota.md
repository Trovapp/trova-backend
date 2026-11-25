# T47 SSE 슬롯·빈 시간 추천 한도 회귀 검증

2026-11-25 · 이슈 #545 · 기준 origin/main e3a737e

## 원인과 수정

- `TripStreams.register`: 사용자당 3개에서 새 연결을 429로 거절했다. 등록 순서를 보존해 같은 사용자의 가장 오래된 연결만 교체한다. 전체 256개에서 새 사용자는 여전히 429이며 이미 3개를 쓰는 사용자의 교체는 허용한다.
- `TripStream.run/close`: 슬롯 반환이 송신기 finally의 emitter 완료 뒤에 있었다. close의 원자적 종료 전환 직후 반환하며 IOException·완료·오류·타임아웃도 같은 경로를 쓴다. servlet 쓰기나 완료가 막혀도 슬롯은 반환된다. 아직 서버가 감지하지 못한 TCP 단절은 다음 쓰기/heartbeat까지 남을 수 있으며 새 연결의 오래된 슬롯 교체로 재진입 거절을 방지한다.
- `TripController.gapRecommendations`: 외부 호출이 없어도 매 요청을 차감했다. `GapRecommendationService.recommend`에서 Google Places 검색 직전에만 차감하고, 한도 초과 예외는 검색 실패 폴백 밖으로 전달한다. 결과 캐시는 추가하지 않았으므로 버전 없는 캐시 키도 없다(ADR 0057).
- `trova.trip-events.active` gauge와 `trova.trip-events.replaced` counter에는 사용자·여행 라벨을 넣지 않는다. 권한 JDBC 조회와 종료 transport 처리는 등록 모니터 밖에 있다.

## 회귀 테스트

`./gradlew --no-daemon test --tests '*TripStreamSlotsTest' --tests '*TripDeliveryTest' --tests '*GapRecommendationServiceTest' --tests '*DailyQuotaControllerTest' --tests '*TripEventsHttpTest'`

총 102개 통과. 네 번째 연결의 가장 오래된 emitter 완료와 새 HTTP 200, 전체 256/257 경계, 가득 찬 상태의 교체, 완료/오류/타임아웃의 중복 반환 방지, 막힌 송신/완료에서도 즉시 슬롯 반환, 외부 검색 없는 요청의 무차감, 검색별 선차감 순서, 검색 실패 시 차감, 소진 후 429 메시지와 외부 호출 차단을 검증했다. 한도 소진 뒤 외부 검색 없는 요청은 200이다.

Gradle의 기존 공유 daemon은 이 워크트리 쓰기 권한이 없어 캐시 생성에 실패했다. 새 단일 실행 daemon을 쓰는 `--no-daemon`으로 정상 실행했다. 외부 API는 mock이며 운영 DB·설정은 변경하지 않았다.

## 변형 검사

각 변형을 하나씩 적용하고 해당 테스트를 실제 실행한 뒤 원본을 복원했다. 컴파일 실패가 아닌 테스트 assertion 실패로 **6개 중 6개 검출**했다.

| 변형 | 실패한 회귀 테스트 |
|---|---|
| 가장 오래된 연결 대신 최신 연결 종료 | `네번째는_같은_사용자의_가장_오래된_다른_여행_연결을_교체한다` |
| 전체 상한 검사 제거 | `전체_256개에서는_새_사용자를_429로_막고_기존_사용자_교체는_허용한다` |
| close의 즉시 반환 제거 | IOException·callback·막힌 송신 반환 테스트 3개 |
| 외부 검색 차감 제거 | 검색별 선차감·실패 차감·소진 차단 테스트 3개 |
| 한도 예외를 검색 실패 catch 안으로 이동 | `한도_소진은_검색_실패로_삼키지_않고_외부_호출_전에_중단한다` |
| 컨트롤러 요청당 차감 복구 | `빈_시간이_없는_조회는_한도를_쓰지_않고_검색_두번_뒤에는_429다` |

## 전체 빌드

`./gradlew --no-daemon build` 성공(2분 22초). 233개 테스트 클래스, 총 1,535개 중 1,529개 통과·6개 조건부 건너뜀·실패 0·오류 0. 건너뛴 테스트는 KafkaShareJobQueueTest, RouteOptimizerBenchTest, PlaceListPerformanceTest, ShareJobProcessRecoveryTest, ShareJobKafkaRoundTripTest, PlaceListPostgresHttpTest의 각 1개로 기존 실행 조건에 따른다. 테스트 제외 옵션은 사용하지 않았다.

최초 sandbox 전체 실행에서는 프로세스 수명 테스트가 macOS 프로세스 조회 `sysctl` 차단(`Operation not permitted`)으로 실패했다. 해당 실행을 중단하고 정상 권한의 단일 실행 daemon으로 전체 빌드를 다시 실행해 위 결과를 얻었다.
