# Kafka ACK 지연·늦은 메시지 재현 (#261)

## 확인한 것

명시 실행용 JUnit 시나리오에서 실제 `KafkaShareJobQueue`와 `ShareJobListener` 코드를 사용했다. 전송 future의 get(timeout)은 시간초과로 제어했고, 저장소와 분석 서비스는 mock이다.

1. enqueue(42)가 ACK 시간초과로 async process(42)를 1번 호출함.
2. future는 취소되지 않았고, 이후 정상 완료가 가능함을 확인함.
3. async가 진행 중인 상태(PROCESSING)로 두고 늦은 Kafka 메시지를 실제 리스너에 전달함.
4. 같은 작업의 processNow(42, KAFKA)가 추가로 1번 호출됨.

원본: `docs/bench/results/kafka-late-ack-reproduction-20261031.json`.

## 범위와 한계

두 실행 경로에 같은 작업이 진입할 수 있다는 코드 경로 재현이다. 실제 브로커의 ACK 지연·네트워크 장애를 주입한 통합 실험은 아니며, 실제 Gemini 중복 호출·DB 장소 중복 저장을 실행하지 않았다. 이 테스트는 현재 위험의 진단 시나리오이지 바람직한 동작의 회귀 계약이 아니다. 정상 빌드에서는 꺼져 있고 다음 명령으로만 실행한다.

`TROVA_REPRODUCE_LATE_ACK=1 ./gradlew test --tests '*KafkaShareJobQueueTest.전송_대기_시간초과_후_늦은_메시지가_두_실행경로에_진입한다'`

## 후속 이슈

https://github.com/Trovapp/trova-backend/issues/261

단순히 PROCESSING 메시지를 모두 버리면 프로세스 재시작 후 재수신 복구도 막을 수 있다. 원자적 작업 선점, 동시 실행 방지와 재시작 복구를 함께 설계해야 한다. 이번 단계에서는 재현·기록만 했으며 운영 로직을 수정하거나 배포하지 않았다.
