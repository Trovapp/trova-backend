# Runbooks

Grafana Cloud 경보(Slack 알림)마다 따라갈 대응 문서. 알림의 "Runbook" 링크가 여기로 온다(비공개 레포라 GitHub 로그인 필요).
대시보드는 Grafana "Trova 필수 모니터링"(https://indigostork3365.grafana.net/d/trova-essentials).

| 경보 | 심각도 | 조건 | Runbook |
|---|---|---|---|
| HighHttp5xxErrorRate | critical | 5분 5xx > 10%, 2분 유지, 5분 20건 이상 | [high-http-5xx.md](high-http-5xx.md) |
| HighHttpLatencyP95 | warning | 5분 p95 > 2초, 5분 유지, 5분 20건 이상 | [high-http-latency-p95.md](high-http-latency-p95.md) |
| TrovaApplicationErrorLogs | warning | 10분 ERROR 로그 3건 이상, 2분 유지 | [application-error-logs.md](application-error-logs.md) |
| HighMemoryPressure | warning | 5분 평균 PSI 메모리 full > 20%, 2분 유지 (느려지기 직전 조기 경보) | [memory-pressure.md](memory-pressure.md) |
| TrovaCloudScrapeUnavailable | warning | 서버 지표 5분 0개, 2분 유지 | [cloud-scrape-unavailable.md](cloud-scrape-unavailable.md) |

서버 다운은 Synthetic 헬스 체크 `trova-health`가 따로 알린다(플러그인 관리 경보) — [cloud-scrape-unavailable.md](cloud-scrape-unavailable.md) 2·3절을 따른다.
