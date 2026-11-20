# 밤사이 중단 경보 전달 경로 점검

2026-11-20 KST · 저장소 기준 `16e3f2e` · **운영/Cloud 변경 없음, 제안은 미반영**

## 결론과 확인 범위

**서버가 멈춘 것을 외부에서 감지하는 구성은 있으나, 사용자의 휴대폰이 밤에도 울린다고 확정할 수 없다.** 저장소에는 Slack 연락처 하나만 있고, Synthetic 실제 설정·야간 발화 이력·전송 결과·휴대폰 수신 증빙이 없다. 11-13 밤 9시간 장애의 미인지 원인을 “경보 없음”이나 “사용자가 못 봄”으로 확정할 근거도 없다. 확정되는 공백은 **감지부터 사람의 확인까지 전달 검증과 증거가 연결돼 있지 않다**는 것이다.

이번 점검은 저장소 문서/정의와 서비스 공식 공개 문서만 읽었다. Cloud 로그인·API 조회, Slack 조회/발송, 운영 서버·DB 접근, 새 서비스 가입은 하지 않았다. `AGENTS.md`는 작업 폴더 및 확인한 상위 경로에 없었고 루트 `CLAUDE.md`를 따랐다. 최신화는 git 쓰기 거부 뒤 매니저가 수행했다.

근거:

- [11-13 대응 기록 4·5장](../2026-11-13-prod-response/record.html): 야간 9시간 장애와 오후 23분 장애는 별개다.
- [11-18 메모리 경보 검토 및 반영 결과](../2026-11-18-memory-pressure-alert-review.md): 오후 경보 전이, 11-18 제한된 반영 확인, Slack 전달 미검증.
- [ADR 0039](../../adr/0039-direct-otlp-metrics-and-external-health-check.md), [ADR 0041](../../adr/0041-grafana-cloud-alert-rules-and-routing.md), [Cloud 저장본 안내](../../../monitoring/grafana-cloud/README.md).
- [지표 끊김 runbook](../../runbooks/cloud-scrape-unavailable.md), [메모리 runbook](../../runbooks/memory-pressure.md), [작업 기록](../../experience-notes.md)의 11-13 항목.

## 정의된 감지와 전달

```text
외부 Seoul probe → 공개 HTTPS /actuator/health → Synthetic 관리 경보 ─┐
앱 → OTLP → Grafana Cloud Prometheus → Grafana 관리 경보 ────────────┤
                              실제 라벨/정책/사일런스 확인 필요 ←──┘
→ trova-slack-alerts → Slack 채널 → 사용자 알림 설정 → 휴대폰 OS → 사람이 확인
```

| 감지 | 저장소에서 확인되는 조건 | 다운/무응답 때 한계 |
|---|---|---|
| 외부 `trova-health` | ADR 0039: Seoul 1곳, 60초마다 `/actuator/health`, 5초 제한, 2xx와 본문 `"status":"UP"`, 5분 중 3회 실패 → Slack | 앱/VM 밖에서 HTTPS 무응답도 감지. 실제 체크 ID, 활성 상태, 실패 규칙 식·평가 간격·라벨·라우팅은 export 미보관. 정확한 발송 지연은 계산 불가 |
| `TrovaCloudScrapeUnavailable` | [정의](../../../monitoring/grafana-cloud/alerting/rules-trova-cloud-availability.json): `absent_over_time(process_cpu_usage{job="trova-prod"}[5m]) or vector(0)` > 0.5, `for=2m`, 평가 1분, warning | 마지막 표본 뒤 대략 7분+평가 위상+정책 대기. 서버 중단과 전송 장애를 구분 못 함. 조회 오류에는 `KeepLast`이므로 정상에서 신규 발화 보장 안 됨 |
| `HighHttp5xxErrorRate` | [HTTP 정의](../../../monitoring/grafana-cloud/alerting/rules-trova-http.json): 최근 5분 요청 ≥20, 5xx >10%, 2분, critical | 무응답/저트래픽에서 완료 요청이 없으면 다운 감지 불가 |
| `HighHttpLatencyP95` | 같은 파일: 5분 요청 ≥20, p95 >2초, 5분, warning; actuator·shares 제외 | 요청 완료와 지표 수신에 의존. 밤의 낮은 요청 수 및 완전 중단을 덮지 못함 |
| `HighMemoryPressure` | [host 정의](../../../monitoring/grafana-cloud/alerting/rules-trova-host.json): PSI full 5분 >20%, 2분; 계산 공백은 최근 5분 마지막 계산값으로 보완 | 11-18 개선은 짧은 계산 공백용. 장기 공백은 OK로 돌아가므로 다운 경보 대체 불가 |
| ERROR 로그·Kafka | [로그](../../../monitoring/grafana-cloud/alerting/rules-trova-cloud-logs.json): ERROR 10분 증가 >2.5, 2분; [Kafka](../../../monitoring/grafana-cloud/alerting/rules-trova-kafka.json): lag >3, 15분 / dead letter 15분 증가 >0, 즉시; 모두 warning | 원인 보조 신호. 앱이 지표를 못 보내는 완전 중단에는 의존 불가 |

위 Grafana 관리 규칙은 저장본상 모두 활성이고 평가 1분, `noDataState=OK`, `execErrState=KeepLast`다. `absent_over_time`은 조회가 성공하면 부재를 숫자로 나타내므로 단순 NoData→OK와 구별해야 한다. 한편 일부 표본만 간헐적으로 도착하면 5분 부재 조건이 충족되지 않을 수 있다. 로컬 [alerts.yml](../../../monitoring/alerts.yml)의 `up`/`absent(up)` 1분 경보는 Docker/SSH 터널용이며 Cloud 운영 다운 경보의 증거가 아니다.

### 연락처·정책·무음 시간

| 항목 | 저장소 사실 | 확인되지 않은 부분 |
|---|---|---|
| 연락처 | [contact-points.json](../../../monitoring/grafana-cloud/alerting/contact-points.json): `trova-slack-alerts`, Slack 수신기 1개, `disableResolveMessage=false` | 실제 webhook 유효성·워크스페이스·채널·담당자·앱 설치/채널 가입 여부. URL은 의도적으로 가려져 있음 |
| 정책 | [notification-policies.json](../../../monitoring/grafana-cloud/alerting/notification-policies.json): `grafana_folder, alertname` 묶음. critical 대기 30초/그룹 갱신 5분/반복 1시간; warning 1분/10분/4시간; 기본 30초/5분/4시간. 모두 같은 Slack | Synthetic의 실제 라벨과 Alertmanager/정책이 이 트리에 연결되는지. 라벨 미일치라면 기본 경로 가능. 4시간 반복은 읽음 확인/에스컬레이션 아님 |
| 정기 무음 | 정책 JSON에 `mute_time_intervals`·`active_time_intervals` 참조 없음 | Cloud 현재 무음 설정 전체·개별 silence·Slack 알림 스케줄·OS 수면/집중 모드는 미보관. “레포에 없음”은 “밤에도 무음 없음”이 아님 |
| 일시 silence | runbook: 배포/재시작 PSI, 부하 측정 p95·Synthetic·PSI에 유한 종료 silence. 11-18 PSI silence 종료 입력 12:20:49 KST, 만료 후 확인 안 함 | 11-13 밤 어떤 silence가 활성인지 증거 없음. 11-18 PSI 전용 silence로 11-13 밤을 설명할 수 없음 |
| 메시지 | [템플릿](../../../monitoring/grafana-cloud/alerting/trova.slack.tmpl): FIRING/RESOLVED, KST, 링크. 사용자 멘션·채널 멘션 없음 | Slack이 멘션만 알리도록 설정됐다면 메시지가 쌓여도 푸시되지 않을 수 있음 |

Slack은 알림 대상·스케줄과 PC 활동 여부에 따른 모바일 전달 시점을 따로 설정한다. 따라서 **Slack 채널 도착 ≠ 휴대폰 알림 ≠ 사람의 확인**이다. [Slack 공식 알림 설정](https://slack.com/help/articles/201355156-Configure-your-Slack-notifications), [진단 절차](https://slack.com/help/articles/360001559367-Troubleshoot-Slack-notifications/).

## 11-13 밤: 사실과 원인 가설

| KST | 기록으로 확인된 사실 |
|---|---|
| 00:56:27 | AppCDS 학습을 위해 앱을 명시적으로 중지 |
| 00:56~08:28 | 학습 Java의 힙 밖 메모리 증가·스왑 압박, SSH/HTTPS 무응답, Grafana 지표 공백 |
| 08:28:08 | 커널이 학습 Java 종료; 직접 중지한 앱은 자동 재시작되지 않음 |
| 09:52:06~09:54:13 | 수동 시작 후 정상 응답 |

직접적인 서비스 장애 원인은 위 운영 시험과 복구 단계 미실행이다. **미통지/미인지 원인은 기록에 명시적으로 “확인하지 못함”**으로 남아 있다. 11-18 문서의 `ProbeFailedExecutionsTooHigh` 15:40:50 발화, `SyntheticCheckFailedExecutionsBreach` 15:42:40 발화는 오후 r28 증거다. 이를 야간 발화 증거로 사용하지 않는다.

가설은 조사 순서이며 가능성의 통계적 순위가 아니다:

1. **감지 자체 미작동:** Synthetic 비활성/수집 또는 평가 오류, 잘못된 조건·대상, 지표 조회 오류. 당시 체크 결과·평가 이력으로 판별한다. 9시간은 기존 부재 조건보다 훨씬 길어 단순 `for=2m`만으로 설명되지는 않는다.
2. **발화했으나 전송 억제/실패:** 작업 중 silence, 다른 Alertmanager/잘못된 라우팅, webhook 실패. 당시 alert instance 라벨·silence 시간·notification 결과로 판별한다.
3. **Slack에는 왔으나 휴대폰/사람까지 전달 실패:** 멘션 전용 설정, 채널 무음, 야간 스케줄, PC 활동에 따른 모바일 지연, OS 알림 권한·집중 모드, 오프라인. Slack 메시지 시각과 휴대폰 진단으로 판별한다. 현재 설정만으로 과거 상태를 확정하지 않는다.

조사 시간 범위는 **2026-11-12 15:30~2026-11-13 01:30 UTC (=11-13 00:30~10:30 KST)**다. 매니저는 보존 기간이 지나기 전에 Synthetic 실패 시계열·경보 전이·알림 오류와 Slack 메시지 링크/시각을 확보한다. 보존되지 않은 항목은 “확인 불가”로 남기고 추정으로 채우지 않는다. webhook·토큰·개인 연락처는 기록하지 않는다.

## 끊길 수 있는 지점과 개선안

| 지점 | 현재 위험 | 무료 범위 개선안 |
|---|---|---|
| 외부 probe/체크 | Seoul 한 곳, 설정 사본 없음 | 먼저 기존 Synthetic 활성·대상·실패 규칙을 검증하고 민감값 제거한 사본 확보. 프로브 추가보다 독립 보조 서비스 검토 |
| 앱→Cloud | 앱 중단 시 지표 동시 소실 | 기존 부재 규칙 유지, Synthetic을 1차 다운 신호로 사용. PSI NoData를 무조건 Alerting으로 바꾸지 않음 |
| Cloud 평가/라우팅 | KeepLast, Synthetic 정책 연결 미확인 | 실제 Synthetic 라벨로 정책 preview 및 발화/해제 시험. Grafana 자체 장애는 같은 플랫폼 경보만으로 보장 못 함 |
| silence/반복 | 야간 전체 mute 가능성, warning 4시간 간격 | 가용성 경보를 밤에도 받는지 확인. 작업 silence는 정확한 경보 범위·종료·담당자 기록, 종료 뒤 헬스/알림 확인. 무분별한 전체 무음 금지 |
| Slack webhook/채널 | 단일 연락처, 다른 매체 없음 | 기존 채널 실제 전달 검증. 전용 경보 채널을 모든 새 메시지 알림으로 설정하고 개인 야간 스케줄 점검 |
| 모바일/사람 | OS 무음·PC 활동·미확인에 대한 자동 후속 없음 | 잠금 화면·PC 활성/비활성 각각 시험, 사용자 확인 시각 기록. 반복은 확인 보장이 아니므로 대응 담당자·수동 후속 경로 명시 |
| 공통 장애 | Cloud+Slack 경로 하나 | 독립 외부 무료 HTTP/keyword 감시 → 별도 이메일/앱 알림 경로를 추가 검토. 같은 휴대폰/OS가 공통 장애점인 것은 남음 |

### 외부 헬스 체크 후보와 비용

기존 Grafana 체크 1개×1개 probe×1분은 31일에 **44,640회**다. Grafana Free 공개 가격은 월 API 테스트 100,000회를 포함하지만 다른 체크의 합계와 로그/지표 사용량도 따로 확인해야 한다. 기존 1개 유지가 우선이며 유료 전환/초과 청구 설정은 하지 않는다. [Grafana 가격](https://grafana.com/pricing/), [공식 사용량 계산](https://grafana.com/docs/grafana-cloud/platform/pricing-and-usage/synthetic-monitoring/).

독립 보조 후보는 **UptimeRobot Free의 HTTP/keyword 체크 1개, 5분 주기**다. 공식 도움말은 상업적 사용 가능·50 monitors·카드 불필요를 명시한다. 같은 공개 health URL의 HTTPS 응답과 UP 본문을 검사하고, 무료 이메일 및 모바일 알림 지원 범위를 계정 화면에서 확인해 기존 Slack과 다른 전달 경로를 선택한다. 무료 플랜에 없는 기능은 활성화하지 않는다. 5분 간격이므로 첫 검사까지 최대 약 5분에 재확인·발송 지연이 더해질 수 있고, 1분 감시나 음성/SMS를 무료라고 가정하지 않는다. **제안만 했으며 가입·체크 생성·발송 없음.** [공식 무료 사용 범위](https://help.uptimerobot.com/en/articles/11604710-who-should-use-uptimerobot-s-free-plan), [현재 요금표](https://uptimerobot.com/pricing/).

## 매니저의 확인·반영 절차

현재 정의를 바꿀 근거가 부족하므로 **Cloud 저장본 JSON은 수정하지 않는다.** ADR 0041의 심각도/반복 정책도 유지한다. Synthetic의 critical 승격·짧은 반복이나 별도 연락처는 실제 라벨/전달 결과 확인 후 사용자 결정과 ADR 갱신이 필요한 후속 제안이다. 앱 중단 없이 먼저 경로를 시험한다.

1. **읽기/백업:** Grafana UI에서 기존 Synthetic 체크·관리 경보, 사용 중인 Alertmanager, 라벨/직접 연락처/정책, active silence·mute 일정을 확인한다. HTTP 대상·본문 조건·timeout·주기·평가 조건을 기록하고 모든 관련 설정을 비밀값 제거해 보관한다. `contact-points.json`의 `[REDACTED]`를 실제 Cloud로 PUT하지 않는다.
2. **과거 원인 분리:** 위 UTC 범위에서 실패 표본 → Pending/Alerting → notification 성공/실패 → Slack 도착 → 사용자 인지 순서로 시각을 대조한다. 누락 지점을 명시한다. Slack 기록 부재만으로 발화 부재를 결론 내리지 않는다.
3. **연락처 시험:** 매니저가 수신자에게 테스트 시간을 알리고 기존 연락처의 테스트 기능을 실행한다. 채널과 휴대폰 잠금 화면 수신을 확인한다. 이 시험은 실제 규칙·정책 매칭까지 검증하지는 않는다.
4. **정책 포함 시험:** 매니저가 운영 앱을 멈추지 않는 임시 시험 규칙/대상을 준비한다. 실제 가용성 경보와 동일한 라우팅 관련 라벨을 사용하되 테스트 식별자를 붙이고, 실제 규칙과 섞이지 않는 그룹으로 FIRING→RESOLVED를 시험한다. Synthetic 경보와 Grafana 관리 경보의 평가/Alertmanager가 다르면 각각 시험한다. `test=true` 전용 우회 정책만 검증하고 운영 경로 통과로 보고하지 않는다. 시험 전후 사본 비교와 임시 자원 삭제까지 담당한다.
5. **야간 수신 확인:** 사용자와 정한 시간에 휴대폰 잠금·PC 활성/비활성·실제 야간 알림 스케줄 조건으로 점검한다. 채널 모든 메시지 알림, Slack 스케줄, OS 알림/집중 모드 예외를 사용자가 확인한다. 주간 테스트 성공을 야간 성공으로 대체하지 않는다.
6. **필요한 최소 변경:** 발견된 비활성·잘못된 대상/라우팅/만료 연락처를 기존 UID 중심으로 수정하고 재시험한다. 기존 정책을 통째로 덮어쓰지 않는다. 승인된 독립 보조 체크는 무료 플랜/사용량을 확인한 뒤 1개만 추가한다. 실패하면 백업 설정 복원·임시 체크 제거 후 기존 경로 재시험한다.
7. **저장본 동기화:** 변경 후 Cloud가 기준인 현행 ADR에 따라 export를 저장한다. Synthetic 사본에는 체크 ID/대상/주기/실패 조건/라벨/연결 Alertmanager·연락처/취득 시각을 포함한다. 실제 값 미확인 상태의 임의 복구 JSON은 만들지 않는다.

### 인수 조건과 증거 양식

아래 표는 **미실행** 상태다. 과거 원인 불명은 남더라도 이 시험을 통과해야 “밤에도 사용자 휴대폰까지 전달됨을 시험했다”고 말할 수 있다. 유료 호출·SMS/전화 구매·운영 장애 유발은 하지 않는다.

| 시험 | 실패 감지/발화 KST | 정책/억제 여부 | Slack 도착 KST·링크 | 휴대폰 수신·사용자 확인 KST | 해제/정리 |
|---|---|---|---|---|---|
| 기존 Slack 연락처 테스트 | 미실행 | 연락처만 시험 | 미확인 | 미확인 | 미확인 |
| 실제 가용성 라우팅 FIRING→RESOLVED | 미실행 | 미확인 | 미확인 | 미확인 | 미확인 |
| 야간·잠금 화면·PC 활성/비활성 | 미실행 | 미확인 | 미확인 | 미확인 | 미확인 |
| 독립 보조 경로(채택 시) | 미실행 | 별도 서비스 | 해당 없음 | 미확인 | 미확인 |

## 저장소 변경·검증·인계

- 추가: 이 보고서. 실제 Cloud 사본·운영 서버·DB는 변경하지 않았다.
- git fetch는 `FETCH_HEAD: Operation not permitted`로 실패했다. 매니저가 최신 main `16e3f2e`로 맞췄으며 커밋·push·PR은 매니저가 담당하기로 했다. PR 번호는 아직 없다.
- 검증: 로컬 링크 검사·비밀값 패턴 검사·`git diff --check` 통과. `./gradlew build`는 2분 55초 후 **1,304개 중 34 실패·6 skipped**로 실패했다. 변경하지 않은 pipeline 테스트 5개 클래스에서 `Operation not permitted (sysctl failed)` 및 자식 프로세스 관측 실패가 발생했다. 빌드 통과로 보고하지 않으며 상세 결과는 로컬 `build/reports/tests/test/index.html`이다.
- 커밋 메시지 초안: `docs: 야간 장애 경보 전달 경로와 수신 검증 절차 정리`
- 후속 우선순위: (1) 기존 경로의 야간 휴대폰 수신 시험, (2) 11-13 남은 이력 보존/대조, (3) 독립 무료 보조 체크 채택 검토.
