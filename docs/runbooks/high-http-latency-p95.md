# Runbook: API 응답 지연 (HighHttpLatencyP95)

Slack 알림의 Runbook 링크가 이 문서다. 운영에 영향을 주는 작업(재시작·롤백·설정 변경)은 원인을 확인한 뒤 담당자(사용자) 승인을 받고 한다.
공통 명령(서버 자원 확인, 재시작, 롤백)은 [HTTP 5xx Runbook](high-http-5xx.md) 4~5절과 같다.

- 대시보드: Grafana "Trova 필수 모니터링" — https://indigostork3365.grafana.net/d/trova-essentials

## 1. 장애 개요

| 항목 | 내용 |
|---|---|
| 경보 이름 | `HighHttpLatencyP95` (Grafana Cloud 관리 경보) |
| 심각도 | warning (반복 알림 4시간 간격, 해제되면 RESOLVED) |
| 조건 | 최근 **5분** p95 응답 시간 > **2초**, 그 상태가 **5분** 연속 유지 |
| 제외 | `/actuator/*`(헬스 체크), `/api/shares`(영상 접수 — 원래 느림) |
| 최소 트래픽 | 5분 요청 20건 미만이면 평가하지 않음. 요청이 없으면 정상 처리 |
| 사용자 영향 | 앱 홈·목록이 늦게 뜨거나, 오래 기다리다 실패처럼 보임 |

```promql
histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job="trova-prod", uri!~"/actuator.*|/api/shares"}[5m])))
  and on() sum(increase(http_server_requests_seconds_count{job="trova-prod", uri!~"/actuator.*|/api/shares"}[5m])) >= 20
```

**이 서버에서 가장 흔한 장애 모양이다.** 2026-11 운영 부하 측정에서 영상 분석과 조회가 겹치면 5xx가 아니라 응답 지연·시간 초과로 무너졌다
(조회 오류 38~55%, 힙 상한 144MiB 적용 뒤 4.6~15.2%, ADR 0040). 5xx 경보보다 이 경보가 먼저 울린다.

## 2. 최초 확인

1. 대시보드 "애플리케이션" → **API별 p95**: 한 API만 느린가(코드·쿼리), 전부 같이 느린가(서버 자원).
2. "비즈니스" → **영상 분석 결과·시간**: 지금 영상 분석이 돌고 있나. 같이 느려졌다면 분석과 조회가 겹친 것.
3. "JVM" → **힙 committed·GC 멈춤**: 힙이 상한(144MiB)에 붙었거나 GC 멈춤이 0.5초를 자주 넘으면 메모리 압박.
4. "시스템" → **CPU·코어당 부하**: 1을 넘게 이어지면 CPU 부족(무료 서버 몫이 줄었을 수 있음).
5. 같은 시각 다른 경보: 5xx·ERROR 로그·지표 수신 중단이 같이 왔나.

## 3. 원인별 확인

| 원인 | 신호 | 확인 | 대응 |
|---|---|---|---|
| **영상 분석과 조회 겹침(메모리)** | 분석 진행 중, 힙 상한 근접, GC 멈춤 증가 | 서버 `cat /proc/pressure/memory`의 `full avg10` ≥ 10%, `free -m`의 available < 100MiB | 분석이 끝나면 대개 회복한다(분석은 동시에 1개, ADR 0038). 반복되면 사후 분석에 기록하고 용량 대책 검토 |
| **CPU 부족(steal)** | CPU 100%, 코어당 부하 > 1, 분석 없음 | 서버 `top -bn1 | head -5`의 `st`(steal) | 무료 서버 몫 문제라 기다린다. 오래 가면 기록(ADR 0037: 한계는 CPU) |
| **특정 API의 느린 쿼리** | 한 API만 p95 상승 | `journalctl`에서 그 경로, DB 연결 대기 | 이슈로 남기고 쿼리·인덱스 확인(ADR 0034·0035 참고) |
| **DB 응답 지연** | 모든 API 상승 + DB 연결 대기 > 0 | `HikariPool` 로그 | Supabase 상태 확인. 대개 스스로 회복 |
| **재시작 직후** | 재시작 몇 분 안 | `systemctl show trova-backend -p ActiveEnterTimestamp`, `journalctl | grep 예열` | 예열(#356)이 돌았는지 확인. 몇 분 안에 내려가면 정상 |

## 4. 복구

- 대부분은 **기다리며 지켜본다**(분석이 끝나거나 CPU 몫이 돌아오면 회복). 재시작은 메모리 압박을 잠깐 풀지만 진행 중인 분석을 끊고 2분 동안 앱이 멈춘다.
- 재시작이 필요하면 [HTTP 5xx Runbook](high-http-5xx.md) 5절 순서(진행 중 작업 확인 → 승인 → 묵음 → 재시작).

## 5. 정상화 확인

- [ ] p95가 2초 아래로 내려오고 RESOLVED가 왔다(평소 0.5~1초).
- [ ] 앱 홈이 2~3초 안에 뜬다.
- [ ] 진행 중이던 영상 분석이 끝났다(비즈니스 패널 success 증가 또는 failure 원인 확인).

## 6. 사후 분석

[HTTP 5xx Runbook](high-http-5xx.md) 7절 항목대로 `docs/monitoring/2026-11-04-capacity-issue-log.md`에 남긴다. 특히 힙 committed 최대값과 GC 직후 사용량을 적는다(ADR 0040 재검토 조건: GC 직후 120MiB 초과).
