# 04 외부 API 클라이언트 4종 테스트

먼저 `docs/codex-tasks/00-common-rules.md`를 읽고 따른다.

## 목표

외부 API를 직접 부르는 클라이언트 구현 4개에 전용 테스트를 만든다. 지금은 이 구현을 쓰는 테스트가 모두 인터페이스를 mock해서, 요청 구성과 재시도 코드가 한 번도 실행되지 않는다. 무료 한도를 지키는 재시도·백오프가 실제로 그렇게 동작하는지 테스트로 고정하는 것이 목적이다.

## 대상 (2026-11-16 기준, 시작 전에 줄이 맞는지 확인하고 다르면 보고)

| 클래스 | 볼 곳 |
|---|---|
| `src/main/java/com/trova/backend/geocoding/KakaoLocalApiClientImpl.java` | 생성자 `:28-38`(RestClient를 안에서 만듦), 재시도 `:72` 부근, 대기 `:105-107`, 근접 검색 `:58` |
| `src/main/java/com/trova/backend/weather/OpenWeatherApiClientImpl.java` | 생성자 `:28-34`, 재시도 `:44` 부근, 요청 `:64`, 대기 `:77-79` |
| `src/main/java/com/trova/backend/congestion/SeoulCongestionApiClientImpl.java` | 생성자 `:31-40`, 키 없음 처리 `:48`, 호출 `:53`, 예외 처리 `:61` |
| `src/main/java/com/trova/backend/recommendation/GooglePlacesApiClientImpl.java` | 생성자 `:44-49`, 재시도 `:105` 부근, 요청 필드 `:142`, 대기 `:178-180` |

- 기존 테스트 방식(따라 쓸 것): `src/test/java/com/trova/backend/conversation/GeminiChatClientImplTest.java`(`MockRestServiceServer.bindTo(RestClient.Builder)`), `src/test/java/com/trova/backend/planner/GeminiJsonClientImplTest.java`(대기 함수를 주입해 실제로 기다리지 않고 요청한 시간만 확인).

## 할 일

1. 네 클래스는 생성자 안에서 `RestClient`를 만들고 `Thread.sleep`을 직접 부른다. 테스트가 가짜 서버와 가짜 대기 함수를 넣을 수 있도록 **패키지 범위 생성자 하나**를 더한다(`RestClient.Builder`와 대기 함수를 받는 식 — 형태는 `GeminiJsonClientImpl` 등 기존 클래스가 쓰는 방식을 먼저 보고 맞춘다). 기존 public 생성자는 그 생성자를 부르도록만 바꾸고, 주소·헤더·시간 제한·재시도 횟수·대기 시간 값은 그대로 둔다.
2. 클래스마다 테스트 파일 하나(`src/test/java/com/trova/backend/<같은 패키지>/<클래스이름>Test.java`). Spring 컨텍스트를 띄우지 않는 단위 테스트로 쓴다.
3. 확인할 것:
   - 카카오·OpenWeather·Google: 429 → 다시 시도 → 성공, 5xx → 다시 시도, 정해진 횟수(코드의 상수)를 다 쓰면 마지막 예외를 그대로 던짐, 재시도 대상이 아닌 4xx는 한 번만 부르고 바로 던짐, 대기 시간이 코드에 적힌 값대로 요청됨, 대기 중 인터럽트면 인터럽트 상태를 되살리고 예외.
   - 카카오: 키워드·주소·근접 검색의 경로와 쿼리(근접 검색의 x·y·반경·정렬), `Authorization: KakaoAK ...` 헤더, 한글 검색어.
   - OpenWeather: `lat`·`lon`·`units=metric` 쿼리.
   - Google: `includedType`이 null일 때와 값이 있을 때 요청 본문 차이, 상세 조회와 전화번호 조회의 필드 마스크 차이.
   - 서울 혼잡도: 키가 null·공백이면 호출 없이 빈 결과, 정상 응답, 본문이 비었을 때, HTTP·역직렬화 예외면 빈 결과. 성공·실패 때 `ApiCallLogService.record`가 불리는지(이 의존 객체는 Mockito로 대신한다).
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
