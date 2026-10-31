# 로컬 Kafka·별도 JVM 재시작 통합 검증 (#261, PR #263)

## 범위

실제 Spring Kafka 내장 브로커(2파티션), 서로 다른 Java 작업자 프로세스, 실제 리스너·분석 서비스·트랜잭션·JPA 저장소·H2 파일 DB·Python 프로세스 경계를 사용했다. 실행 중인 로컬 서버(8080)와 Docker 컨테이너, 운영 서버와 Supabase는 변경하지 않았다.

이 작업자는 전체 HTTP 서버가 아닌 테스트 전용 Spring 작업자다. 인증·공유 HTTP API는 실행하지 않는다. Gemini·카카오·자동 초안 등의 외부 연동은 대기 가능한 Python 출력과 mock으로 대체한다. 실제 영상 정확도·외부 호출·Aiven 지연이나 전체 서비스 E2E 검증은 아니다. 외부 API 쿼터를 사용하지 않았다.

## 강제 종료와 재시작

1. 새 JVM이 H2 파일 DB에 사용자와 작업을 만들고 Kafka 메시지를 소비한다.
2. Python fixture가 실행되면 PROCESSING 상태가 DB에 보이는지, 오프셋이 아직 완료 위치에 도달하지 않았는지 확인한다.
3. Java 프로세스를 강제로 종료한다. 테스트가 소유한 fixture 자식 프로세스도 명시적으로 정리한다.
4. 같은 파일 DB로 새 JVM을 띄운다. 원래 job ID가 DB에 반드시 있어야 하며, 없으면 새 작업으로 조용히 대체하지 않고 실패한다.
5. 기존 PROCESSING 작업이 로드되고, 같은 Kafka 메시지로 Python이 다시 실행되는 것을 확인한다.
6. fixture를 완료시킨 뒤 analysisStatus DONE, 저장 장소 1개, 커밋 오프셋 1을 확인한다.
7. 완료된 작업을 다시 전송하면 오프셋만 2로 올라가고 Python 실행/장소 저장은 늘지 않는다.

첫 실행에서 강제 종료한 시도까지 포함해 Python 실행은 2회, 완료 저장은 1회다. 강제 종료 전 부분 저장이나 외부 API 중복 부작용을 검증한 것은 아니며 exactly-once라고 해석하지 않는다.

## async 선행과 늦은 Kafka 수신

새 DB의 작업을 실제 @Async 경로로 먼저 실행해 fixture에서 대기시킨다. 같은 작업의 Kafka 메시지를 전송하고 실제 리스너의 수신 marker를 기다린다. 수신 이후 1초 동안 커밋 오프셋은 기존 2에 머물고 Python 실행은 1회임을 검사한다. fixture를 완료하면 오프셋 3, DONE, 저장 1개, Python 실행 1회를 확인한다.

리스너 marker는 파일 관찰만 추가하며 실제 ShareJobListener.onMessage를 호출한다. ACK 시간초과 자체를 네트워크로 주입하지는 않았다. 그 원인은 기존 제어 시나리오, 이 검증은 실제 늦은 중복 수신과 완료 전 오프셋 유지에 집중한다.

## fixture 보강

H2 기본 지연 쓰기에서는 SIGKILL 이후 마지막 PROCESSING 갱신 대신 PENDING이 복원되는 실행이 관측됐다. `WRITE_DELAY=0`으로 쓰기를 동기화하고, 재시작 시 동일 작업의 PROCESSING 로드를 명시적으로 검사한다. 데이터 파일을 새로 만들어 우연히 통과하지 않도록 seed job ID 파일도 확인한다. 이 조정은 운영 DB 설정 변경이 아니다.

작업자의 브로커 session timeout/heartbeat는 테스트 시간을 줄이기 위해 6초/1초다. 운영 복구 시간의 측정값으로 제시하지 않는다. 소비 동시성은 1이며 다중 JVM 동시 소비를 검증하지 않는다. 각 작업자는 별도로 실행되고 종료되며 동시에 같은 DB를 소비하지 않는다.

테스트 작업자의 환경 변수는 상속하지 않고 PATH만 전달한다. datasource는 임시 H2 파일, Kafka는 부모 테스트 브로커로 명시한다. @TestConfiguration으로 일반 앱/테스트의 컴포넌트 스캔에 섞이지 않게 했다.

## 실행 및 결과

`TROVA_RUN_PROCESS_RECOVERY=1 ./gradlew test --tests '*ShareJobProcessRecoveryTest' -q`

Java 21과 PATH의 python3가 필요하다. Docker·Supabase 자격 증명은 필요하지 않다. 일반 빌드에서는 이 통합 시나리오를 skip한다. 실행 증거는 `docs/bench/results/kafka-process-recovery-20261031.json`에 기록한다.

잠금을 제거한 negative control은 실제 Python 2개 실행(기대 1개)로 실패했고, 잠금을 복구한 최종 코드에서 통과했다. negative 원본은 `docs/bench/results/kafka-process-recovery-negative-20261031.json`. 최종 정상 원본은 위 positive 결과다. 작업자 JVM 종료도 확인했다. 전체 빌드 상태는 작업 기록에 남기며, PR 자동 머지·새 코드 배포는 하지 않는다.

일반 전체 빌드는 942개 중 941 통과·실패/오류 0·명시 실행용 통합 시나리오 1개 skip. 해당 시나리오는 위 opt-in 명령으로 별도 통과했다. 기존 localhost:8080 헬스 UP과 기존 Docker 컨테이너 목록 유지도 확인했다.
