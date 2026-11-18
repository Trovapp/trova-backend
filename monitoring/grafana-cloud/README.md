# Grafana Cloud 저장본

운영 모니터링(Grafana Cloud `indigostork3365`)의 대시보드·경보·알림 설정을 그대로 받아 둔 것이다.
**기준은 Cloud다.** 여기 파일은 기록·복구용이고, Cloud에서 바꾸면 다시 받아서 덮어쓴다(이 파일을 고쳐 Cloud에 올리지 않는다).
결정 배경은 [ADR 0039](../../docs/adr/0039-direct-otlp-metrics-and-external-health-check.md)(지표 직접 전송·외부 헬스 체크),
[ADR 0041](../../docs/adr/0041-grafana-cloud-alert-rules-and-routing.md)(경보 기준·알림 정책), 대응은 [docs/runbooks](../../docs/runbooks/README.md).

받은 시점: 2026-11-12 KST(#402) — `trova-essentials` version 9(넘김 패널에 "다시 보냄" 선 추가, ADR 0043). 그 전 #396: version 8(넘김 패널에 일정 재구성 추가). 그 전 #394: version 7(대기열 — Kafka 줄: 처리 못 한 메시지·넘김·실패 작업), 경보 그룹 `trova-kafka` 추가. 그 전: 2026-11-11 KST 오후 — 서버 메모리 지표(`host_*`, #379) 배포와 r23 측정 뒤. `trova-essentials`는 version 5(읽는 법 안내, CPU steal·iowait, 서버 여유 메모리·스왑·PSI 패널 추가), 경보 그룹 `trova-host` 추가.

| 파일 | Cloud 위치 | 내용 |
|---|---|---|
| `dashboards/trova-essentials.json` | 대시보드 "Trova 필수 모니터링" | 요약 5개 + 시스템(CPU·메모리·스왑·PSI)·JVM·애플리케이션·비즈니스. 평소 보는 화면, 경보 링크가 여기로 온다 |
| `dashboards/trova-operations.json` | "Trova 운영 모니터링" | 예전 운영 화면(11개 패널, 지표 이름은 직접 전송과 같아 그대로 나옴) |
| `dashboards/trova-logs.json` | "Trova 운영 로그" | Loki 로그. alloy를 평소 꺼 두므로 대부분 비어 있음 |
| `dashboards/trova-traces.json` | "Trova 요청 추적" | Tempo 추적. 위와 같은 이유로 대부분 비어 있음 |
| `alerting/rules-trova-http.json` | 경보 그룹 `trova-http` | HighHttp5xxErrorRate(critical), HighHttpLatencyP95(warning) |
| `alerting/rules-trova-cloud-logs.json` | `trova-cloud-logs` | TrovaApplicationErrorLogs(warning, `logback_events_total` 기준) |
| `alerting/rules-trova-cloud-availability.json` | `trova-cloud-availability` | TrovaCloudScrapeUnavailable(warning) |
| `alerting/rules-trova-host.json` | `trova-host` | HighMemoryPressure(warning, PSI memory full 5분 > 20%, 2분 지속) — 기준 근거는 ADR 0042·`docs/runbooks/memory-pressure.md`(PR #381) |
| `alerting/rules-trova-kafka.json` | `trova-kafka` | TrovaKafkaConsumerLag(warning, 토픽별 처리 못 한 메시지 > 3개 15분), TrovaKafkaDeadLetter(warning, 실패 작업 토픽으로 보낸 메시지 15분 > 0) — `docs/runbooks/kafka-queue.md` |
| `alerting/notification-policies.json` | 알림 정책 | 묶음·반복 주기(critical 1시간, warning 4시간) |
| `alerting/contact-points.json` | 연락처 `trova-slack-alerts` | Slack. **webhook 주소는 `[REDACTED]`** — 복구할 때 Slack 앱에서 다시 넣는다 |
| `alerting/templates.json`, `trova.slack.tmpl` | 알림 템플릿 `trova.slack` | Slack 메시지 틀(같은 내용, `.tmpl`은 읽기용) |

담지 않은 것: Synthetic 체크 `trova-health`와 그 경보(Synthetic Monitoring 앱이 관리), Asserts가 자동으로 만든 경보.

## 다시 받는 방법

Grafana에 로그인한 브라우저에서 아래를 GET으로 받는다(조회만 하므로 Cloud는 바뀌지 않는다).

```
/api/dashboards/uid/<uid>                                              # 응답의 .dashboard (id는 뺀다)
/api/v1/provisioning/folder/<폴더 uid>/rule-groups/<그룹>/export?format=json
/api/v1/provisioning/policies/export?format=json
/api/v1/provisioning/contact-points/export?format=json                 # trova-slack-alerts만 남긴다
/api/v1/provisioning/templates
```

받은 뒤 `grep -rE 'hooks\.slack\.com|xox[abp]-|glc_' monitoring/grafana-cloud`로 시크릿이 없는지 확인한다(연락처 export는 기본으로 가려져 나온다).

## 승인 대기 후보

`proposals/high-memory-pressure-gap.json`은 PSI 공백 보완 **미승인 후보**이며 Cloud 저장본·복구 입력이 아니다. 현재 규칙은 `alerting/rules-trova-host.json`에 그대로 보관한다. 후보는 복구 경로 `alerting/` 밖에 두어 폴더 전체를 재귀 수집해도 섞이지 않게 한다. 후보 전용 UID `candidate-memory-pressure-gap`, 이름 `[Candidate] HighMemoryPressure gap continuity`, 그룹 `trova-host-candidate`, `isPaused: true`를 사용하므로 더 넓은 경로를 실수로 import해도 운영 UID·그룹을 덮어쓰거나 같은 이름의 활성 경보를 만들지 않는다. 후보를 복구 폴더에 복사하거나 후보 전용 식별자로 Cloud 경보를 생성하지 않는다.

[검토·승인할 변경](../../docs/monitoring/2026-11-18-memory-pressure-alert-review.md) 승인 뒤 매니저가 **기존 UID `bg3yd8j9d5fr4a`를 유지한 채** 수정한다. 식별자·일시중지 값을 통째 복사하지 않는 순서, export 갱신·후보 삭제와 롤백은 [runbook 7절](../../docs/runbooks/memory-pressure.md#7-승인-대기-변경-cloud-미반영)에 따른다.

적용 경로 조사: 레포에는 Cloud `alerting/`을 자동 provisioning/import하는 CI·스크립트·마운트가 없다. `docker-compose.monitoring.yml`은 별도 로컬 경로 `monitoring/grafana/provisioning/`만 마운트하고 그곳의 provider는 대시보드만 읽는다. Cloud는 아래 수동 복구 절차를 사용한다. 따라서 문구로 하위 폴더를 제외하던 방식 대신 경로 자체를 분리하고 식별자도 나눴다.

## 되돌리기·복구

**`proposals/` 아래 파일은 복구 대상에서 제외하며 PUT·Import하지 않는다.** 아래 경보 복구는 `alerting/rules-*.json`의 현재 저장본만 대상으로 한다. `alerting/` 전체를 수집해도 후보가 없어야 하며 검증 스크립트는 후보의 재유입과 UID/title 중복을 거부한다.

- 대시보드: Grafana → Dashboards → New → Import에 JSON을 넣는다(uid가 같으면 덮어쓴다).
- 경보·정책·템플릿: Grafana provisioning API(`PUT /api/v1/provisioning/alert-rules/{uid}`, `PUT /api/v1/provisioning/policies`,
  `PUT /api/v1/provisioning/templates/trova.slack`)에 해당 내용을 넣는다. 연락처는 webhook 주소를 다시 넣어야 한다.
- 레포의 `monitoring/alerts.yml`은 로컬 Prometheus(docker)용 규칙이라 이 Cloud 경보와 별개다.

## 로컬 경보 검증

`python3 monitoring/grafana-cloud/validate_host_alert.py` — 저장본 UID/title 유일성, 후보 경로·별도 식별자·일시중지, JSON 참조·조건 검사와 설치된 `promtool`을 이용한 공백/복귀/빈 시계열 합성 검증. `python3 -m unittest discover -s monitoring/grafana-cloud -p 'test_validate_host_alert.py' -v`는 후보와 저장본 충돌·복구 경로 재유입 회귀를 검사한다. 로컬 `alerts.yml`은 별도이며 `promtool test rules monitoring/alerts.test.yml`로 검사한다. 실제 Cloud 전이·r28 원시 시계열 재생은 아니다.

실패 대조군: `python3 monitoring/grafana-cloud/validate_host_alert.py --variant original`, `--variant max`는 각각 종료 코드 1이어야 한다. `python3 -O monitoring/grafana-cloud/validate_host_alert.py`도 같은 검사를 수행한다.
