# 기존 방식·Spring AI 2·Kafka 비교 정리 (#286)

> 후속 실제 표본 확대: [새 Java 각20회·운영 각6회 비교](expanded-system-comparison-20261101.md). 이 문서 아래는 이전 원본 재계산 기록입니다.

현재 근거로 Spring AI 2나 Kafka의 전체 처리 속도 향상을 입증하지는 못했다. Spring AI 2는 Java AI 호출의 통합과 계측을 제공하고, Kafka는 전달 비용을 추가하는 대신 재수신 경로를 제공한다. 아래 수치는 저장된 원본을 다시 계산한 결과이며 새 외부 API 실험을 수행한 것은 아니다.

## 도입 순서와 비교 범위

| 단계 | 기준 커밋 | Java 텍스트/JSON 호출 | 영상 시작 대기열 |
|---|---|---|---|
| 최초 | `97fc866` | RestClient | async |
| Kafka 도입 | `1666fee` | RestClient | Kafka/async 선택 |
| Spring AI 2 도입 | `15c1406` | Spring AI 2 | Kafka/async 선택 |
| HTTP 수정 및 후속 보강 | `2b1b56e` 이후, 이번 배포 `bd196e1` | Spring AI 2·HTTP/1.1 | 운영 Kafka·실패 시 async 폴백 |

실제 도입은 **REST+async → REST+Kafka → Spring AI 2+Kafka**다. Spring AI만 있는 중간 운영 빌드는 없었다. Python 영상 추출은 별도이며 Java 클라이언트 전환에 따른 추출 정확도 향상을 주장하지 않는다. 세 빌드의 전체 API·DB·LLM·메모리를 한 조건으로 비교한 실험은 아직 없다.

따라서 클라이언트 자체와 대기열 효과를 분리한다. 정상 async와 브로커 실패 뒤 async 폴백도 구분한다. 폴백에는 Kafka 실패 대기 비용이 섞인다.

## 재계산한 측정값

| 비교·환경·표본 | 기존 방식 | 도입 방식 | 해석 |
|---|---:|---:|---|
| Java 동일 요청 중앙값·로컬·각n=4 | REST 3,656.64ms | Spring AI 2 3,636.54ms | 약20.10ms 차이, 속도 이득 입증 불가 |
| 작업 전달 중앙값·로컬·각n=20 | async 0.0549ms | Kafka 6.6327ms | 브로커 전달 오버헤드 관찰 |
| 작업 전달 표본 p90·로컬·각n=20 | async 0.0771ms | Kafka 6.7629ms | nearest-rank, 운영 p90 아님 |
| 작업 생성→분석 시작 중앙값·운영·각n=4 | 정상 async 135ms | Aiven Kafka 574ms | 같은 코드·영상에서 Kafka +439ms |
| 공유 HTTP 응답 중앙값·운영·각n=4 | 정상 async 400.33ms | Aiven Kafka 834.25ms | Mac 클라이언트 왕복 포함 |
| 분석 시작→저장 완료 중앙값·운영·각n=4 | 정상 async 22.06초 | Aiven Kafka 21.45초 | LLM 변동·재시도 혼재, 속도 이득 입증 불가 |
| 작업 생성→저장 완료 중앙값·운영·각n=4 | 정상 async 22.20초 | Aiven Kafka 21.75초 | 전체 차이 약0.44초, 인과 효과로 해석 불가 |
| 안정 후 Java RSS 중앙값·운영·각n=4 | 정상 async 380.6MiB | Aiven Kafka 392.7MiB | JVM 재시작/GC/캐시 미통제 |

Java 비교는 같은 프로세스의 두 클라이언트, 같은 모델·temperature·JSON 요청을 사용했다. 순서를 교대하고 호출 사이6초를 두며 각각 워밍업1회를 제외했다. 측정8회 모두 성공했지만 n=4로 실제 실패율/p90을 추정하지 않는다. 오류 경로의 timeout 의미까지 완전히 같지는 않다.

로컬 큐 비교는 내장 Kafka·동일 작업 ID·mock 저장소/분석서비스다. enqueue→메서드 진입만 측정했고 DB·Gemini·운영 TLS·부하·HTTP 프록시 비용 전체를 포함하지 않는다.

운영 비교는 같은 JAR/Python/영상 하나로 Kafka→정상 async→Kafka 순서의 직렬11건이다. 블록별 워밍업3건을 제외해 경로별4건, 전체11/11분석 성공이다. 무작위 순서·포화 부하 시험이 아니다. 측정 대상의 Gemini 논리 호출은 각12회였고, 실제 시도 합계는 async14/Kafka12, 대기는 async15초/Kafka0이었다. 따라서 전체 지연 차이를 큐의 효과로 분리할 수 없다. 자동 초안은 원래대로 켜 두었으며 위 완료시간은 분석 저장 완료까지다.

## 자원과 신뢰성

과거 로컬 JAR 기록은 기준71.2MB, Kafka 도입83.9MB, Spring AI 작업95.9MB다. 당시 산출물/자원 로그의 정확한 커밋을 모두 복원하지 못했으므로 이 차이를 각 기술의 독립 증가분으로 합산하지 않는다. 새 배포 JAR 해시는 [배포 기록](../reviews/production-release-286-20261101.md)에 따로 둔다.

| 경계 | 확인한 근거 | 제한 |
|---|---|---|
| Java AI 호출 | 운영 초기6/6 timeout → HTTP/1.1 수정 후 성공 기록, 앱 재시도 한곳으로 통합 | REST 대비 속도/호출수 개선률 아님; SDK 도입 중 중첩 재시도 제거 |
| Kafka ACK 지연·async 폴백 | 제어 시나리오에서 같은 작업이 두 경로로 진입 가능함을 재현 | 실제 네트워크 ACK 지연 주입이나 외부 API exactly-once 증명 아님 |
| 실행 중복 억제·재시작 | 후속 #261/#263의 실제 Kafka·별도 JVM·파일 H2·Python fixture에서 동시 실행1회/저장1개, 강제 종료 후 PROCESSING 재로드·재수신 확인 | 단일 JVM 실행 잠금; 다중 JVM 선점·부분 저장/외부 호출 중복 보장 아님 |
| Python 취소·시간초과 | #284/#285에서 보조5개 기존실패 재현, 종료 확인 후 정리·미확인 파일 보존, 전체1010검사 | 관찰 자식 기준이며 JVM SIGKILL/새 fork/원자적 프로세스 그룹 보장 아님 |
| 브로커 전원 중단 | 과거 Aiven 전원 중단 뒤 오프셋 초기화 관측 기록 존재 | 무조건 무손실 주장 불가; 실패 정리는 자동 재처리와 다름 |

#261의 과거 "중복 위험 재현·미해결"은 당시 상태다. 후속 로컬 통합 검증과 이미 머지된 단일 JVM 중복 억제를 함께 읽어야 한다. 최신 공통 종료 helper 적용 소스의 [복구 결과](results/auxiliary-process-recovery-20261101.json)는 강제 종료 시도 포함 실행2/완료저장1, 늦은 중복 수신 실행1/저장1을 보여 준다. 운영 Aiven 강제 중단 실험을 새로 수행한 것은 아니다.

## 근거와 재현

- [클라이언트 원본](results/client-latency-20261031.json), [로컬 큐 원본](results/local-queue-latency-20261031.json), [운영 비교 원본](results/production-queue-ab-20261031.json).
- [원본 해시와 재계산 JSON](results/system-comparison-summary-20261101.json), [재계산 스크립트](scripts/summarize-system-comparison.py): `python3 docs/bench/scripts/summarize-system-comparison.py`. 네트워크·키·실제 API 없이 기존 표본만 읽는다.
- [클라이언트/큐 상세 조건](system-comparison-20261031.md), [운영 A/B 조건·복구·테스트 데이터 정리](production-queue-ab-20261031.md), [실제 Kafka/JVM 복구 범위](../reviews/kafka-process-recovery-20261031.md).
- 이번 배포 smoke와 최근24시간 혼합 테스트는 비교 표본에서 제외한다. 배포 전 02:39 KST 운영 관찰(#284)의 알려진 테스트 제외 표본은0건이므로 실제 사용자 실패율을 제시하지 않는다.

추가 실험을 하게 된다면 동일 코드에서 클라이언트/대기열을 각각 전환하고 같은 영상 묶음·교대 순서·호출 간격·재시도 기록을 고정해야 한다. 요청 응답, 큐 대기, 분석시간, 외부 시도/대기, RSS를 분리하고 표본을 늘린 뒤 판단한다. 이번 승인 범위는 기존 결과 정리이므로 운영 모드 전환이나 추가 성능용 실제 AI 호출은 실행하지 않았다.
