# 밤사이 중단 경보 전달 경로 점검

2026-11-20 KST · 관련 이슈 #496 · 저장소 기준 `16e3f2e` · **운영/Cloud 변경 없음, 제안은 미반영**

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
4. **정책 포함 시험:** 매니저가 운영 앱을 멈추지 않는 임시 시험 규칙/대상을 준비한다. 저장본 정책의 분기는 `severity=critical`/`warning`이고, 묶음 키는 `grafana_folder, alertname`이다. 시험할 실제 경보의 severity와 나머지 라우팅 관련 라벨을 유지하고, 별도 alertname으로 그룹을 분리한다. `test=true`는 메시지의 테스트 표시용으로만 붙이며 **테스트 전용 우회 정책은 만들지 않고 기존 severity 경로로 FIRING→RESOLVED를 전달**한다. 실제 Cloud 정책에 추가 matcher가 있으면 먼저 같은 경로를 타는지 preview로 확인한다. Synthetic 경보와 Grafana 관리 경보의 평가/Alertmanager가 다르면 각각 시험한다. 시험 전후 사본 비교와 임시 자원 삭제까지 담당한다. 이 단계는 라우팅 시험이며 기존 감지 규칙 자체의 작동은 아래 실제 발화 시험으로 별도 확인한다.
5. **야간 수신 확인:** 사용자와 정한 시간에 휴대폰 잠금·PC 활성/비활성·실제 야간 알림 스케줄 조건으로 점검한다. 채널 모든 메시지 알림, Slack 스케줄, OS 알림/집중 모드 예외를 사용자가 확인한다. **평소 취침 위치·음량에서 무음 모드와 방해 금지/수면 모드를 각각, 그리고 실제 사용하는 조합으로 켠 상태에도 알림 소리가 나는지** 기록한다. 화면 표시·진동만으로 통과시키지 않는다. 해당 OS/Slack 조합에서 소리를 허용할 수 없다면 야간 깨움 기준은 실패로 남기고 사용자와 무료 대체 경로를 검토한다. 실제 수면 중 깨움 확인은 사전에 동의한 시간에 수행하며, 알림 소리로 깨어 직접 확인한 시각과 지연을 기록한다. 깨어 있는 상태의 소리 시험만 했다면 “실제 수면 중 깨움 미검증”으로 구분하고, 주간 테스트 성공을 야간 성공으로 대체하지 않는다.
6. **필요한 최소 변경:** 발견된 비활성·잘못된 대상/라우팅/만료 연락처를 기존 UID 중심으로 수정하고 재시험한다. 기존 정책을 통째로 덮어쓰지 않는다. **연락처 export의 webhook은 `[REDACTED]`이므로 그 백업만으로 원래 URL을 복원할 수 없다.** 기존 연락처를 바꾸기 전 담당자가 원래 URL을 보안 저장소/Slack 앱에서 복구할 수 있음을 확인하거나, 원래 연락처를 보존한 채 새 연락처로 시험한다. 원래 URL을 확보하지 못한 기존 연락처는 덮어쓰거나 삭제하지 않는다. URL은 파일·로그·보고서에 출력하지 않는다. 승인된 독립 보조 체크는 무료 플랜/사용량을 확인한 뒤 1개만 추가한다. 실패하면 백업 정책·규칙을 복원하고, 연락처는 확보한 원래 URL로 복원하거나 보존한 원래 연락처로 정책을 되돌린 뒤 임시 자원을 제거하고 기존 경로를 재시험한다.
7. **저장본 동기화:** 변경 후 Cloud가 기준인 현행 ADR에 따라 export를 저장한다. Synthetic 사본은 `monitoring/grafana-cloud/synthetic/trova-health.json`에 저장하고 체크 ID/대상/주기/실패 조건/라벨/연결 Alertmanager·연락처/취득 시각을 포함한다. 이는 조회 증빙이며 provisioning 복구 입력이 아니다. 실제 값을 취득하기 전에는 파일을 만들지 않는다. [저장본 안내](../../../monitoring/grafana-cloud/README.md)의 취지에 따라 아래 시크릿 검사와 로컬 검증을 수행한다. README 예시 문자열을 검사하지 않도록 **JSON·템플릿 사본만** 대상으로 삼고 Synthetic 사본도 검사에 포함한다.

```sh
grep -riqE --include='*.json' --include='*.tmpl' 'hooks\.slack\.com|xox[abp]-|glc_|glsa_|Authorization|basicAuth' monitoring/grafana-cloud
```

비밀값 자체는 출력하지 않는다. 종료 0이면 의심 패턴 발견으로 반출을 중단하고 로컬에서 마스킹 여부를 확인한다. `Authorization`·`basicAuth`는 키 이름이나 안전한 비활성 설정에도 걸릴 수 있으므로 값이 비밀인지 검토하고, 필요한 키는 유지하되 비밀값을 제거한 근거를 남긴다. 종료 1이면 패턴 미검출, 2 이상이면 검사 오류다. 미검출은 모든 비밀의 부재를 증명하지 않으므로 export의 인증 헤더·URL 자격 증명도 검토한다.

이어 `python3 monitoring/grafana-cloud/validate_host_alert.py`와 `python3 -m unittest discover -s monitoring/grafana-cloud -p 'test_validate_host_alert.py' -v`를 실행하고 결과를 기록한다. promtool 등 전제 도구가 없으면 검증 미완료로 남긴다. **검증 범위는 host 규칙의 구조·식·합성 동작과 alerting 저장본의 UID/title 중복·후보 재유입 등이다.** Synthetic 사본, 다른 경보의 조건 의미, 연락처·정책 라우팅 전체의 정확성은 검증하지 않으며 Cloud 발화·휴대폰 전달 시험을 대체하지 않는다.

UptimeRobot을 실제 채택할 때는 기존 다운 감지 구성을 정한 **ADR 0039를 먼저 사용자 결정에 맞춰 갱신**하고 독립 경로의 목적·무료 한도·담당자·중단/복구 방법을 기록한다. 이번 제안만으로 ADR이나 외부 서비스를 변경하지 않는다. 2단계의 과거 원인 대조와 6단계에서 복원 후 재시험한 결과는 아래 표에 남긴다.

| 11-13 과거 원인 대조 단계 | 관측 KST·근거 링크/식별자 | 확인 결과·누락 이유 |
|---|---|---|
| probe 실패·OTLP 마지막 표본 | 미확보 | 미확인 |
| 기존 규칙 Pending/Alerting | 미확보 | 미확인 |
| 당시 정책·silence·전송 성공/실패 | 미확보 | 미확인 |
| Slack 도착·휴대폰 수신·사용자 인지 | 미확보 | 미확인 |
| 대조 결론·가설별 채택/배제 근거 | 미확보 | 원인 미확정 |

| 복원 후 재시험 기록 | 기록 값 |
|---|---|
| 복원 사유·수행자·시각 KST | 미실행 |
| 백업 식별자·복원한 규칙/정책/연락처 UID | 미확인(비밀 URL 기록 금지) |
| 원래 연락처 보존/URL 복구 방식·임시 자원 제거 | 미확인 |
| Synthetic/Grafana 각각 재시험 시각·증거 링크 | 미확인 |
| FIRING/RESOLVED·휴대폰 소리·지연·최종 통과/실패 | 미확인 |

### 기존 감지 규칙의 실제 발화 시험 — 별도 승인된 점검 창에서 매니저 수행

임시 규칙의 강제 발화만으로는 가설 1(기존 감지 미작동)을 배제할 수 없다. **기존 `trova-health`의 실패 경보와 UID `fg3ljpj1nelmoe`의 `TrovaCloudScrapeUnavailable`을 그대로 발화**시키는 아래 시험이 필요하다. 이번 문서 수정은 운영 중단 승인이 아니며 실행하지 않는다. 매니저는 사용자에게 영향·최대 중단 시간·담당자·서버 쪽 자동 복구 타이머와 수동 복구 절차를 제시해 별도 승인받은 점검 창에서만 수행한다. 승인된 창이 없으면 해당 인수 행은 미실행으로 남긴다.

1. 기존 체크의 실제 실패 조건·평가 간격과 두 경보의 정상 상태를 확인한다. 이 두 경보의 알림을 막는 silence/mute가 시험 시간에 없어야 한다. 임계값·쿼리·체크 대상·라우팅을 시험용으로 바꾸지 않는다. 서버 쪽 자동 복구 시한을 먼저 준비하고, 명시적 stop은 자동 재시작을 보장하지 않는다는 11-13 교훈을 복구 계획에 반영한다.
2. 승인된 중단으로 앱의 health 응답과 OTLP 전송이 함께 멈추는 조건을 만든다. `trova-health`의 실제 probe 실패 표본이 기록된 뒤, 확인한 실패 조건(문서상 5분 중 3회)에 따라 **기존 Synthetic 관리 경보**가 발화하는지 체크 ID·경보명/인스턴스·시각으로 기록한다. 단순 contact point Test 결과로 대체하지 않는다.
3. 마지막 `process_cpu_usage` 표본부터 5분 부재, 기존 규칙의 2분 Pending 및 1분 평가 위상을 관찰해 `TrovaCloudScrapeUnavailable`의 Alerting과 실제 Slack/휴대폰 전달을 기록한다. 정책 대기까지 포함할 시험 시간을 사전에 계산한다. 승인된 최대 중단 시간 안에 발화/전달이 확인되지 않으면 실패로 기록하고 복구하며, 시험을 위해 무단 연장하지 않는다.
4. 앱을 복구한 뒤 health UP, OTLP 새 표본, 두 기존 경보의 정상 복귀와 RESOLVED 전달을 확인한다. 자동 복구 타이머·임시 설정 정리까지 기록한다. 이 결과는 **시험 당시 기존 감지 경로가 동작함**을 입증할 뿐, 11-13 야간에도 동작했다는 과거 증거는 아니다.

### 인수 조건과 증거 양식

아래 표는 **미실행** 상태다. 과거 원인 불명은 남더라도 이 시험을 통과해야 “밤에도 사용자 휴대폰까지 전달됨을 시험했다”고 말할 수 있다. 이번 작업에서는 운영 장애를 유발하지 않는다. 실제 감지 시험은 위 별도 승인 점검 창에서만 수행하며, 유료 호출·SMS/전화 구매는 하지 않는다. 소리와 실제 수면 중 깨움 여부는 서로 구분해 기록한다.

허용 지연은 다음 **인수용 제안 기준**이며 서비스 SLA나 실측 결과가 아니다. 매니저와 사용자가 시험 전에 확정하고 변경 사유를 남긴다. 실제 정책의 평가 간격/대기와 맞지 않으면 시험 전에 기준·점검 창을 조정하며 결과를 본 뒤 기준을 느슨하게 바꾸지 않는다.

- **D(감지):** Synthetic은 첫 실패 표본부터 Alerting까지 5분 이내, 지표 부재는 마지막 표본부터 Alerting까지 8분 이내를 제안한다. 실제 Synthetic 추가 지속 조건은 사전 확인한다.
- **N(전송):** 새 그룹의 Alerting→Slack은 실제 `group_wait`+2분 이내, Slack→휴대폰 소리는 1분 이내. 기존 그룹 갱신/RESOLVED→Slack은 실제 `group_interval`+2분 이내로 별도 측정한다. 서로 다른 Alertmanager의 정책 시간을 각각 기록한다. 연락처 직접 Test는 실행→Slack 2분 이내를 사용한다.
- **W(깨움):** 휴대폰 소리→사용자가 깨어 확인하기까지 5분 이내. 소리가 없거나 소리가 아닌 다른 이유로 깨어 확인한 경우 깨움 시험 실패다.
- **U(독립 보조):** 장애 시작→이메일/앱 알림 10분 이내, 모바일 소리·깨움은 N의 모바일 구간/W 적용을 제안한다. 실제 무료 기능의 제약을 먼저 확인한다.
- 판정은 `통과/실패/미실행` 중 하나로 기록한다. 해당 지연 초과·누락·소리 미발생은 실패다. 미실행/증거 미확보를 통과로 처리하지 않는다. D·N과 복구 확인이 필요한 행은 모두 충족해야 통과다.

| 시험·적용 기준 | 실패 감지/발화 KST | 정책/억제 여부 | Slack 도착 KST·링크 | 휴대폰 수신·사용자 확인 KST | 해제/정리 | 통과/실패 |
|---|---|---|---|---|---|---|
| 기존 Slack 연락처 테스트(N) | 미실행 | 연락처만 시험 | 미확인 | 미확인 | 미확인 | 미실행 |
| Synthetic 경로 라우팅 FIRING→RESOLVED(N) | 미실행 | Alertmanager·정책 미확인 | 미확인 | 미확인 | 미확인 | 미실행 |
| Grafana 관리 경로 라우팅 FIRING→RESOLVED(N) | 미실행 | Alertmanager·정책 미확인 | 미확인 | 미확인 | 미확인 | 미실행 |
| 기존 trova-health 실패 → 기존 Synthetic 경보 실제 발화(D·N) | 미실행 | 미확인 | 미확인 | 미확인 | UP·정상 복귀·RESOLVED 미확인 | 미실행 |
| 기존 TrovaCloudScrapeUnavailable 표본 부재 → 실제 발화(D·N) | 미실행 | 미확인 | 미확인 | 미확인 | 새 표본·정상 복귀·RESOLVED 미확인 | 미실행 |
| 야간·잠금 화면·PC 활성/비활성(N) | 미실행 | 미확인 | 미확인 | 미확인 | 미확인 | 미실행 |
| 취침 위치에서 무음·방해 금지 각각/조합의 알림 소리(N) | 미실행 | OS 예외 미확인 | 미확인 | 소리·음량·설정 미확인 | 미확인 | 미실행 |
| 사전 동의한 실제 수면 중 알림 소리로 깨움(N·W) | 미실행 | 미확인 | 미확인 | 깨어 확인한 시각·지연 미확인 | 미확인 | 미실행 |
| 독립 보조 경로(채택 시, U·N 모바일 구간·W) | 미실행 | 별도 서비스 | 해당 없음(별도 도착 시각 기록) | 미확인 | 미확인 | 미실행 |

## 저장소 변경·검증·인계

- 추가: 이 보고서. 실제 Cloud 사본·운영 서버·DB는 변경하지 않았다.
- git fetch는 `FETCH_HEAD: Operation not permitted`로 실패했다. 매니저가 최신 main `16e3f2e`로 맞추고 커밋·push·PR을 진행했다. 현재 [PR #497](https://github.com/Trovapp/trova-backend/pull/497), `Closes #496`이며 리뷰 수정 커밋도 매니저가 담당한다.
- 검증: 로컬 링크 검사·비밀값 패턴 검사·`git diff --check` 통과. `./gradlew build`는 2분 55초 후 **1,304개 중 34 실패·6 skipped**로 실패했다. 변경하지 않은 pipeline 테스트 5개 클래스에서 `Operation not permitted (sysctl failed)` 및 자식 프로세스 관측 실패가 발생했다. 빌드 통과로 보고하지 않으며 상세 결과는 로컬 `build/reports/tests/test/index.html`이다.
- PR 리뷰 반영: 실제 발화·야간 깨움 인수 조건, 저장본 검사 범위/한계, 복원과 과거 원인 대조 기록을 보강했다. 운영 시험은 여전히 미실행이다.
- 후속 우선순위: (1) 기존 경로의 야간 휴대폰 수신 시험, (2) 11-13 남은 이력 보존/대조, (3) 독립 무료 보조 체크 채택 검토.
