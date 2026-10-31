# PR #262·#263·#265 조합 검증 (#266)

2026-10-31, main `005ac63`에서 만든 로컬 `test/#266-combined-verification` 브랜치에서 세 PR의 변경을 합쳐 검증했다. GitHub 머지·운영 배포는 하지 않았다. 이 브랜치는 조합 재현용이며 원본 PR 대신 머지할 용도가 아니다.

## 입력과 충돌 처리

| PR | 검증한 커밋 | 변경 |
|---|---|---|
| #262 | `ff095d5af64efe15cfb244c9e680fd3a533c80f7` | 추출 진단·평가 호환성·성능 기록 |
| #263 | `086c4b568649ac07674afd4d31aa2a41f413893d` | 단일 JVM 중복 분석 방지·Kafka 재시작 검증 |
| #265 | `a913296a0ccdc8fa070efd9250b71ae7efc8b89b` | Python 프로세스 종료·출력 수집·정리 |

각 커밋과 공통 main 사이의 binary diff를 순서대로 `git apply --3way --index`로 적용했다. #263과 #265 적용 때 `docs/experience-notes.md`의 끝에 덧붙인 기록만 충돌했다. 공통 본문과 각 PR의 추가 부분을 모두 보존했다. 제품 코드·테스트·평가 파일은 각 원본 PR 커밋의 바이트와 일치하는지 확인했다. 별도 제품 코드 수정은 없었다.

실제 순차 머지 때도 main에 먼저 들어간 작업 기록을 남기고 다음 PR의 추가 기록을 이어 붙여야 한다. PR head 또는 main이 바뀌면 이 결과만으로 새 조합을 통과로 간주하지 않는다.

## 검증 결과

| 검사 | 결과 |
|---|---|
| `./gradlew build -q` | 951개 중 948 통과, 실패·오류 0, opt-in 3개 skip |
| Python pipeline unittest | 48개 통과 |
| Python 평가 provider unittest | 3개 통과 |
| Node 채점·오류 격리·CLI 회귀 검사 | 통과 |
| 실제 promptfoo 설정 + echo 제공자 오프라인 10건 | 기존 각 사례 pass/score/recall/precision 동일, 3 성공·7 채점 실패·실행 오류 0 |
| 기존 10건 엄격 재채점 | 유효 10/10, recall 0.6637121212·precision 0.6417857143 유지 |
| 로컬 큐 벤치마크 opt-in | 1개 통과, 각 경로 워밍업 제외 n=20 |
| ACK 지연 모의 진입 opt-in | 1개 통과 |
| 실제 Kafka + 별도 JVM 재시작 opt-in | 1개 통과 |

기본 빌드에서 건너뛴 세 테스트는 아래처럼 순차 실행해 각각 테스트 XML의 실패·오류·skip 0을 확인했다. 뒤의 선택 실행이 Gradle 테스트 결과를 덮어쓰므로 전체 빌드 집계와 각 실행 집계는 별도 JSON에 보관했다.

```bash
TROVA_RUN_QUEUE_BENCH=1 ./gradlew test --tests '*ShareJobKafkaRoundTripTest.로컬_async와_Kafka_전달_지연을_비교한다' -q
TROVA_REPRODUCE_LATE_ACK=1 ./gradlew test --tests '*KafkaShareJobQueueTest.전송_대기_시간초과_후_늦은_메시지가_두_실행경로에_진입한다' -q
TROVA_RUN_PROCESS_RECOVERY=1 ./gradlew test --tests '*ShareJobProcessRecoveryTest' -q
```

테스트가 고정 파일명으로 출력한 세 JSON은 `combined-266-` 접두사로 따로 보관하고 기존 역사적 원본은 복원해 바이트 일치를 확인했다. 재실행 시에도 같은 보관 절차가 필요하다.

## 함께 동작하는 경계

- #263의 실제 서비스와 #265의 실제 PipelineRunner를 함께 사용해, PROCESSING·미커밋 상태에서 JVM 강제 종료 → 동일 파일 DB 상태 재조회 → 재수신 → DONE·저장 1개·offset 1을 확인했다. 강제 종료된 실행까지 Python 시도는 2회이며, 중단 전 외부 호출의 exactly-once를 뜻하지 않는다.
- 완료 작업 재전송은 추가 분석·저장 없이 offset 2까지 진행했다.
- async 선행 분석 중 같은 작업을 Kafka가 받으면 완료 전 offset 2를 유지하고, 완료 후 3으로 진행했다. 이 시나리오의 Python 실행과 장소 저장은 각각 1회였다. 세 작업자 JVM의 종료도 확인했다.
- 전체 빌드에는 실제 Python 부모·관찰 자식의 인터럽트/시간초과/콜백 실패/정상 완료 정리, 큰 한국어 stdout, 탐색·종료 예외 주입 검사가 포함된다.
- ACK 지연 모의 테스트는 분석 서비스를 mock으로 바꾸므로 두 전달 경로에 진입하는 원인을 계속 재현한다. 중복 분석 방지의 합격 근거는 실제 서비스 조합 테스트다.
- 새 API 호출은 0회다. promptfoo의 7건 실패는 기존 예측의 정확도 판정이며 실행 장애가 아니다. 정답과 기존 합격 기준은 변경하지 않았다.

새 로컬 전달 중앙값은 async 0.074375ms·Kafka 6.95275ms였다. 저장소·분석 mock 기반 전달 비용이며 실제 서비스 전체 속도나 수정 전후 성능 비교가 아니다. 기존 REST/Spring AI 및 운영 async/Kafka 비교는 [시스템 비교](../bench/system-comparison-20261031.md)와 [운영 비교](../bench/production-queue-ab-20261031.md)를 그대로 참고한다.

## 범위와 후속

내장 Kafka·H2 파일 DB·별도 Spring JVM·Python fixture의 검증이다. 운영 Aiven·Supabase·HTTP 인증·실제 Gemini를 묶은 검증, 부분 저장 후 복구, 다중 JVM 동시 소비, 장시간 max.poll 부하까지 검증하지 않았다. JVM SIGKILL은 finally가 실행되지 않으므로 이번 재시작 테스트를 임시 파일 전수 정리 보장으로 해석하지 않는다.

세 PR의 해당 커밋 조합에서는 새 실패를 발견하지 않았다. 다음 단계는 사용자 선택 후 순차 머지와 변경된 main 확인이며, 운영 배포는 별도로 선택한다. 잠자기 복구는 계속 보류한다.

원본과 집계: [조합 검증 JSON](../bench/results/combined-266-verification-20261031.json), [재시작 결과](../bench/results/combined-266-kafka-process-recovery-20261031.json), [전달 표본](../bench/results/combined-266-local-queue-latency-20261031.json), [ACK 모의 진입](../bench/results/combined-266-kafka-late-ack-reproduction-20261031.json).


## 사용자 승인 후 실제 머지 및 main 검증

사용자의 "진행해줘 순서대로"에 따라 #262 → #263 → #265를 squash 머지했다. #263과 #265에는 각각 그 시점 main을 merge하고 작업 기록의 충돌을 해결했다. 커밋 전 전체 build는 각각 944 tests(941 통과·3 skip), 951 tests(948 통과·3 skip)였으며 실패·오류는 없었다. head를 명시해 머지했으며 최종 main은 `4307b79ace781dec36b26bff8cb5df83995a041e`다.

최종 main의 src/pipeline-test/eval 파일이 앞선 로컬 조합 `3fa9bbb`와 동일함을 확인했다. 해당 main에서 `./gradlew clean build -q`를 다시 실행해 951개 중 948 통과·실패/오류 0·명시 실행용 3 skip을 확인했고, 실제 Kafka/별도 JVM 재시작 테스트를 다시 명시 실행해 1개 통과·skip 0을 확인했다. 다른 두 opt-in은 앞서 동일 코드 조합에서 통과한 결과를 유지하며 추가 재측정하지 않았다.

원격 머지 커밋은 #262 `4d37e62`, #263 `ac6c949`, #265 `4307b79`다. 원래의 로컬 통합 커밋을 머지한 것이 아니다. 운영 JAR/Python 교체·서비스 재시작·실제 API 호출은 하지 않았다. [main 검증 집계](../bench/results/merged-main-266-verification-20261031.json)와 [재시작 원본](../bench/results/merged-main-266-kafka-process-recovery-20261031.json)을 별도로 보관했다.
