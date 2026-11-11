# 0042. 서버 메모리·스왑·PSI·CPU steal은 앱이 /proc에서 읽어 지표 전송에 싣는다

- 날짜: 2026-11-11
- 상태: 제안 — 운영 배포 전후 측정 뒤 확정(아래 "확인할 것")
- 관련: #378, ADR 0039(지표 직접 전송, alloy 끔), ADR 0040(힙 상한 144MiB), 부하 측정 r5~r21(`docs/monitoring/2026-11-08-prod-analysis/`)

## 문제

2026-11 운영 부하 측정에서 조회가 무너지기 전에 먼저 나빠진 신호는 서버 메모리(available 43~66MiB), 스왑 읽기(초당 수백 번),
PSI 메모리 full 대기(20~35%), CPU steal이었다. 이 값들은 Grafana에 없다 — alloy를 쓸 때도 노드 지표는 수집하지 않았고(`monitoring/cloud/config.alloy`),
지금은 앱 JVM 지표만 OTLP로 보낸다(ADR 0039). 그래서 장애 때는 서버에 들어가 `free -m`을 쳐야 알 수 있고, 경보는 응답이 이미 느려진 뒤(p95)에야 울린다.

## 해결안

| 해결안 | 상주 메모리(추정) | 판단 |
|---|---|---|
| **A. 앱이 /proc를 읽어 Micrometer 지표로 (고름)** | 0~1MiB | 새 프로세스 없음. 이미 나가는 OTLP 전송에 실린다. JVM이 죽은 순간은 못 보냄 |
| B. 크론 스크립트가 OTLP로 push | 상주 0, 실행 때 5~10MiB | JVM과 따로 돌아 죽기 직전도 잡지만, 메모리가 바닥난 순간 fork가 부담 |
| C. node_exporter | 10~20MiB | 긁어서 보낼 프로세스가 또 필요(ADR 0039와 충돌) |
| D. alloy 다시 켜기 | 약 60MiB(실측) | 조회 오류 32.4% 사례(문제 기록 #16) |

JVM이 죽은 순간은 외부 헬스 체크(Synthetic `trova-health`)와 오류 순간 기록(`monitoring/incident-snapshot/`)이 이미 맡는다.

## 고른 것

`HostProcMetrics`(MeterBinder) — `/proc/meminfo`, `/proc/vmstat`, `/proc/stat`, `/proc/pressure/{memory,cpu}`, `/proc/self/status`.

| 지표(Prometheus 이름) | 뜻 |
|---|---|
| `host_memory_total_bytes`, `host_memory_available_bytes` | 서버 전체·새로 쓸 수 있는 메모리 |
| `host_swap_total_bytes`, `host_swap_used_bytes`, `host_swap_pages_total{direction}` | 스왑 크기·사용량·읽고 쓴 페이지 |
| `host_pressure_avg10_percent{resource,type}`, `host_pressure_stall_seconds_total{resource,type}` | PSI 최근 10초 비율, 누적 멈춤 시간 |
| `host_cpu_time_seconds_total{mode}` | 서버 CPU 시간(steal·iowait·idle 등, USER_HZ=100) |
| `host_process_memory_rss_bytes`, `host_process_memory_swap_bytes` | java 프로세스 RSS·스왑 |

- 시리즈 약 22개 추가(2026-11 기준 약 2,100개, 무료 한도 1만).
- 지표를 읽을 때마다 파일을 다시 읽되 같은 파일은 1초에 한 번만. 파일이 없으면(맥 로컬) 등록하지 않고, 깨지면 NaN.

## 확인할 것 (운영)

- 배포 전후 같은 조건(조회 50명만, 새로 켠 서버, 약 12분)에서 java RSS·서버 available·스왑 읽기·p95 비교. 차이가 측정 흔들림 수준이면 확정.
- Grafana에서 OTLP 변환 뒤 이름이 위 표와 같은지(대시보드·경보 쿼리에 그대로 쓰기 위해).

## 다시 검토할 때

- JVM이 멈춘 순간의 서버 상태가 자주 필요해지면 B(크론 push)를 더한다.
- 서버를 옮기면 USER_HZ·/proc 형식을 다시 확인한다.
