# 대화 장소 카드 우선 스트리밍

`POST /api/conversations/{sessionId}/messages/stream`은 장소 도구가 끝나는 즉시 카드를 전송하고, 기존 Gemini 2차 답장을 이어서 전송한다. 기존 `POST /api/conversations/{sessionId}/messages`의 JSON 응답은 그대로다. 두 경로는 동일한 세션·서비스·Gemini 프롬프트·문장 정리·10턴 상한을 공유하며 서로 번갈아 호출할 수 있다.

## 요청과 인증

- 헤더: `Content-Type: application/json`, `Accept: text/event-stream`, 기존 `Authorization: Bearer …` 또는 로그인 세션 쿠키.
- 본문: 기존 메시지 API와 동일. `message`는 공백이 아닌 300자 이하 문자열.
- 첫 메시지에는 `tripId`와 정확히 하나의 컨텍스트가 필요하다: `tripPlaceId` 또는 `day` + `gapBeforePlaceId`.
- 후속 메시지는 같은 `sessionId`에 `{"message":"조용한 곳으로 찾아줘"}`만 보낸다.
- 인증·여행 멤버십·세션 소유권·IP 제한은 기존 POST와 동일하다. 인증 헤더를 보낼 수 있는 POST 스트림이므로 Trip Events의 GET 티켓을 사용하지 않는다. URL에 토큰을 넣지 않는다.
- 새 경로에는 처리 중인 턴 기준 사용자당 3개·전체 256개의 상한이 추가된다. 연결이 끊겨도 서버 작업이 끝날 때까지 상한에 포함된다. 상한은 단일 JVM 범위이며, 상한 초과는 시작 전 HTTP 429 JSON과 X-Trace-Id로 반환한다.
- 기존 대화 경로에는 별도의 `DailyQuotaService.consumePlaceCall` 호출이 없다. 새 경로도 이를 추가하거나 다른 추천 API의 하루 한도를 우회해 호출하지 않고 동일한 도구를 사용한다. Gemini 호출 수·실패 처리·재시도 정책은 변경하지 않는다.

```http
POST /api/conversations/unique-session-id/messages/stream
Authorization: Bearer <token>
Content-Type: application/json
Accept: text/event-stream

{"message":"근처 조용한 카페 찾아줘","tripId":10,"tripPlaceId":100}
```

응답은 `200`, `Content-Type: text/event-stream`, `Cache-Control: no-store`, `X-Accel-Buffering: no`, `X-Trace-Id: <traceId>`다. SSE 표준대로 UTF-8 바이트를 누적해 빈 줄로 이벤트를 구분하고, 각 이벤트의 `data:`를 JSON으로 파싱한다. 네트워크 청크 하나와 이벤트 하나가 일치한다고 가정하지 않는다.

## 이벤트 계약

성공 순서: `cards` → `reply` → `done`. 카드 이벤트는 최대 1회다. 도구를 호출하지 않거나 `note_preference`처럼 후보가 없는 도구를 쓴 경우에는 `reply` → `done`만 온다. 후보 조회 결과가 빈 배열이면 `cards`의 `candidates: []`를 보낸다.

```text
event:cards
data:{"candidates":[{"placeId":5,"googlePlaceId":"g5","name":"조용한 카페","category":"cafe","rating":4.5,"userRatingCount":100,"latitude":37.5,"longitude":127.0,"address":"서울","distanceToNextKm":0.2,"estimatedTravelMinutes":3,"isCongestionAvailable":false,"congestionLevel":null,"recommendationReason":"가까워요"}]}

event:reply
data:{"reply":"근처에서 조용히 쉬기 좋은 곳을 찾았어요.","turnCount":1,"turnLimitReached":false}

event:done
data:{"turnCount":1,"turnLimitReached":false}

```

`cards.candidates`는 기존 JSON 응답의 `candidates`와 동일한 `CandidateResponse` 배열이다. 필드명·순서·nullable 값과 후보 순위를 유지한다. `placeId`는 서버 장소 ID이고 `googlePlaceId`는 Google ID다. 문장은 실제 Gemini 2차 응답을 기존 정리기로 처리한 결과다. 카드 도착 시 표시하고 문장 로딩을 유지하다가 `reply`에서 문장을 채운다. 두 이벤트를 모두 기다린 후 카드까지 표시하면 이 API의 목적이 사라진다.

`reply`의 `turnCount`, `turnLimitReached`는 기존 응답과 같다. `done`은 해당 턴의 서버 처리가 끝났다는 뜻이다. 10턴이 찬 뒤 요청하면 Gemini를 호출하지 않고 `reply: null`, `turnCount: 10`, `turnLimitReached: true`인 `reply`·`done`을 보낸다. 세션은 기존처럼 서버 메모리에만 있으며 마지막 활동 후 30분 TTL을 따른다.

두 경로는 세션별 ReentrantLock으로 한 턴씩 처리한다. 최대 30초 대기 후 잠금을 얻지 못하면 429이며, 이미 시작한 SSE에서는 error 이벤트로 전달한다. 외부 호출을 세션 모니터 안에서 실행하지 않아 JDK 21 가상 스레드의 캐리어 pinning을 피한다. 결정 근거는 [ADR 0055](../adr/0055-conversation-card-stream.md)에 기록했다.

## 오류·연결 종료

스트림 시작 전의 잘못된 입력·미인증·권한 없음·없는 세션 컨텍스트·처리 상한 오류는 기존 HTTP 상태/JSON 오류 규칙으로 처리한다. 앱은 먼저 HTTP 상태를 확인한다. 타인 세션은 404, 여행에서 나간 사용자는 403이다.

스트림 시작 후 예외가 발생하면 HTTP 상태는 이미 200이므로 `error` 이벤트의 `status`를 확인한다. 일반 API와 같은 `code`, `message`, `status`, `traceId`와 기본 오류 필드를 사용한다. 기존 traceId가 있으면 유지하고 없으면 생성한다. `X-Trace-Id` 헤더와 이벤트의 ID는 같다. 오류 뒤에는 `done`을 보내지 않고 연결을 닫는다.

```text
event:error
data:{"timestamp":"2026-11-25T09:00:00Z","path":"/api/conversations/unique-session-id/messages/stream","error":"Internal Server Error","code":"INTERNAL_SERVER_ERROR","message":"요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.","status":500,"traceId":"example-trace-id"}

```

기존 Gemini 클라이언트가 실패를 빈 결과로 반환하는 경우의 기존 폴백 문구는 유지한다. 이 경우에는 기존 POST와 마찬가지로 정상 `reply`·`done`을 보낸다. 기존 POST는 실제 성공 응답의 후보만 표시 이력으로 기록하며, 이차 실패 폴백의 candidates가 null이면 후보 이력을 남기지 않는다. 스트림은 카드 전송이 성공한 뒤 폴백이 나와도 표시 후보 ID와 후보 요약을 기록해 다음 턴의 선호 표현을 해석할 수 있게 한다. 예기치 못한 예외가 나면 완성되지 않은 턴을 부분 기록하지 않는다.

클라이언트 중단·타임아웃은 Gemini 호출을 취소하지 않는다. 서버는 처리와 기록을 마치고 같은 세션으로 다음 대화를 받을 수 있다. 전송은 60초까지만 유지한다. 앱이 `done` 없이 EOF/네트워크 오류를 받으면 상태를 미확인으로 표시한다. 카드 뒤 `error`가 온 경우에도 답장 실패를 표시한다. 자동 재연결·자동 POST 재전송을 하지 않는다. 재전송은 새 턴·도구 쓰기를 중복 실행할 수 있다. SSE 이벤트 ID·재생·`Last-Event-ID` 복구 기능은 제공하지 않는다.

## 관측과 검증

- `conversation.stream.cards` (`conversation_stream_cards_seconds_*`): 세션 검증과 상한 확보 직후부터 `cards` 전송 성공까지. 카드가 없는 턴·전송 전에 끊긴 연결은 기록하지 않는다. 앱 렌더링 시각이나 실제 네트워크 수신 시각을 뜻하지 않는다.
- `conversation.stream.complete` (`conversation_stream_complete_seconds_*`), `outcome=success|error`: 같은 시작점부터 서버 처리·저장 및 최종 이벤트 전송 시도까지. 클라이언트가 끊겨도 서버 처리가 끝나면 기록한다. 사용자/여행/세션 ID 라벨은 없다.
- HTTP 지표 URI는 `/api/conversations/{sessionId}/messages/stream`이다. HTTP 지연에는 스트림 전체 수명이 들어가므로 `HighHttpLatencyP95` 일반 요청 경보 집계에서 이 URI를 별도로 검토해야 한다. 이 변경에서 운영 경보 설정은 수정하지 않는다.
- 대화 시간 분해 근거: [v11 서버 측정](../monitoring/2026-11-25-v11-recheck/server-results.md), [부하 측정의 Gemini 분당 15회 한도](../monitoring/2026-11-25-load-test/README.md). 외부 API 호출 없이 테스트 더블로 계약과 순서를 검증한다.

### 2026-11-25 로컬 검증 결과

`./gradlew build --no-daemon` 전체 성공(1분 44초): 1,500개 중 실패/오류 0, 기존 조건부 테스트 6개 건너뜀. 테스트 제외 옵션은 사용하지 않았다. 프로세스 정리 테스트의 `ProcessHandle/sysctl` 조회가 필요해 샌드박스 밖의 새 Gradle 데몬으로 실행했다. 일반 샌드박스 및 그 데몬을 재사용한 실행의 환경 실패는 이 실행에서 해소됐다.

- `ConversationStreamContractTest` 7개: 실제 전송 버퍼의 카드 선도착, 도구 없는 턴, 오류 이벤트/traceId, 기존 JSON 카드 형식·기록 일치, 기존 경로와 이어지는 세션, 연결 종료 후 저장, 처리 상한, 턴 상한/기존 실패 폴백, 별도 타이머 검증.
- `ConversationStreamHttpTest` 3개: 실제 Tomcat/JWT에서 카드 flush, SSE Accept 요청의 시작 전 JSON 오류, 시작 후 오류의 헤더/본문 traceId 일치. Gemini와 도구는 테스트 더블이다.
- 기존 `ConversationControllerTest` 12개와 `ConversationServiceTest` 6개도 통과했다. 새 경로의 인증·입력·소유권·멤버십 거부를 포함한다.
- 변형 검사: `cards` 전송을 `reply` 뒤로 옮긴 임시 변형에서 `cards_are_flushed_before_second_Gemini_finishes_and_legacy_contract_and_state_match`가 실패(종료 코드 1)했다. 원복 후 전체 빌드가 통과했다.
- Gemini 2차 호출에 700ms 지연을 넣은 로컬 한 회: MockMvc 카드 전송 **1.5ms**, 전체 **704.2ms**; 실제 localhost HTTP 카드 수신 **140.9ms**, 전체 수신 **834.2ms**. HTTP 숫자는 인증·세션 검증·전송까지 포함한다. 외부 Gemini/Places 또는 운영 성능 측정값이 아니며, 실제 도구 실행 시간 개선을 주장하지 않는다.
