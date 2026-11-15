# 04 외부 API 클라이언트 4종 테스트

먼저 `docs/codex-tasks/00-common-rules.md`를 읽고 따른다.

## 목표

외부 API를 직접 부르는 클라이언트 구현 4개에 전용 테스트를 만든다. 지금은 이 구현을 쓰는 테스트가 모두 인터페이스를 mock해서, 요청 구성과 재시도 코드가 한 번도 실행되지 않는다. 무료 한도를 지키는 재시도·백오프가 실제로 그렇게 동작하는지 테스트로 고정하는 것이 목적이다.

## 대상 (2026-11-16 기준, 시작 전에 줄이 맞는지 확인하고 다르면 보고)

| 클래스 | 볼 곳 |
|---|---|
| `src/main/java/com/trova/backend/geocoding/KakaoLocalApiClientImpl.java` | 생성자 `:29-39`(RestClient를 안에서 만듦), 재시도 `:72-90` 부근(잡는 예외 `:78-80`), 대기 `:105-112`, 근접 검색 `:58` |
| `src/main/java/com/trova/backend/weather/OpenWeatherApiClientImpl.java` | 생성자 `:28-38`, 재시도 `:44` 부근(잡는 예외 `:47-49`), 요청 `:64-70`(`appid` `:70`), 대기 `:77-84` |
| `src/main/java/com/trova/backend/congestion/SeoulCongestionApiClientImpl.java` | 생성자 `:31-44`, 키 없음 처리 `:48`, 호출 `:53-56`(경로 `:54`), 예외 처리 `:61`. **재시도와 대기가 없다** |
| `src/main/java/com/trova/backend/recommendation/GooglePlacesApiClientImpl.java` | 생성자 `:44-54`(`X-Goog-Api-Key` `:51`), 필드 마스크 상수 `:30-34`, 영업시간 검색 `:86-103`, 재시도 `:105` 부근(잡는 예외 `:111-113`), 요청 필드 `:142`, 대기 `:178-185` |

- 기존 테스트 방식(따라 쓸 것): `src/test/java/com/trova/backend/conversation/GeminiChatClientImplTest.java`(`MockRestServiceServer.bindTo(RestClient.Builder)`), `src/test/java/com/trova/backend/planner/GeminiJsonClientImplTest.java`(대기 함수를 주입해 실제로 기다리지 않고 요청한 시간만 확인).

## 할 일

1. 네 클래스는 생성자 안에서 `RestClient`를 만든다. 카카오·OpenWeather·Google은 `Thread.sleep`도 직접 부른다. 테스트가 가짜 서버(와 가짜 대기 함수)를 넣을 수 있도록 **패키지 범위 생성자 하나**를 더한다. 기존 public 생성자는 그 생성자를 부르도록만 바꾸고, 주소·헤더·시간 제한·재시도 횟수·대기 시간 값은 그대로 둔다.
   - 카카오·OpenWeather·Google: `RestClient.Builder`와 대기 함수를 받는다. 대기 함수는 `src/main/java/com/trova/backend/planner/GeminiJsonClientImpl.java:45-47`의 `Sleeper`처럼 `throws InterruptedException` 형태로 하고, `InterruptedException`을 잡아 인터럽트 상태를 되살리는 코드는 **클래스에 그대로 둔다**(대기 함수 안으로 옮기면 테스트가 그 코드를 타지 못한다).
   - 서울 혼잡도: 대기 함수 인자는 넣지 않는다(`RestClient.Builder`만).
2. 클래스마다 테스트 파일 하나(`src/test/java/com/trova/backend/<같은 패키지>/<클래스이름>Test.java`). Spring 컨텍스트를 띄우지 않는 단위 테스트로 쓴다.
3. 확인할 것:
   - 카카오·OpenWeather·Google: 429 → 다시 시도 → 성공, 5xx → 다시 시도, 연결 실패·시간 초과(`ResourceAccessException` — `MockRestServiceServer`의 `withException(new IOException(...))`) → 다시 시도 → 성공, 정해진 횟수(코드의 상수)를 다 쓰면 마지막 예외를 그대로 던짐, 재시도 대상이 아닌 4xx는 한 번만 부르고 바로 던짐, 대기 시간이 코드에 적힌 값대로 요청됨, 대기 중 인터럽트면 인터럽트 상태를 되살리고 예외(이 테스트는 `finally { Thread.interrupted(); }`로 상태를 지운다 — `src/test/java/com/trova/backend/planner/GeminiJsonClientImplTest.java:162`).
   - 카카오: 키워드·주소·근접 검색의 경로와 쿼리(근접 검색의 x·y·반경·정렬), `Authorization: KakaoAK ...` 헤더, 한글 검색어.
   - OpenWeather: `lat`·`lon`·`units=metric`·`appid` 쿼리.
   - Google: `X-Goog-Api-Key` 헤더, `includedType`이 null일 때와 값이 있을 때 요청 본문 차이, 근처 검색·텍스트 검색·영업시간 검색·상세·전화번호 조회 **각각의 `X-Goog-FieldMask` 값이 코드의 상수와 정확히 같은지**(필드가 늘면 요금 등급이 올라간다 — `:30-31` 주석), 텍스트 검색과 영업시간 검색의 본문(`locationBias` 포함).
   - 서울 혼잡도: 요청 경로(키와 한글 지역 이름), 키가 null·공백이면 호출 없이 빈 결과, 정상 응답, 본문이 비었을 때, HTTP·역직렬화 예외면 빈 결과. 성공·실패 때 `ApiCallLogService.record`가 불리는지(이 의존 객체는 Mockito로 대신한다).
4. 테스트의 API 키는 `"test-key"` 같은 가짜 문자열만 쓴다.

## 건드릴 파일

- 위 네 클래스(생성자 추가와, 대기 함수를 쓰도록 바꾸는 최소한의 줄만)
- 새 테스트 파일 4개

## 건드리지 말 파일

- 네 클래스의 인터페이스와 응답 `record`
- 이 클라이언트를 쓰는 서비스와 그 테스트
- `build.gradle`, `src/main/resources/`, `src/test/resources/`, `docs/`, `pipeline-test/`

## 완료 기준

- 네 클래스의 기존 동작(주소, 헤더, 시간 제한, 재시도 횟수, 대기 시간)이 바뀌지 않았다. `git diff`에서 값 변경이 없어야 한다.
- 새 테스트가 실제 네트워크에 나가지 않고, 실제로 기다리지 않는다(테스트 전체가 몇 초 안에 끝남).
- 테스트를 쓰다 클래스의 동작이 이상해 보이는 곳(예: 재시도해야 할 응답을 재시도하지 않음)을 찾으면 **고치지 말고** 지금 동작대로 테스트를 쓰고 보고의 "따로 볼 것"에 적는다.
- `./gradlew build` 통과.

## 검증 명령

```bash
./gradlew build
```
