# r26 경보 상태 기록 (Grafana 규칙 상태 API, 30초 간격·브라우저 탭이라 간격이 늘어진 구간 있음, KST)

| 시각 | 바뀐 경보 | 상태 |
|---|---|---|
| 19:04:53 | 전체 5개 | inactive (측정 시작) |
| 19:06:53 | HighHttpLatencyP95 | pending (5분 p95 > 2초) |
| 19:12:12 | HighHttpLatencyP95 | firing (5분 유지 → Slack warning) |
| 19:16:12 | HighHttpLatencyP95 | inactive (해제 → RESOLVED) |

HighMemoryPressure·HighHttp5xxErrorRate·TrovaApplicationErrorLogs·TrovaCloudScrapeUnavailable은 측정 내내 inactive.

## 같은 시간 지표 (Grafana Cloud, 1분 간격)

| 시각 | PSI 메모리 full 5분 평균(%) | 스왑 읽기(페이지/초, 5분) | 서버 p95(초, 5분) |
|---|---|---|---|
| 19:06 | 0.7 | 10.6 | 0.2 |
| 19:08 | 2.2 | 43.8 | 2.7 |
| 19:10 | 5.8 | 95.2 | 2.6 |
| 19:12 | **11.3** (최고) | 185.3 | 2.5 |
| 19:14 | 11.2 | 220.1 | 2.1 |
| 19:16 | 8.9 | 220.1 | 1.8 |
| 19:18 | 9.5 | 225.3 | 1.7 |
| 19:20 | 6.1 | 119.2 | 1.4 |
| 19:22 | 2.1 | 25.8 | 1.0 |
