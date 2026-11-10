# 0039. 운영 지표는 서버가 직접 보내고, 서버 다운은 외부 헬스 체크로 감지한다 (alloy는 평소 끔)

- 날짜: 2026-11-10
- 상태: 확정 — 2026-11-10 운영 반영(427d727), 직접 전송 비용 측정(r10: java +7MiB, 스왑 읽기 3→4회/초)
- 관련: #358, #355, 문제 기록 #16(`docs/monitoring/2026-11-04-capacity-issue-log.md`), ADR 0037

## 문제

운영 서버(E2.1.Micro, 메모리 956MiB)는 java만 500MiB를 넘게 쓴다. 11-07에 Grafana Cloud 수집기 alloy를 넣은 뒤
릴스 분석이 겹치면 조회가 무너졌다(#16). 같은 조건 비교(2026-11-10 운영, 조회 50명만, 각 12분):

| | alloy 지표만 켬 | alloy 끔 |
|---|---|---|
| 조회 p50 / p95 | 0.31초 / 1.8초 | 0.19초 / 0.93초 |
| 스왑 다시 읽기 | 199회/초 | 3회/초 |
| 메모리 full 대기 | 5.1% | 0.8% |

릴스까지 겹치면 alloy 전부 켬 조회 오류 79.7%(r5), 지표만 32.4%(r8), 끔 0.6%(r6, 실제 릴스 5개 처리).
지표만 남긴 alloy도 별도 프로세스 약 60MiB(대부분 스왑)라 이 서버에서는 결정적인 차이가 됐다.

## 해결안

| 안 | 판단 |
|---|---|
| alloy 그대로 | 표준 구성이지만 위 측정대로 무너진다 |
| alloy 수집 줄이기 | 지금도 지표 하나만 60초마다 긁는다. 프로세스가 남는 한 줄일 몫이 작다 |
| 평소 끄고 오류 때 자동으로 켜기 | 지표는 켠 뒤부터만 보여 오류 순간이 비고, 메모리가 바닥난 순간에 부담을 더한다 |
| Grafana Cloud가 서버를 긁기 | 서버 부담 0이지만 지표 주소를 인터넷에 열어야 한다 |
| **서버(Micrometer)가 OTLP로 직접 보내기 + 외부 헬스 체크 + 오류 순간 기록 (고름, 사용자 선택 1)** | 프로세스가 늘지 않는다. 그래프에 보이는 값은 같다 |

사용자 질문 "서버로 직접 보내면 서버가 죽었다를 알 수 없으면 안 될 것 같다"에 대해: alloy의 `up`도 같은 VM 안이라
VM이 멈추면 `up=0`을 보내지 못하고 끊길 뿐이었다. 다운은 바깥에서 확인하는 쪽이 정확하다(접속 경로·인증서 문제까지 잡는다).

## 결정

- **지표:** `OtlpMetricsConfig`가 `OtlpMeterRegistry`를 만든다(`APP_METRICS_OTLP_ENABLED=true`인 운영에서만). `/actuator/prometheus`와
  같은 지표, 60초 간격, 시간 단위는 초(이름이 `_seconds`로 기존과 같게), `service.name`·`service.instance.id`=`trova-prod`(→ `job`·`instance` 라벨).
  스프링 자동 설정(`OtlpMetricsExportAutoConfiguration`)은 OpenTelemetry SDK 모듈(추적 포함)을 요구해 끄고 직접 만든다.
- **다운 감지:** Grafana Cloud Synthetic Monitoring 체크 `trova-health` — Seoul 1곳에서 1분마다 `/actuator/health`(2xx, 본문 `"status":"UP"`, 5초),
  5분 중 3번 실패하면 Slack(`trova-slack-alerts`). 월 약 4.5만 회(무료 10만 회 안). 배포 재시작(약 2분)은 2번 실패라 알리지 않는다.
- **끊김 알림:** alloy `up`을 쓰던 `TrovaCloudScrapeUnavailable`은 일시 정지했다. 직접 전송 반영 뒤 "지표가 5분 동안 안 옴"으로 바꾼다.
- **오류 순간 기록:** `monitoring/incident-snapshot/` — 1분마다 헬스가 UP이 아니거나 메모리 full 대기 avg10 ≥ 10%면
  메모리·스왑·메모리 대기·프로세스·`/actuator/prometheus`·최근 5분 앱 로그를 `/var/log/trova-incident/`에 남긴다(10분에 한 번, 60개 보관).
- **alloy:** 운영에서 stop + disable(2026-11-10). 로그를 Grafana에서 봐야 할 때만 켠다(journal을 거슬러 읽는다).

## 잃는 것

- 서버(OS) 지표의 평소 그래프 — 오류 순간 기록으로 대신한다.
- 로그·요청 추적을 Grafana에서 상시 보기 — 서버에서 `journalctl`, 필요하면 alloy를 잠깐 켠다. `TrovaApplicationErrorLogs` 알림은 그동안 동작하지 않는다.
- 네트워크가 끊긴 동안의 지표(보관 후 재전송 없음).

## 확인할 것

운영 반영 뒤 조회 50명만(`prod_analysis.py --shares 0 --min-minutes 10`)을 재서 r9b(alloy 끔, 직접 전송 없음)와 java 메모리·스왑 읽기·조회 p95를 비교한다.
Grafana `trova-operations` 대시보드 쿼리(`http_server_requests_seconds_*`, `jvm_*`, `process_cpu_usage`)가 그대로 나오는지 보고, `up` 패널은 헬스 체크 결과로 바꾼다.

## 운영 측정 결과 (2026-11-10, r10)

조회 50명만, alloy 끔: 직접 전송 없음(r9b) p95 0.93초·스왑 읽기 3회/초·java 최대 482MiB → 직접 전송(r10) 1.0초·4회/초·489MiB.
Grafana `trova-operations` 쿼리는 이름·라벨이 그대로 맞았고 `up` 패널만 `probe_success{job="trova-health"}`로 바꿨다.
