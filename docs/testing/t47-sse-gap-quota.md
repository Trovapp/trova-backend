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

## PR #546 교차 리뷰 수정 검증

기준 커밋 `2f537cb`, 최신 `origin/main`은 `e3a737e`이며 `git fetch origin` 후 `git merge origin/main` 결과 이미 최신이었다.

수정 전에 기존 진입점을 사용하는 회귀 테스트부터 추가했다. 첫 실행은 28개 중 5개 실패로 종료 이벤트 누락, 종료 중 스트림 재교체, 닫힌 슬롯 뒤 writer 누적, 대화 도구의 불필요한 검색과 한도 안내 유실을 재현했다. 새 서비스 메서드가 없어 생긴 컴파일 실패를 재현 근거로 사용하지 않았다.

- `replaced` 이벤트를 FIFO writer에서 전송한 뒤 종료한다. 테스트는 이벤트 이름과 `{"reason":"connection_limit"}` 본문, emitter 완료, 다른 사용자·나머지 연결 보존을 확인한다.
- 종료 이벤트를 기다리는 연결을 교체에서 제외한다. 예약은 원자적으로 처리하며 servlet I/O는 등록 모니터 밖에서 수행한다.
- 슬롯은 즉시 반환하면서 writer 예약은 emitter 완료의 finally까지 유지한다. 전체 writer 수는 슬롯 한도의 두 배(기본 512)로 제한한다. 인터럽트를 무시하는 송신 두 개를 막아 둔 작은 풀에서 추가 연결 429와 활성 슬롯 0·writer 2를 확인하고, 송신을 풀면 writer 0과 새 연결 수락을 확인한다.
- 종료된 실행기의 `RejectedExecutionException`을 그대로 전달하며 슬롯·writer 예약을 반환한다. 작은 풀에서 세 번 반복해 예약 누수도 확인한다.
- 대화 도구는 실제 `GapRecommendationService`와 실제 `DailyQuotaService`를 연결해 장소 사이·하루 끝의 선택된 빈 시간만 한 번 검색/차감하는지, 이미 소진된 한도 메시지를 그대로 안내하는지 네 조합으로 확인한다.
- HTTP로 빈 시간 두 개·남은 한도 하나일 때 첫 검색만 호출하고 429인지 확인한다. 남의 여행 403, 없는 여행/일차 404 및 미인증 401 뒤에도 실제 한도가 남아 정상 요청이 성공하는지 확인한다.
- ADR 0036의 상태는 확정으로 유지하고 차감 위치 문장 옆에 ADR 0057 대체 표시를 추가했다. ADR 0057과 API 문서에 이벤트·앱 자동 재접속 중지 계약·writer 상한을 기록했다. 앱 구현은 별도 작업이다.

전체 빌드 재실행 중 SSE HTTP 테스트에서 `MockHttpServletResponse`의 비동기 헤더 쓰기와 MockMvc 기본 응답 출력의 `HeaderValueHolder.getStringValues()` 순회가 겹쳐 NPE가 발생했다. 이 클래스의 자동 응답 출력을 `MockMvcPrint.NONE`으로 끄고, 완료된 첫 응답의 실제 본문에 `event:replaced`와 reason이 있는지 직접 단언하도록 보강했다. HTTP 상태·인증·본문 검증은 유지한다.

최종 전체 빌드 `./gradlew --no-daemon build`는 2분 25초에 성공했다. 233개 클래스, 1,543개 중 통과 1,537·조건부 건너뜀 6·실패/오류 0이다. 새 실행 사례는 8개(대화 4·SSE 3·HTTP 1)이며 기존 교체 이벤트·권한 테스트의 단언도 강화했다. 최초 sandbox 빌드는 기존 프로세스 조회 제한으로 실패했으므로, 최종 빌드는 해당 조회를 허용한 환경에서 테스트 제외 없이 실행했다.

전체 빌드 이후 수정 1·2·3과 종료 중 연결 제외를 각각 되돌려 실제 테스트 실패로 **5/5 검출**했다. 각 변형은 실행 직후 finally에서 원복했다.

| 리뷰 항목·변형 | 검출 결과 |
|---|---|
| 1: 교체 시 `replaced()` 대신 종전 `close()` | 교체 이벤트 send 검증 실패 1개 |
| 2a: 대화 단건 경로를 종전 그날 전체 조회·후필터로 복구 | 남은 한도 1·장소 사이/하루 끝 두 경우 실패 |
| 2b: `QuotaExceededException` 전용 catch 제거 | 소진된 한도·장소 사이/하루 끝의 안내 문구 두 경우 실패 |
| 3: writer 수에 따른 admission 상한 제거 | 막힌 writer 누적 후 429 검증 실패 1개 |
| 4: 종료 중 연결을 사용자 연결 수에 포함 | 불필요한 재교체 counter 검증 실패 1개 |

컴파일 실패를 검출로 세지 않았다. 실패는 각 실행의 JUnit assertion 또는 Mockito 송신 검증으로 확인했다.

변형 원복 뒤 `./gradlew test --tests '*TripStreamSlotsTest' --tests '*ConversationToolExecutorTest' --tests '*GapRecommendationServiceTest' --tests '*GapRecommendationQueryCountTest' --tests '*TripEventsHttpTest'`를 실행해 113개 모두 통과했다(9초). `git diff --check`도 통과했다.
