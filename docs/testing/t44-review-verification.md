# T44 / PR #539 리뷰 수정 검증

2026-11-25. 외부 Gemini와 도구는 테스트 더블이며 운영 API·DB를 호출하지 않았다.

## 테스트 먼저 확인

수정 전 코드에 회귀 테스트를 추가한 첫 실행에서 세션 모니터 보유와 기존 POST 폴백의 잘못된 표시 후보 기록을 각각 실패로 확인했다(13건 중 2건 실패). 기존에 올바르게 동작하던 완료 슬롯 반환·초기화 실패·빈 후보·후보 없는 선호 저장은 회귀 테스트를 보강했다. 새 테스트 9건(계약 5, 실제 HTTP 1, 직렬화 3)을 추가했고 기존 스트림 테스트 10건의 이름을 한글로 바꾸고 와일드카드 import와 var를 제거했다.

## Pinning 검증

로컬 Temurin 21.0.9에서 다음 init 스크립트로 CPU 2개, 가상 스레드 캐리어 최대 2개를 고정했다. 운영의 21.0.12 자체를 실행한 결과는 아니다.

```groovy
allprojects {
    tasks.withType(Test).configureEach {
        jvmArgs '-XX:ActiveProcessorCount=2',
                '-Djdk.virtualThreadScheduler.parallelism=2',
                '-Djdk.virtualThreadScheduler.maxPoolSize=2',
                '-Djdk.tracePinnedThreads=full'
        testLogging.showStandardStreams = true
    }
}
```

위 내용을 `/tmp/t44-verification.init.gradle`에 저장하고 실행한다.

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --no-daemon \
  --project-cache-dir /tmp/t44-gradle-cache -I /tmp/t44-verification.init.gradle \
  test --tests '*ConversationSerializationTest' --tests '*ConversationStreamContractTest'
```

`가상_스레드의_외부_호출은_세션_모니터를_잡거나_캐리어를_고정하지_않는다`는 JFR의 `jdk.VirtualThreadPinned`를 임계값 0으로 켜고, 가상 스레드에서 Gemini 1차·도구·Gemini 2차 대기를 각각 재현한다. ConversationService가 스택에 있는 pinning 이벤트 0건과 세 단계의 세션 모니터 미보유를 단언한다. 이 검증은 세션 모니터로 인한 pinning 회귀를 다루며 실제 HTTP/JDBC 라이브러리 전체의 pinning 부재를 주장하지 않는다. 실제 HTTP 카드 전송·429 계약은 별도 Tomcat/JWT 통합 테스트로 검증한다.

`같은_세션의_둘째_요청은_첫_이차_호출과_저장_뒤에_진행한다`는 첫 SSE 서비스 호출의 두 번째 Gemini를 래치로 멈추고 기존 POST 서비스 호출을 동시에 넣어 호출 순서와 최종 히스토리를 단언한다. `세션_잠금은_삼십초까지만_기다리고_시간이_지나면_호출하지_않는다`는 실제 30초 대기 상한과 외부 호출 미실행을 검증한다.

## 변형 검사

각 변형을 하나씩 임시 적용하고 해당 테스트만 실행한 다음 원본으로 복원했다. 세 경우 모두 컴파일 오류가 아닌 테스트 단언 실패(종료 코드 1)를 확인했다.

| 번호 | 임시로 되돌린 동작 | 실패한 테스트 |
| --- | --- | --- |
| 1 | 세션 ReentrantLock 대신 synchronized(state)로 한 턴 실행 | 가상 스레드 외부 호출의 JFR pinning 이벤트 부재 단언 |
| 2 | 카드 전송 여부·POST 성공과 관계없이 도구 후보를 표시 후보로 기록 | 기존 메시지 이차 실패의 표시 후보 ID 비어 있음 단언 |
| 3 | 스트림 처리 finally의 슬롯 반환 제거 | 완료 후 perUser=1인 두 번째 HTTP 스트림 성공 단언 |

정상 코드로 복원한 뒤 다음 전체 빌드가 2분 45초에 성공했다.

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --no-daemon \
  --project-cache-dir /tmp/t44-gradle-cache build
```

전체 1,509건 중 1,503건 통과, 기존 조건부 테스트 6건 건너뜀, 실패·오류 0건이다. 대화 계약 12건·실제 HTTP 4건·직렬화 3건도 모두 통과했다. 테스트 제외 옵션을 사용하지 않았다. Gradle 데몬이 다른 샌드박스의 파일 권한을 재사용하는 문제를 피하려고 새 데몬과 별도 프로젝트 캐시를 사용했다.

## 재검토 후 main 병합과 문서 정리

태그 재사용 PR #540이 합쳐진 `origin/main` (`bd8871a`)을 기존 PR 브랜치에 병합했다. 태그 재사용 ADR 0055를 유지하고 대화 스트림 결정은 [ADR 0056](../adr/0056-conversation-card-stream.md)으로 옮겼으며, ADR 목록에는 0055 다음에 0056을 두었다. 경험 기록의 T44 절은 2026-11-23 운영 배포 항목 아래로 옮겨 배포 기록이 T44에 속하지 않도록 했다.

첫 전체 빌드는 macOS 샌드박스가 `ProcessHandle.descendants()`의 sysctl 조회를 막아 기존 프로세스 실행기 테스트에서 `Operation not permitted (sysctl failed)`로 실패했다. 프로세스 조회가 허용된 환경에서 테스트를 제외하지 않고 같은 전체 빌드를 다시 실행했다.

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --no-daemon \
  --project-cache-dir /tmp/t44-final-gradle-cache build
```

결과: `BUILD SUCCESSFUL in 2m 36s`, 전체 1,525건 중 1,519건 통과·기존 조건부 6건 건너뜀·실패/오류 0건. 수정한 문서의 상대 링크와 `git diff --check`도 통과했다.
