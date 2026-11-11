# Runbook: ERROR 로그 증가 (TrovaApplicationErrorLogs)

Slack 알림의 Runbook 링크가 이 문서다. 운영에 영향을 주는 작업은 원인을 확인한 뒤 담당자(사용자) 승인을 받고 한다.
공통 명령은 [HTTP 5xx Runbook](high-http-5xx.md) 4~5절과 같다.

## 1. 장애 개요

| 항목 | 내용 |
|---|---|
| 경보 이름 | `TrovaApplicationErrorLogs` (Grafana Cloud 관리 경보) |
| 심각도 | warning (반복 알림 4시간 간격, 해제되면 RESOLVED) |
| 조건 | 최근 **10분** 동안 앱 ERROR 로그 **3건 이상**, 그 상태가 **2분** 유지 |
| 지표 | `logback_events_total{level="error"}` — 로그 **개수**만 Grafana로 간다. 본문은 서버에만 있다(Loki 수집 꺼짐, ADR 0039) |
| 데이터 없음 | 정상으로 처리. 지표 자체가 끊기면 `TrovaCloudScrapeUnavailable`이 따로 울린다 |
| 사용자 영향 | 오류 종류에 따라 다르다 — 영상 분석 실패, 푸시 실패, API 오류 등 |

```promql
sum(increase(logback_events_total{job="trova-prod", level="error"}[10m]))
```

서버를 재시작하면 카운터가 0부터 다시 센다. 재시작 직후 처음 난 ERROR 몇 건은 증가로 잡히지 않을 수 있다.

## 2. 로그 본문 보기 (먼저)

```
ssh -i ~/.ssh/oracle_buildlog_vm ubuntu@193.123.161.217 'sudo journalctl -u trova-backend --since "-20min" --no-pager | grep -E " ERROR " | tail -40'
# 스택 트레이스까지
ssh -i ~/.ssh/oracle_buildlog_vm ubuntu@193.123.161.217 'sudo journalctl -u trova-backend --since "-20min" --no-pager | grep -A15 " ERROR " | tail -120'
```

- 같은 메시지가 반복되는가, 서로 다른 오류가 섞였나.
- 로거 이름(`com.trova.backend.<패키지>`)으로 기능을 가른다: `pipeline`(영상 분석), `geocoding`(카카오), `push`(웹 푸시), `planner`·`replan`(일정), `queue`(Kafka).

## 3. 원인별 확인

| 로그에 보이는 것 | 원인 | 확인 | 대응 |
|---|---|---|---|
| `pipeline`, `yt-dlp`, 인스타그램 `429`·로그인 요구 | 영상 내려받기 차단 | 대시보드 "영상 분석 결과" failure, `/api/shares` 실패 | 인스타 차단은 몇 시간 뒤 풀리는 경우가 많다(#365 자동 재시도 미구현). 반복되면 기록 |
| `gemini`, `429`, `RESOURCE_EXHAUSTED`, 시간 초과 | Gemini 무료 한도·장애 | "외부 API 성공률" gemini 하락 | 로컬·운영이 같은 키를 쓴다 — 오늘 사용량 확인. 한도면 태평양 자정까지 기다림 |
| `kakao`, `geocoding` | 카카오 지오코딩 실패 | "외부 API 성공률" kakao | 무료 한도(일 10만) 확인, 대개 일시적 |
| `Kafka`, `queue`, fallback | Aiven Kafka 연결 문제 | `trova_queue_fallback_total` 증가 | 브로커 실패 시 async로 넘긴다(ADR 0021) — 분석은 계속됨. 브로커 상태만 확인 |
| `HikariPool`, `SQL`, `Connection` | DB 문제 | DB 연결 대기 | [HTTP 5xx Runbook](high-http-5xx.md) 4절 1·2번 |
| `OutOfMemoryError` | 힙 부족 | 힙 committed가 144MiB에 붙음 | 자동 재시작됨. ADR 0040 재검토(힙 상한 올리기) — 사용자 결정 |
| 처음 보는 예외 | 코드 버그 | 최근 배포 시각 | 이슈로 남긴다. 사용자 영향이 크면 롤백 검토(5xx Runbook 5절) |

## 4. 정상화 확인

- [ ] 10분 동안 ERROR가 3건 아래로 내려오고 RESOLVED가 왔다.
- [ ] 원인이 외부(인스타·Gemini 한도)라면 그 사실과 풀린 시각을 기록했다.

## 5. 사후 분석

반복되는 오류는 이슈로 만든다. 사용자 영향이 있었다면 `docs/monitoring/2026-11-04-capacity-issue-log.md`에 한 절로 남긴다.
경보가 의미 없이 울렸다면(무해한 ERROR) 로그 레벨을 고치는 쪽을 먼저 검토한다 — 기준을 올려 덮지 않는다.
