# Runbook: 서버 지표 수신 중단 (TrovaCloudScrapeUnavailable)

Slack 알림의 Runbook 링크가 이 문서다. 운영에 영향을 주는 작업은 원인을 확인한 뒤 담당자(사용자) 승인을 받고 한다.

## 1. 장애 개요

| 항목 | 내용 |
|---|---|
| 경보 이름 | `TrovaCloudScrapeUnavailable` (Grafana Cloud 관리 경보) |
| 심각도 | warning (반복 알림 4시간 간격, 해제되면 RESOLVED) |
| 조건 | 서버가 직접 보내는 지표(`process_cpu_usage`)가 **5분** 동안 하나도 안 들어오고, 그 상태가 **2분** 더 유지 |
| 구조 | 앱이 1분마다 OTLP로 Grafana Cloud에 보낸다(alloy 없음, ADR 0039). 이 경보가 울리면 앱이 죽었거나, 보내기가 막힌 것 |
| 함께 볼 것 | 외부 헬스 체크 `trova-health`(Grafana Synthetic, 서울에서 1분마다 `/actuator/health`) |
| 영향 | 이 동안 다른 지표 경보(5xx·p95·ERROR 로그)는 판단하지 못한다 |

```promql
absent_over_time(process_cpu_usage{job="trova-prod"}[5m])
```

## 2. 먼저 가를 것: 서버가 죽었나, 보내기만 막혔나

대시보드 "Trova 필수 모니터링" 맨 위 **서버 응답(헬스 체크)** 을 본다(이 값은 외부에서 재므로 앱 지표가 끊겨도 나온다).

| 헬스 체크 | 뜻 | 다음 |
|---|---|---|
| **0 (실패)** | 앱이 죽었거나 응답하지 못함 | 3절 |
| **1 (정상)** | 앱은 살아 있고 지표 전송만 끊김 | 4절 |

## 3. 앱이 죽었을 때

```
ssh -i ~/.ssh/oracle_buildlog_vm ubuntu@193.123.161.217 'systemctl status trova-backend --no-pager | head -15; systemctl show trova-backend -p NRestarts -p ActiveEnterTimestamp; sudo journalctl -u trova-backend --since "-15min" --no-pager | grep -E "OutOfMemory|Started|Stopping|ERROR" | tail -20; free -m'
```

- **재시작 중**(`activating`, 최근 `Started` 없음): 시작에 약 110초 + 예열이 걸린다. 배포·수동 재시작이었다면 기다린다. RESOLVED가 오면 끝.
- **힙 부족 종료**(`OutOfMemoryError`, `NRestarts` 증가): 5초 뒤 자동 재시작된다. 반복되면 ADR 0040(힙 144MiB) 재검토 — 사용자 결정.
- **서버(VM) 자체가 응답 없음**(ssh 안 됨): Oracle Cloud 콘솔에서 인스턴스 상태 확인. 재부팅은 사용자 승인 뒤. 같은 VM에 safeKor 수집기도 있다.
- 오류 순간 기록: `ls -t /var/log/trova-incident | head` — 헬스가 UP이 아니던 순간의 메모리·프로세스·로그가 남는다.

## 4. 앱은 살아 있는데 지표만 끊겼을 때

```
ssh -i ~/.ssh/oracle_buildlog_vm ubuntu@193.123.161.217 'sudo journalctl -u trova-backend --since "-15min" --no-pager | grep -iE "otlp|OtlpMeterRegistry|metrics" | tail -20; curl -s -o /dev/null -w "%{http_code}\n" https://grafana.com/'
```

- **전송 오류 로그(401·403)**: Grafana Cloud 토큰 만료·권한. 토큰은 운영 `.env`에 있다 — 값은 채팅·문서에 옮기지 않고 사용자가 교체한다.
- **429·한도**: Grafana Cloud 무료 한도(시리즈 1만, 2026-11 기준 약 2,100개 사용) 또는 체험 기간 종료 확인.
- **네트워크 오류**: 서버의 바깥 연결 문제(위 `curl`이 200이 아니면 의심). 대개 일시적. 2분 지속 조건이 있어 짧은 끊김은 울리지 않는다.
- 앱 설정이 바뀌었는지: `app.metrics.otlp.enabled`가 꺼지지 않았나(측정용 drop-in이 남아 있지 않나 `ls /etc/systemd/system/trova-backend.service.d/`).

## 5. 정상화 확인

- [ ] 대시보드 그래프가 다시 이어지고 RESOLVED가 왔다.
- [ ] 헬스 체크가 1이다.
- [ ] 끊긴 동안 다른 문제가 없었는지 5xx·p95·ERROR 로그 패널을 그 시간대로 다시 본다.

## 6. 사후 분석

끊긴 시간과 원인을 `docs/monitoring/2026-11-04-capacity-issue-log.md`에 남긴다. 2026-11까지 이 경보는 19번 울렸고 그중 9번이 3분 이하(일시 지연)여서 지속 조건을 2분으로 늘렸다 — 그래도 잦으면 조건을 다시 본다.
