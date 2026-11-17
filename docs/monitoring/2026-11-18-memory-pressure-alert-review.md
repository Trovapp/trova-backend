# r28 메모리 압박 경보 지연 검토

날짜: 2026-11-18 · #428 · **레포 변경 제안만, Cloud 미반영**.

## 근거와 확인 범위

- PR #433 브랜치 `origin/test/#428-memory-pressure-alert`, 확인한 커밋 `5cb8c66305a0df26f8f7037958ce02ff9109c5fb`의 [r28 alerts.md](https://github.com/Trovapp/trova-backend/blob/5cb8c66305a0df26f8f7037958ce02ff9109c5fb/docs/monitoring/2026-11-08-prod-analysis/r28-reels-browse100-144m-alert/alerts.md): Grafana 규칙 이력 API 및 30초 상태 관찰을 정리한 전이 표.
- 같은 커밋의 [r28 README](https://github.com/Trovapp/trova-backend/blob/5cb8c66305a0df26f8f7037958ce02ff9109c5fb/docs/monitoring/2026-11-08-prod-analysis/r28-reels-browse100-144m-alert/README.md), `screenshots/1-psi-5m.png`, `samples.jsonl`(서버 별도 관찰 58개, 앱 OTLP 표본이 아님).
- [장애 기록](2026-11-13-prod-response/record.html) 5·7장: 15:41~16:04 외부 응답 불가, 재시작 UP까지 133초 등. 과제에서 제공한 배포 범위는 133~146초(이 기록 표에서는 146초 자체를 확인하지 못함).
- 로컬 저장본 `monitoring/grafana-cloud/alerting/`과 ADR 0039~0042. 운영·Cloud에 조회하거나 부하를 다시 걸지 않았다.

## 실제 경보 타임라인 (2026-11-13 KST)

| 경보 | Pending | Alerting / 해제 |
|---|---|---|
| 외부 ProbeFailedExecutionsTooHigh | 기록 없음 | 15:40:50 발화 → 15:41:50 해제 → 15:43:50 재발화 → 16:03:50 해제 |
| SyntheticCheckFailedExecutionsBreach | 기록 없음 | 15:42:40 발화, 15:45:40 발화 전이도 기록됨 → 16:05:40 Normal/NoData. 동일 인스턴스의 연속 전이라고 단정하지 않음 |
| HighHttpLatencyP95 | 15:39:30 | 15:44:30 발화 → 16:01:30 해제 |
| TrovaApplicationErrorLogs | 15:45:20 | 15:47:20 발화 → 16:06:20 해제 |
| HighMemoryPressure | 15:49:10, 15:52:10, 15:55:10, 16:00:10 | 15:51:10·15:54:10 Normal(공백), 15:57:10 첫 발화 → 15:59:10 Normal(공백) → 16:02:10 재발화 → 16:04:10 해제 |
| TrovaKafkaConsumerLag | 15:50:10 | 해당 표에 발화/해제 기록 없음 |
| TrovaKafkaDeadLetter | 없음(즉시 조건) | 15:52:10 발화 → 16:07:10 해제 |
| HighHttp5xxErrorRate | 15:53:30 | 15:55:30 발화 → 15:59:30 해제 |
| TrovaCloudScrapeUnavailable | 기록 없음 | 전이 표에 없음. 매 5분 창의 표본 수·정확한 정상 상태까지 증명하는 자료는 아님 |

PSI **평가 결과** 공백은 15:51:10·15:54:10·15:59:10 전이에서 확인된다. 다음 Pending은 각각 15:52:10·15:55:10·16:00:10이다. 이 구간을 **원시 OTLP 전송이 중단된 시작·끝 시각**으로 쓰면 안 된다. 5분 rate 창과 평가 주기·MissingSeries 처리가 있으며, 원시 OTLP 시계열과 `grafana_state_reason` 전량은 저장돼 있지 않아 표본 단위 단절 시각과 NoData/MissingSeries 구분은 확정할 수 없다.

원문은 "세 번 리셋 뒤 첫 발화"라고 서술하지만 표 기준으로는 **첫 발화 전 Pending 리셋은 2번**, 첫 발화 뒤 해제가 1번이다. 최초 Pending→첫 발화는 8분, 외부 응답 불가 시작→첫 발화는 약 16분 10초, 외부 경보→PSI 첫 발화는 16분 20초다. 외부 헬스 체크가 반쯤 멈춘 상태도 이미 잡았으므로 "기존 다운 경보가 r28을 놓쳤다"는 전제는 기록과 다르다. 규칙 발화와 Slack 도착은 구분한다(원문은 Slack warning을 기록했지만 도착 초 단위 시각은 없음).

## 방법 비교 (추가 비용 모두 0원)

아래 시간은 기록된 평가 전이를 바탕으로 한 **반사실 추정**이다. 원시 시계열 전체 재생이나 Cloud 실행 결과가 아니다.

| 방법 | r28 예상 및 기존 PSI 첫 발화 대비 | 배포 133~146초·오탐·사일런스 | 비용/판단 |
|---|---|---|---|
| A. PSI `noDataState=Alerting`(오류도 Alerting 또는 KeepLast) | 15:49:10 Pending 뒤 공백을 Alerting 조건으로 취급하면 15:51:10 부근, 약 6분 앞당김. MissingSeries라면 같은 효과를 보장 못 함 | 정상 재시작의 지표 공백도 PSI 장애로 오인. 2분을 넘는 재시작은 사일런스 필수. 오류까지 Alerting이면 Grafana 조회 장애도 메모리 압박으로 오인 | 0원, 새 수집 없음. 원인 구분·MissingSeries 불확실성 때문에 기각 |
| **B. PSI 계산값이 없을 때만 최근 5분 마지막 유효 계산값으로 보완(추천)** | 15:49:10의 >20% 값으로 15:51:10 공백을 이어 15:51:10 부근 발화, 약 **6분 빠름**(1분 subquery 위상·미보관 원시값에 따른 오차 가능). 장애 시작 이후 약 10분이라 외부 경보보다 여전히 늦음 | 새 낮은 값은 즉시 우선하므로 정상 회차의 짧은 >20%를 무조건 5분 늘리지 않음. 압박 직후 공백이면 회복 여부를 모르므로 오탐 가능. 계획 재시작 전·부하 측정 전 종료 시각 있는 사일런스 필수 | **0원**, 기존 시계열만 조회, 새 프로세스·외부 서비스·수집 횟수 없음. 조회량은 소폭 증가 |
| C. 기존 외부 Synthetic 실패 경보 유지(지연 조건 추가는 보류) | 이미 15:40:50 발화, PSI보다 16분 20초 빠름. 지연 전용 추가 경보의 개선 분수는 지연 원시 자료가 없어 계산 불가 | 1분 체크·5분 중 3회 실패. 146초 재시작은 위상에 따라 3번 실패할 수 있어 재시작은 항상 무음이라고 보장 못 함. 배포·부하 측정 사일런스 필요 | 기존 체크 그대로면 0원·추가 사용량 0. 새 체크/서비스 없이 기존 경보를 1차 대응 신호로 사용 |

대기 시간만 2분→0~1분이면 15:49:10~15:50:10(7~8분 빠름)이 가능하지만, 기존 정상 회차에서 20% 초과 최대 0.5분도 울릴 수 있다. 임계값/평균 창까지 낮추면 기존 정상·비정상 분리 근거를 다시 검증해야 하므로 이번에는 유지한다. `absent_over_time(...[5m])`는 이미 약 7분 지연 경보로 존재하고 간헐 수신마다 리셋되므로 r28의 짧은 평가 공백 대책은 아니다. 새 공백 경보를 짧게 추가해도 PSI와 배포 무음 문제를 함께 풀지 못한다.

## 추천 구현과 승인할 정확한 Cloud 변경

`trova-host` 그룹, UID **bg3yd8j9d5fr4a**, `HighMemoryPressure`만 변경한다. Cloud가 기준인 기존 정책의 예외로 **아직 반영하지 않은 후보**를 해당 JSON에 보관한다. 승인 후 매니저가 기존 Cloud 규칙과 차이를 확인하고 그 규칙 하나만 수정한다(폴더 UID가 비어 있는 export 파일 전체를 그대로 업로드하지 않는다).

1. A의 instant PromQL을 아래로 교체한다.

```promql
(100 * sum(rate(host_pressure_stall_seconds_total{job="trova-prod", resource="memory", type="full"}[5m])))
or
last_over_time((100 * sum(rate(host_pressure_stall_seconds_total{job="trova-prod", resource="memory", type="full"}[5m])))[5m:1m])
```

2. B(스왑 조회)를 제거한다. 보조 조회가 비어 보완된 A와 별개로 NoData를 만들 가능성을 없애고, 스왑 확인은 대시보드에서 한다. `context`, `description`, `summary`, `value_label`을 JSON 후보 그대로 바꿔 마지막 계산값일 수 있음을 표시한다.
3. **유지:** C→A >20, `for=2m`, 평가 1m, `noDataState=OK`, `execErrState=KeepLast`, UID·라벨·라우팅·나머지 규칙. 알림 warning의 group_wait=1m는 별도라 발화 뒤 통지에 시간이 더 든다.
4. 배포·재시작 **중지 전에** `alertname=HighMemoryPressure`, `service=trova-backend`, `environment=production`의 사일런스를 시작해 **정상 UP 및 지표 재수신 확인 뒤 최소 5분까지** 유지한다. 처음에는 예상 종료+10분처럼 유한 종료를 잡고, 지연 시 종료 전에 연장한다. 이미 높은 PSI가 남은 상태의 133~146초 재시작도 이것으로 알림을 막는다. 사일런스는 평가/Pending을 멈추지 않는다. 종료 전에 A가 최신값으로 20% 이하이고 Normal인지 확인하고, 압박 지속이면 사일런스를 자동 연장해 숨기지 말고 담당자에게 알린다.
5. 부하 측정은 기존 p95·Synthetic 사일런스에 PSI를 명시적으로 포함한다. 이번 작업에서는 사일런스 생성·측정·운영 변경을 하지 않았다.
6. 승인 후 Cloud 미리보기에서 A 공백 보완·임계값·Normal 복귀와 주석을 확인한다. 적용 뒤 원본 PSI, 보완값 A, 규칙 전이, Slack 수신 시각을 비교한다. 롤백은 A를 첫 줄의 원래 쿼리로 복원하고 B·주석을 변경 전 저장본으로 복원하는 것이다.

`or`는 현재값이 있으면 현재값을 택하고, `last_over_time`은 과거 최대가 아닌 마지막 값을 택한다. 따라서 실제 낮은 관측으로 회복하면 즉시 정상 판단할 수 있다. 이 동작의 근거는 [Prometheus 함수](https://prometheus.io/docs/prometheus/latest/querying/functions/)·[연산자](https://prometheus.io/docs/prometheus/latest/querying/operators/), NoData와 MissingSeries 차이는 [Grafana 공식 설명](https://grafana.com/docs/grafana/latest/alerting/guides/missing-data/)이다.

## 남은 위험과 검증 한계

- 보완은 **원시 표본 신선도 보장**이 아니다. 기존 rate의 5분 창 + 보완 5분으로 오래된 압박이 최대 약 10분 남을 수 있다. 샘플/평가 위상에 따라 더 짧다. 실제 회복과 공백에 따른 임시 유지가 구분되지 않아 마지막 계산값임을 알린다.
- 모든 표본이 처음부터 없거나 공백이 보완 범위를 넘으면 이 경보는 정상으로 돌아간다. 장기 끊김·외부 장애 경보가 여전히 필요하다. Grafana 조회 오류에는 보완식 자체도 실행 불가능하며 KeepLast가 곧 신규 발화를 보장하지 않는다.
- 단일 서버(job 단위 sum) 전제. 여러 인스턴스로 늘리면 보완과 라벨 정책을 다시 설계한다.
- 최초 >20% 이전 공백은 해결하지 않는다. r28을 장애 시작 전에 경고하게 만든다는 주장은 하지 않는다.
- 정상 >20% 뒤 곧바로 전송만 끊겨도 2분을 채워 울릴 수 있다. 배포 자동화가 사일런스를 만들지 않으므로 담당자가 반드시 수행해야 한다. 사일런스는 정해진 시간의 알림 공백도 만든다.
- Cloud 전이 엔진/Slack을 로컬 promtool로 검증할 수는 없다. 계산식과 정상값 우선·장기 공백·빈 시계열은 로컬 합성 데이터로 검증하고, 실제 r28 전이 해석과 구분한다.


## 로컬 검증 결과

- `python3 monitoring/grafana-cloud/validate_host_alert.py`: alerting JSON 전체 파싱, 후보 참조·임계값·UID·라벨·정책 검사 및 promtool 3.13.2로 실제 후보 PromQL 5개 합성 시나리오 통과. 압박 뒤 공백 유지/장기 공백 만료, 새 정상값 우선 및 정상값 뒤 재공백, 최초 무자료, 정상 재시작 상당 133·146초 공백을 검사했다. 높은 값 뒤 계획 재시작은 자동으로 구분할 수 없어 문서의 사일런스가 필요하다.
- `promtool test rules monitoring/alerts.test.yml`: 기존 로컬 경보 테스트 통과(Cloud와 별개).
- `./gradlew build -x test`: 성공. 앱 코드·설정 변경이 없으므로 과제의 허용에 따라 Java 테스트는 생략하고 규칙 전용 검증을 수행했다. 빌드에는 운영 환경 파일을 사용하지 않았다.
- `git diff --check`: 통과. 운영 접근·측정·Cloud API/UI 변경·push·PR 생성 없음.

변경 파일: `monitoring/grafana-cloud/alerting/rules-trova-host.json`, `monitoring/grafana-cloud/validate_host_alert.py`, `monitoring/grafana-cloud/README.md`, `docs/runbooks/memory-pressure.md`, `docs/adr/0046-memory-pressure-alert-gap-continuity.md`, `docs/adr/README.md`, 본 보고서. 기존 ADR 0041은 확정 정책을 유지하고 새 ADR을 승인 대기 초안으로 연결했다.
