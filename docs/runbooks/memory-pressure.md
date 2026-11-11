# Runbook: 서버 메모리 압박 (HighMemoryPressure)

Slack 알림의 "Runbook" 링크가 이 문서다. 응답이 **느려지기 직전**에 울리도록 만든 조기 경보다.
운영에 영향을 주는 작업(재시작·설정 변경)은 **원인을 확인한 뒤 담당자(사용자) 승인을 받고** 한다.

- 대시보드: Grafana "Trova 필수 모니터링" — https://indigostork3365.grafana.net/d/trova-essentials ("시스템 — 서버 자원" 줄)
- 서버 접속: `ssh -i ~/.ssh/oracle_buildlog_vm ubuntu@193.123.161.217` (Oracle E2.1.Micro, 메모리 956MiB, 2코어)
- 관련 결정: ADR 0040(힙 상한 144MiB), ADR 0042(서버 지표를 앱이 /proc에서 읽음)

## 1. 장애 개요

| 항목 | 내용 |
|---|---|
| 경보 이름 | `HighMemoryPressure` (Grafana Cloud, 규칙 그룹 `trova-host`) |
| 심각도 | warning (Slack 반복 4시간 간격, 해제되면 RESOLVED) |
| 조건 | 최근 **5분** 평균 PSI 메모리 full > **20%**, 그 상태가 **2분** 이어짐. 지표가 없으면 정상 처리(서버 다운은 다른 경보가 맡음) |
| 사용자 영향 | 아직 없거나 시작 단계. 이어서 p95 지연·시간 초과가 온다 |

```promql
100 * sum(rate(host_pressure_stall_seconds_total{job="trova-prod", resource="memory", type="full"}[5m]))
```

**PSI(Pressure Stall Information)란:** 리눅스가 "프로그램이 자원을 기다리느라 멈춰 있던 시간 비율"을 알려 주는 값(`/proc/pressure/memory`).
메모리가 모자라 스왑(디스크)에서 다시 읽어 오는 동안 멈춘 시간이 쌓인다. `full`은 **모든** 프로그램이 동시에 멈춘 시간이라 서버 전체가 사실상 멈춘 비율이다.
사용량(몇 MiB 쓰나)과 달리 **실제로 일을 못 한 시간**이라 사용자가 느끼는 느려짐과 거의 바로 이어진다.

**왜 20%·2분인가 (2026-11 운영 측정 21회에 대어 봄, `docs/monitoring/2026-11-08-prod-analysis/`):**

| 회차 묶음 | 조회 오류율 | 5분 평균 PSI 메모리 full 최대 | 이 경보 |
|---|---|---|---|
| 조회만(r9b·r10·r12·r22·r23) | 0% | 1~6% | 안 울림 |
| 릴스 분석 + 조회, 멀쩡(r6·r13·r14·r21·r26) | 0.6~4.6% | 11~21% (20% 넘은 시간 최대 0.5분) | 안 울림 |
| 릴스 분석 + 조회, 나빠짐(r8·r11·r15~r20·r5) | 8.2~80% | 26~36% (20% 넘은 시간 4.6~19분) | 울림 |

10%로 잡으면 멀쩡한 분석 중에도 울리고, 25%로 잡으면 오류 8.2%였던 r20을 놓친다(25% 넘은 시간 0.6분).

**경보를 만든 뒤 실측 (2026-11-11 r26, #384):** 릴스 분석 + 조회(오류 0.7%)에서 PSI 5분 평균 최고 11.3% → 이 경보는 울리지 않았다(의도대로).
같은 회차에서 HighHttpLatencyP95는 19:12~19:16 울렸다 — 분석이 겹치면 p95가 먼저 2초를 넘는다. 이 경보가 실제로 울린 기록은 아직 없다(울리면 6절 사후 분석에 남긴다).

## 2. 최초 확인 (5분 안에)

대시보드 "시스템 — 서버 자원" 줄을 본다.

1. **PSI 그래프**: 메모리 full이 계속 오르나, 한 번 튀고 내려오나.
2. **서버 여유 메모리·java 메모리**: available이 100MiB 아래인가(무너질 때 43~66MiB). java RSS가 평소(410~500MiB)보다 큰가.
3. **스왑 읽기**: 초당 수백 페이지가 이어지나(조회만 50명일 때 약 40).
4. **CPU steal·iowait**: iowait가 높으면 스왑 읽기 때문이다. steal이 높으면 우리 탓이 아닌 느려짐(무료 서버 공유)이다.
5. **비즈니스·애플리케이션**: 영상 분석이 진행 중인가, 요청 수가 갑자기 늘었나.

## 3. 원인별 확인

| 원인 | 확인 | 대응 |
|---|---|---|
| **영상 분석과 조회 겹침 (가장 흔함)** | 진행 중 분석 수: `echo "select status, count(*) from processing_jobs where status in ('PENDING','PROCESSING') group by status;" \| PROD_DB_ENV_FILE=<운영 DB 환경 파일> python3 monitoring/prod_readonly_psql.py` | 분석이 끝나면 대개 회복한다. 기다리며 p95 경보를 본다 |
| **힙이 커짐** | JVM 줄 "힙 메모리" committed가 144MiB에 붙었나, `ps -o rss -C java` | 힙 상한은 144MiB(ADR 0040). 상한이 바뀌었으면 `/etc/systemd/system/trova-backend.service.d/gc.conf` 확인 |
| **다른 프로세스** | `ps aux --sort=-rss \| head` | 같은 VM에 safeKor 수집기 크론이 있다(그 비밀 파일은 열지 않는다). 알 수 없는 프로세스면 기록 후 사용자에게 |
| **부하 측정 중** | 테스트 계정·`zz-loadtest.conf` 있음 | 측정 전에 사일런스를 건다 |

서버에서 한 번에 보기:
```
ssh -i ~/.ssh/oracle_buildlog_vm ubuntu@193.123.161.217 'free -m; cat /proc/pressure/memory; grep -E "pswpin|pswpout" /proc/vmstat; ps aux --sort=-rss | head -6 | cut -c1-150'
```

## 4. 복구

- 분석이 겹친 경우: 기다린다. 10분 넘게 내려오지 않고 p95·5xx 경보까지 울리면 [high-http-5xx.md](high-http-5xx.md) 5절(재시작은 사용자 승인 뒤).
- 재시작하면 java 메모리가 처음부터 다시 쌓이므로 압박은 잠시 풀리지만, 원인(분석 겹침·힙 확장)이 남아 있으면 다시 온다.

## 5. 정상화 확인

- [ ] PSI 메모리 full 5분 평균이 20% 아래로 내려오고 Slack에 RESOLVED가 왔다.
- [ ] 스왑 읽기가 초당 수십 이하로 내려왔다.
- [ ] p95가 평소(1초 안팎)로 돌아왔다.

## 6. 사후 분석

`docs/monitoring/2026-11-04-capacity-issue-log.md`에 한 절. 특히 이 경보가 p95 경보보다 **얼마나 먼저** 울렸는지 적는다 — 조기 경보로서 쓸모를 판단하는 근거다(2026-11-11 기준 실제 장애로는 아직 확인 안 함).
