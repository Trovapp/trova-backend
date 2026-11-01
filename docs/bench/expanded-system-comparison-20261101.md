# REST·Spring AI 2 및 운영 async·Kafka 확대 비교 (#288)

> 공유용 문서: [기존 방식·Spring AI 2·Kafka 비교 PDF (6쪽)](reports/trova-performance-comparison-20261101.pdf). [생성·검수 안내](reports/README.md).

표본을 늘린 이번 실험에서도 전체 서비스의 속도 개선을 입증하지는 못했다. Java의 짧은 JSON 요청은 Spring AI 2 중앙값이 작았지만 짝지은 재표집 범위에 0이 포함됐다. 운영에서는 Kafka 전달 지연이 추가됐으며, 전체 분석 시간에는 LLM 응답과 검증 분기 차이가 섞였다.

과거 표본은 합치지 않았다. Java는 각 20회, 운영은 영상 3종을 경로별 2회씩 측정했다. Java 클라이언트와 Python 영상 추출은 별도이며, 최초 REST 서버·Spring AI 서버·Kafka 서버 세 빌드를 같은 조건으로 비교한 실험은 아니다.

## 조건과 사용량

- 기준 소스 `40fe231`은 배포 소스 `bd196e1`과 런타임이 같다. 운영 JAR SHA-256 `f820ab28d6ba3e66fa7b8b2abbf9fb26f3a899d4409eb4390911383b5ed0e910`과 Python 진입 스크립트 7개의 해시를 전후 대조했다.
- Java: 로컬 Mac·Java 21, 같은 Gemini 모델 식별자·temperature 0.1·짧은 JSON 요청. 각 워밍업 1회와 측정 20회, 라운드마다 먼저 부르는 클라이언트를 교대했다. 호출 사이 8초를 두고 앱/SDK/라이브러리 재시도를 껐다. REST 연결 3초·읽기 20초와 Spring AI 전체 20초 제한의 차이는 유지했다. 서버와 DB는 기동하지 않았다.
- 운영: Oracle E2.1.Micro + Aiven, 테스트 계정 1, 직렬 낮은 부하, `reanalyze=true`, 자동 초안 설정 유지. 영상 `H1bCCd1vDCA`(A)·`YS4z0NqKkyw`(B)·`8vmpPuFwQ94`(C)를 각 경로에서 2회씩 측정했다. 순서는 K1(워밍업+ABC) → async(워밍업+CBA+ABC) → K2(복구·워밍업+CBA)다. 총 15건 중 워밍업 3건을 제외했다.
- HTTP는 Mac 왕복, 큐 대기는 앱 로그, 완료는 DB `analysis_finished_at`으로 측정했다. 분석 시간은 생성→완료에서 큐 대기를 뺀 값이다. 자동 초안 완료 시간은 포함하지 않는다. 실제 로그의 경로와 기대 경로도 대조했다.
- RSS는 진행 분석·초안·재구성이 0건으로 조회된 뒤 MainPID의 `/proc/VmRSS`를 읽은 값이다. 후속 비동기 작업·GC·캐시·SDK 실행 이력·재시작을 통제한 메모리 실험은 아니다.
- 사용량 사전 조회는 개발 환경 로그 0행, 운영 12행(시도 정보 9행)이었다. 같은 Gemini 키를 사용한다. 직접 호출 등 미기록 사용량과 공급자의 잔여 할당량은 알 수 없어, 이 숫자를 실제 잔여 한도로 제시하지 않는다. 추가 작업 예산 200을 두었으며 이는 공급자 무료 할당량을 뜻하지 않는다.
- 공유 한도 20건은 바꾸지 않았다. 시작 시 2건 사용, 실험 15건으로 3건 여유를 남겼다. 유료 등급·다른 API·영구 환경·스키마·JAR은 변경하지 않았다.

## Java 클라이언트 결과

| 지표 | REST | Spring AI 2 |
|---|---:|---:|
| 측정 표본 | 20 | 20 |
| 중앙값 | 913.17ms | 719.33ms |
| 평균 | 2,798.47ms | 2,423.58ms |
| 최소 | 602.43ms | 544.86ms |
| 1사분위 | 759.61ms | 633.10ms |
| 3사분위 | 1,104.08ms | 1,031.03ms |
| 최대 | 15,385.11ms | 15,154.69ms |

워밍업 포함 42/42회, 제외 후 각 20/20회 성공했다. 측정 20회의 토큰 합은 양쪽 404였다. 평균이 중앙값보다 큰 것은 두 방식 모두 10초대 응답을 포함하기 때문이다.

중앙값끼리 차이는 Spring AI가 193.84ms 작다. 같은 라운드의 **Spring AI−REST 차이 중앙값**은 −136.10ms이며, 짝을 유지한 bootstrap(seed 288·5,000회)의 중앙 95% 범위는 **−311.32~+12.64ms**다. 두 통계는 서로 다르다. 범위가 0을 포함하며 시간 상관·전송 클라이언트/연결 재사용·공급자 백엔드 버전을 분리하지 않았으므로, 일반적인 속도 우위나 전체 서비스 개선률을 주장하지 않는다. 이 범위는 관측 표본 조건부 설명용이다.

## 운영 큐 결과

| 지표(워밍업 제외·각 n=6) | 정상 async | Aiven Kafka |
|---|---:|---:|
| 작업 생성→분석 시작 중앙값 | 234.50ms | 440.00ms |
| 공유 HTTP 응답 중앙값 | 388.14ms | 636.44ms |
| 분석 시작→저장 완료 중앙값 | 11.689초 | 12.060초 |
| 작업 생성→저장 완료 중앙값 | 11.919초 | 12.761초 |
| 관찰 시점 Java RSS 중앙값 | 320.383MiB | 300.594MiB |

- 큐 대기의 중앙값끼리 차이는 Kafka가 205.5ms 크다. 영상/반복을 맞춘 6개 짝의 차이 중앙값은 177.5ms(관측 범위 81~1,309ms)였다. 영상 단위로 두 반복을 함께 복원추출한 27개 조합의 중앙 95% 범위는 128.03~833.80ms다. 영상이 3종뿐이므로 일반 모집단 신뢰구간이나 인과적 유의성으로 해석하지 않는다. 해당 낮은 부하에서 전달 비용이 추가된 관측이다.
- 생성→완료 중앙값 차이는 Kafka가 약 0.84초 크다. 분석 구간의 LLM 응답과 동적 검증 분기도 달라, 이를 큐만의 전체 시간 효과로 해석할 수 없다.
- 측정 6건의 Gemini 논리 로그는 async 20행/Kafka 21행, 알려진 시도 합도 20/21, 대기 합은 각각 0이었다. collect/filter/select는 각 6행으로 같지만 verify는 2/3행이었다. 같은 영상이어도 후보와 추가 검증 실행이 완전히 같지 않았다는 근거다. 이 측정 대상 행에서는 시도/대기 누락이 없었다.
- RSS는 이번에는 Kafka 쪽이 작았으나 고정 메모리 절감으로 해석하지 않는다. 과거 소표본은 반대 방향이었다. 프로세스·GC·캐시·자동 초안 중복 억제·SDK 실행 이력 등을 통제하지 않았다.
- 전체 분석은 15/15건 성공했다(측정 12+워밍업 3). 이 결과로 실제 트래픽 실패율·처리량·운영 p90을 일반화하지 않는다. Aiven 장애 주입이나 다중 JVM 동시 소비도 시험하지 않았다.

단계별 지연 분포와 영상별·짝별 차이는 [분석 JSON](results/expanded-system-analysis-20261101.json)에 있다. Python 영상 추출은 Java AI 클라이언트 비교와 별개다. 응답 정확도·장소 추출 정답 평가는 이번 범위가 아니다.

## 복구와 비용 기록

- 소유한 `/run` 파일로 `APP_QUEUE_TYPE`만 임시 변경했다. async 기동 82.395초, 원래 Kafka 복구 86.059초는 각 1회이며 모드별 기동 성능 통계가 아니다. 복구 후 Kafka 블록도 성공했다.
- 최종 active·health UP·Kafka·빈 DropInPaths, JAR/Python 7개 해시 동일, 실제 WARN/ERROR 레벨 0을 확인했다. 이번 작업 폴더와 임시 설정도 잔여 0이다.
- 이번 작업 108~122와 사용자 1로 식별한 장소 114개·자동 초안 1개만 비공개 0600 백업 후 삭제했다. 잔여 각각 0, 완료 job/API 로그는 유지했다. 최종 진행 분석·초안·재구성도 각각 0이다.
- 테스트 계정은 오늘 **17/20건 사용, 3건 남음**이다. 완료 job도 공유 한도에 포함된다. 한도를 돌려놓기 위해 완료 job을 삭제하지 않았다.
- 로컬 42회는 파일에 개별 기록했으며 DB 로그에 자동 포함되지 않는다. 운영 구간 Gemini 51행 중 50행은 알려진 시도 합 50·대기 0, 자동 초안 1행은 시도/대기 누락이다. 카카오 119로그 행도 있다. 로그 행은 실제 wire 요청 수와 항상 같지 않고, 누락을 0으로 처리하지 않는다. 공급자 잔여 할당량·청구 대시보드는 확인하지 않았으며 유료 전환은 없었다.
- 잠자기 복구는 계속 보류했다. 8080·개발 서버·QA 시뮬레이터·앱 OTA는 조작하지 않았다.

## 검증과 재현

전체 `./gradlew build -q`는 1,010개 중 1,007개 통과·실패/오류 0·기존 opt-in 3개 skip이다. 실제 실행한 Java 하네스도 컴파일/측정을 통과했다. 운영 복구 도구의 오프라인 검사 5개도 통과했다. 소유하지 않은 설정 보존, 전환 실패 후 복구, 이미 복구된 경우 재시작 생략, 복구 실패 시에도 정리 시도를 확인한다.

운영 도구는 명시적 `--run-production`과 환경 파일을 요구하고 import만으로 실행되지 않는다. journal은 서버가 받는 UTC 형식으로 조회한다. 원문·URL·오류 내용·키/토큰은 결과에 저장하지 않는다. 영상 ID는 공개 테스트 입력이다.

```bash
# 새 출력 이름을 사용한다. 키는 비공개 환경에서 TROVA_BENCH_KEY로 주입한다.
./gradlew -I docs/bench/scripts/client-latency.init.gradle clientLatencyBench \
  -PbenchSamples=20 -PbenchPauseMillis=8000 -PbenchOutput=<새-client.json> -q

# 기존 operator venv(psycopg) 사용. 이 명령은 실제 운영 모드를 전환한다.
python docs/bench/scripts/production_queue_bench.py --run-production \
  --env-file <확인한-운영.env> --host <SSH호스트> --ssh-key <키-경로> \
  --base-url <운영-URL> --backup-dir <비공개-백업-폴더> \
  --expected-jar-sha256 <배포-JAR해시> --source-commit <기준-소스커밋> \
  --local-client-result <새-client.json> --output <새-production.json>

# 네트워크 없이 신규 표본만 재계산한다.
python3 docs/bench/scripts/analyze_expanded_comparison.py \
  --client docs/bench/results/client-latency-expanded-20261101.json \
  --production docs/bench/results/production-queue-expanded-20261101.json \
  --output docs/bench/results/expanded-system-analysis-20261101.json
python docs/bench/scripts/test_production_queue_bench.py -v
```

원본: [Java 42호출](results/client-latency-expanded-20261101.json), [운영 15건·전환·정리](results/production-queue-expanded-20261101.json), [사용량 사전 조회](results/expanded-quota-preflight-20261101.json), [소스/도구/클래스 해시](results/expanded-run-manifest-20261101.json), [독립 최종 점검](results/expanded-production-postflight-20261101.json). [실행 전 계획](../superpowers/specs/2026-11-01-expanded-system-comparison.md)과 [도입 순서·과거 근거](system-comparison-summary-20261101.md)를 함께 참고한다.
