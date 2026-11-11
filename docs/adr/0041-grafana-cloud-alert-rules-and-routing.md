# 0041. Grafana Cloud 경보는 임계값·지속 조건·최소 요청 수로 울리고, 알림 정책은 심각도별로 반복한다

- 상태: 확정 · 날짜: 2026-11-11 · 근거: #372, Cloud 정비 보고서(trova-backend-d2 세션, 2026-11-11), Runbook PR #374·#376, 관련 ADR 0039·0040
- 저장본: [monitoring/grafana-cloud](../../monitoring/grafana-cloud/README.md) — 기준은 Cloud, 레포는 받아 둔 사본

## 문제

ADR 0039로 alloy를 끄고 서버가 지표를 직접 보내게 된 뒤, 경보가 실제 상황과 맞지 않았다(Cloud 규칙 상태 이력 14일, 실측).

- `TrovaCloudScrapeUnavailable`: 11-08~11-10에 19번 발생, 그중 9번이 3분 이하(`for: 0s`라 전송 한두 번 지연에도 울림).
- `TrovaApplicationErrorLogs`: Loki 로그 기준 + `or vector(0)`이라 alloy를 끈 뒤로 **늘 Normal인 죽은 경보**.
- 5xx·지연 경보가 없었다. 부하 측정(11-08~11-11)에서 서버가 무너질 때 5xx는 0건이었고 p95가 3~10초로 늘었다 — 장애가 오류가 아니라 **지연으로 먼저** 나타난다.
- 규칙마다 Slack 수신처를 직접 지정(간편 라우팅)해 알림 정책(묶기·반복)이 적용되지 않았고, 메시지 문구가 경보별 if문과 alloy 시절 설명으로 흩어져 있었다.

## 결정

| 경보 | 심각도 | 조건 | 지속 | 데이터 없음 |
|---|---|---|---|---|
| HighHttp5xxErrorRate | critical | 최근 5분 API(`/actuator` 제외) 5xx 비율 > 10%, **5분 요청 20건 이상일 때만** | 2분 | OK |
| HighHttpLatencyP95 | warning | 최근 5분 p95 > 2초(`/actuator`, `/api/shares` 제외), 20건 이상일 때만 | 5분 | OK |
| TrovaApplicationErrorLogs | warning | `logback_events_total{level="error"}` 10분 증가 > 2.5(=3건 이상) | 2분 | OK |
| TrovaCloudScrapeUnavailable | warning | `process_cpu_usage` 5분 동안 표본 0개 | 2분(총 약 7분) | (쿼리가 늘 값을 냄) |

- 모든 규칙: 조회 오류는 직전 상태 유지(`KeepLast`), 라벨 `severity`·`service=trova-backend`·`environment=production`, 주석에 현재값·임계값·측정 범위·지속 조건·대시보드(`trova-essentials`)·Runbook 링크.
- **알림 정책:** 간편 라우팅을 지우고 정책 트리로 보낸다. `grafana_folder, alertname`으로 묶고, critical은 30초 기다려 보내고 1시간마다, warning은 1분 기다려 4시간마다 다시 알린다. 해제(RESOLVED)도 보낸다.
- **메시지:** 공용 템플릿 `trova.slack` 하나로 모든 경보가 같은 틀(🚨/⚠️/🔔 머리말, KST 감지·해제 시간, 대시보드·Runbook·규칙 링크)을 쓴다.
- **서버 다운**은 이 규칙들이 아니라 Synthetic `trova-health`(ADR 0039)가 맡는다. 지표가 끊기면 위 경보는 NoData→OK로 조용해지고, 그 사실은 `TrovaCloudScrapeUnavailable`이 알린다.
- 부하 측정 중에는 `HighHttpLatencyP95`(와 Synthetic)에 사일런스를 건다.

## 이유

- **최소 요청 20건:** 11-07~11-11 5분 창 910개 중 85%가 요청 0건, 실사용 창 중앙값 약 7.5건. 앱을 한 번 열면 홈 API가 약 9개 나가므로 20건 ≈ 앱 열기 2번 이상. 요청이 적을 때는 짧은 오류 묶음도 5분 창에 계속 남아 `for`만으로는 걸러지지 않아서, 이 조건이 실제 거름 장치다. 30건이면 실사용 창의 90% 이상이 평가에서 빠진다.
- **5xx 10%·p95 2초:** 지난 5일을 1분 간격으로 돌려 보면 5xx 조건은 0번, p95 조건은 11번이고 모두 부하 측정 시간대였다(실사용 p95 중앙값 0.07초, 90% 0.77초). `/api/shares`는 평균 4.8초로 원래 느려 뺐다.
- **ERROR 로그 3건:** 외부 API 시간 초과 같은 한두 건으로 울리지 않게. `increase()` 외삽으로 2건이 2.2~2.5로 보일 수 있어 기준을 2.5로 뒀다. 이 지표는 앱 시작 때 0으로 생겨 첫 ERROR도 잡힌다.
- **NoData→OK:** 요청이 적거나 지표가 끊긴 경우를 "장애"로 울리지 않고, 끊김은 전담 경보 하나와 Synthetic이 맡게 해 같은 사건으로 여러 알림이 오지 않게 한다.

## 비교한 대안

| 안 | 판단 |
|---|---|
| 기존 경보 유지(`for: 0s`, Loki 기준) | 짧은 지연에 반복 발생 + 로그 경보는 동작 안 함 |
| 5xx 경보만 추가 | 이 서버의 실제 장애(메모리 압박)는 지연으로 나타나 하나도 못 잡았다 |
| 최소 요청 30건 | 실사용 시간대가 거의 평가에서 빠져 부하 측정 때만 살아 있게 된다 |
| alloy를 다시 켜 Loki 로그로 경보 | ADR 0039의 메모리 문제로 평소에는 켜지 않는다 |
| 규칙별 간편 라우팅 유지 | 묶기·반복 주기를 경보마다 따로 관리해야 한다 |

## 한계

- 20건 미만일 때 한 사람에게 계속 500이 나는 경우는 5xx 경보가 못 잡는다(ERROR 로그 경보와 Synthetic이 대신).
- 재시작 뒤 어떤 API의 첫 5xx 1건은 OTLP 카운터의 첫 표본이라 `increase()`에 잡히지 않는다.
- 서버 메모리·스왑·PSI·CPU steal 지표는 아직 없어 경보가 없다(앱이 `/proc`를 읽어 보내는 작업 예정).
- Slack 실제 화면(이모지·링크)은 테스트 발송 전이라 확인하지 않았다(템플릿 미리보기로만 확인).

## 다시 볼 조건

실사용 트래픽이 늘어 5분 요청 중앙값이 20건을 넘을 때, 메모리 지표가 생겨 지연 경보를 메모리 기준으로 바꿀 수 있을 때, 같은 경보가 한 주에 3번 넘게 오탐일 때.
