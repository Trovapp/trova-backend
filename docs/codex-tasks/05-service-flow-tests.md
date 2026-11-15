# 05 일정 생성·소셜 로그인 연결 흐름 테스트

먼저 `docs/codex-tasks/00-common-rules.md`를 읽고 따른다. 이 작업은 **테스트 파일만 새로 만든다.** `src/main/` 아래는 고치지 않는다.

## 목표

여러 부품을 이어 주는 두 클래스에 전용 테스트를 만든다. 각 부품의 테스트는 있지만 이어 주는 흐름(실패했을 때 어느 단계에서 멈추고 무엇을 기록하는지)을 직접 부르는 테스트가 없다.

## 대상 (2026-11-16 기준, 시작 전에 줄이 맞는지 확인하고 다르면 보고)

| 클래스 | 볼 곳 | 지금 있는 관련 테스트 |
|---|---|---|
| `src/main/java/com/trova/backend/service/ItineraryGenerationService.java` (56줄, `generate` 하나) | 처리 시작 표시 `:41`, 작업 찾기 `:42-43`, 장소 읽기 `:44`, 러너 실행 `:46`, 결과 반영 `:47`, 완료 `:49`, 실패 처리 `:51-54` | `PlacesControllerTest`는 이 서비스를 mock한다 |
| `src/main/java/com/trova/backend/security/CustomOAuth2UserService.java` (41줄, `loadUser` 하나) | 상위 호출 `:26`, 속성 변환 `:29`, 사용자 저장 `:30`, 권한·반환 `:35-39` | `OAuth2UserInfoTest`(변환만), `UserUpsertServiceTest`(저장만) |

## 할 일

1. `src/test/java/com/trova/backend/service/ItineraryGenerationServiceTest.java` — 의존 객체를 Mockito로 대신하는 단위 테스트.
   - 작업을 찾지 못하면 러너가 실행되지 않고 실패로 기록된다.
   - 저장된 장소가 빈 목록일 때: 이 클래스는 빈 목록을 그대로 러너에 넘긴다. 러너가 예외를 던지면 실패로 기록되는 것까지만 확인한다.
   - 러너가 예외를 던지면 완료 표시를 하지 않고 실패로 기록된다.
   - 결과 반영이 예외를 던지면 완료 표시를 하지 않고 실패로 기록된다.
   - 정상이면 처리 시작 → 러너 → 결과 반영 → 완료 순서로 부르고(`InOrder`), **실패 기록(`markFailed`)을 부르지 않는다(`never()`)**.
   - 처리 시작 표시가 예외를 던지면 작업 조회와 러너를 부르지 않고 실패로 기록된다.
   - 완료 표시가 예외를 던지면 실패로 기록된다(지금 코드는 같은 `try` 안이라 그렇게 된다 — 지금 동작대로 쓰고 "따로 볼 것"에 적는다).
   - 실패 기록에 예외 메시지가 그대로 넘어간다.
   - `@Async` 메서드이지만 Spring 없이 객체를 직접 만들어 같은 스레드에서 부른다.
2. `src/test/java/com/trova/backend/security/CustomOAuth2UserServiceTest.java`
   - 상위 `loadUser`(`:26`의 `super.loadUser`)는 실제 네트워크 호출이다. 하위 클래스에서 `loadUser`를 다시 정의하거나 `spy`로 `loadUser`를 바꾸면 **검증하려는 본문이 실행되지 않으므로 쓰지 않는다.** 상위 `DefaultOAuth2UserService`의 `setRestOperations(RestOperations)`에 `MockRestServiceServer`를 묶은 `RestTemplate`을 넣어 사용자 정보 응답만 가짜로 준다(지금 의존성 spring-security-oauth2-client 7.1.0에 이 메서드가 있는 것을 확인했다). 상위 클래스가 기대하는 응답 변환 설정은 상위 클래스 소스를 보고 맞춘다. **그래도 클래스를 고쳐야만 테스트할 수 있으면 만들지 말고 그 이유를 보고한다.**
   - 지원하지 않는 provider면 사용자 저장을 부르지 않고 실패한다.
   - 카카오 응답에 `id`는 있고 `kakao_account`가 없으면 사용자 저장 전에 실패한다. 사용자 이름 속성(`id`, 구글 `sub`)을 뺀 응답은 상위 클래스에서 먼저 실패할 수 있어 이 클래스의 코드를 타지 않으니 쓰지 않는다. 지금 동작은 `NullPointerException`으로 보인다(`src/main/java/com/trova/backend/security/OAuth2UserInfo.java:33-34`) — 고치지 말고 지금 동작대로 쓰고 "따로 볼 것"에 적는다.
   - 성공하면 원본 속성과 사용자 이름 속성이 유지되고 `ROLE_USER`가 붙는다.
   - 사용자 저장이 예외를 던지면 그대로 전달된다.

## 건드릴 파일

- 위 새 테스트 파일 2개만

## 건드리지 말 파일

- `src/main/` 전부
- 기존 테스트 파일, `src/test/resources/`, `build.gradle`, `docs/`, `pipeline-test/`

## 완료 기준

- 새 테스트가 Spring 컨텍스트·네트워크·Python 프로세스를 띄우지 않는다.
- 테스트가 구현을 그대로 베낀 검증(호출 순서만 나열)에 그치지 않고, 위에 적은 실패 경우마다 "무엇이 일어나지 않아야 하는지"(`never()`)를 확인한다.
- 테스트를 쓰다 클래스의 동작이 이상해 보이면 고치지 말고 지금 동작대로 쓰고 보고의 "따로 볼 것"에 적는다.
- `./gradlew build` 통과.

## 검증 명령

```bash
./gradlew build
```
