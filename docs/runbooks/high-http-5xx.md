# Runbook: HTTP 5xx 오류율 증가 (HighHttp5xxErrorRate)

Slack 알림의 "Runbook" 링크가 이 문서다. 위에서부터 순서대로 따라간다. 운영에 영향을 주는 작업(재시작·롤백·설정 변경)은
**원인을 확인한 뒤 담당자(사용자) 승인을 받고** 한다.

- 대시보드: Grafana "Trova 필수 모니터링" — https://indigostork3365.grafana.net/d/trova-essentials
- 서버 접속: `ssh -i ~/.ssh/oracle_buildlog_vm ubuntu@193.123.161.217` (Oracle E2.1.Micro, 메모리 956MiB, systemd `trova-backend`)
- 관련 결정: ADR 0037(GC·스왑), ADR 0039(지표 직접 전송·외부 헬스 체크), ADR 0040(힙 상한 144MiB)

## 1. 장애 개요

| 항목 | 내용 |
|---|---|
| 경보 이름 | `HighHttp5xxErrorRate` (Grafana Cloud 관리 경보) |
| 심각도 | critical (Slack 반복 알림 1시간 간격, 해제되면 RESOLVED) |
| 서비스 | trova-backend (운영, job=`trova-prod`) |
| 조건 | 최근 **5분** 동안 `/actuator`를 뺀 전체 요청 중 5xx 비율 > **10%**, 그 상태가 **2분** 연속 유지 |
| 최소 트래픽 | 5분 요청 20건 미만이면 평가하지 않음(앱 한 번 열면 API 약 9개). 요청이 없거나 지표가 없으면 정상으로 처리 |
| 사용자 영향 | 앱 홈·여행·장소 목록이 실패하거나, 영상 링크 공유가 접수되지 않음 |

"5분"은 비율을 계산하는 범위이고, "2분"은 그 비율이 10%를 넘은 상태가 이어져야 하는 시간이다. 한 번 튄 오류로는 울리지 않는다.

```promql
100 * sum(rate(http_server_requests_seconds_count{job="trova-prod", uri!~"/actuator.*", status=~"5.."}[5m]))
  / sum(rate(http_server_requests_seconds_count{job="trova-prod", uri!~"/actuator.*"}[5m]))
```

**같이 알아 둘 것:** 이 서버에서 부하로 무너질 때는 대개 5xx가 아니라 **응답 지연·시간 초과**로 나타난다(2026-11 부하 측정).
그 경우는 `HighHttpLatencyP95`(warning)가 먼저 울린다. 서버가 아예 죽으면 외부 헬스 체크(`trova-health`)가 울린다.

## 2. 최초 확인 (5분 안에)

대시보드 "Trova 필수 모니터링"에서 위에서 아래로 본다.

1. **한눈에 보기**: 서버 응답(헬스 체크)이 1인가, 5xx 오류율·p95가 언제부터 올랐나.
2. **애플리케이션**: 초당 요청 수 — 요청이 갑자기 늘었나(부하) 그대로인가(코드·외부 문제). "API별 p95"에서 어떤 API인지.
3. **JVM**: 힙 committed가 상한(144MiB) 가까이 붙었나, GC 멈춤이 0.5초를 자주 넘나.
4. **시스템**: CPU·코어당 부하, 서버 여유 메모리·스왑 읽기·PSI·CPU steal. PSI 메모리 full이 20%를 넘으면 [memory-pressure.md](memory-pressure.md)도 본다.
5. **오류 로그·DB 연결**: ERROR 로그가 같이 늘었나, DB 연결 대기가 0이 아닌가.
6. **최근 변경**: 최근 배포·설정 변경이 있었나.
   ```
   ssh -i ~/.ssh/oracle_buildlog_vm ubuntu@193.123.161.217 'ls -lt /opt/trova-backend/ | head; ls -lt /etc/systemd/system/trova-backend.service.d/; systemctl show trova-backend -p ActiveEnterTimestamp -p NRestarts'
   ```

## 3. 로그 분석

로그 본문은 Grafana로 보내지 않는다(서버 메모리 때문에 수집기를 두지 않음, ADR 0039). 서버에서 직접 본다.

```
# 장애 시간대 ERROR와 예외 (시간은 KST가 아니라 서버 시각 기준으로 --since를 준다. 앱 로그는 +09:00으로 찍힌다)
ssh -i ~/.ssh/oracle_buildlog_vm ubuntu@193.123.161.217 'sudo journalctl -u trova-backend --since "-30min" --no-pager | grep -E " ERROR | WARN |Exception" | tail -80'

# 어떤 API·상태 코드인지 (지표 그대로)
ssh -i ~/.ssh/oracle_buildlog_vm ubuntu@193.123.161.217 'curl -s localhost:8080/actuator/prometheus | grep -E "^http_server_requests_seconds_count.*status=\"5" '

# 오류 순간 기록(헬스가 UP이 아니거나 메모리 대기가 높았던 순간 자동 저장)
ssh -i ~/.ssh/oracle_buildlog_vm ubuntu@193.123.161.217 'ls -t /var/log/trova-incident | head; journalctl -t trova-incident --since today --no-pager | tail'
```

요청 추적(트레이스)은 지금 꺼 두었다. 같은 요청의 로그를 묶어 보려면 시각·API 경로·사용자 ID로 찾는다.

## 4. 원인별 확인

| 원인 | 확인할 지표 | 확인할 로그·명령 | 복구 방향 |
|---|---|---|---|
| **1. DB 연결 실패** | DB 연결 사용 중·대기, 5xx가 모든 API에 고르게 | `HikariPool`, `Connection is not available`, `SQLTransientConnectionException` | Supabase 상태 확인. 연결이 돌아오면 앱은 스스로 회복(재시작 전에 2~3분 기다린다 — 2026-09-30 재시작 직후 DB 연결 대기 후 자연 회복 사례) |
| **2. DB 커넥션 풀 고갈** | DB 연결 대기 > 0이 이어짐, 느린 API의 p95 급증 | 느린 쿼리 로그, `Connection is not available, request timed out` | 원인 쿼리·요청을 찾는다. 운영 DB 읽기는 `monitoring/prod_readonly_psql.py`(SQL은 표준 입력)로만 |
| **3. 외부 API 장애** | 비즈니스 "외부 API 성공률"(Gemini·카카오) 하락, 영상 분석 failure 증가 | `gemini`, `kakao`, `429`, `timeout` | 대부분 영상 분석 실패로 나타나고 조회 5xx는 아니다. Gemini 무료 한도는 로컬·운영이 같이 쓴다(하루 사용량 먼저 확인). 재시도·한도는 코드에 있음(ADR 0020) |
| **4. 애플리케이션 예외** | 특정 API 하나만 5xx, ERROR 로그 증가 | 스택 트레이스의 첫 `com.trova` 줄 | 최근 배포가 원인이면 5번 롤백 검토. 아니면 이슈로 남기고 고친다 |
| **5. 서버 자원 부족** | 힙 committed가 상한에 붙음, GC 멈춤 잦음, CPU 100%·코어당 부하 > 1 | 아래 명령 | 영상 분석과 조회가 겹치는지 본다. 힙 부족 종료(`OutOfMemoryError`)면 자동 재시작(5초 뒤, UP까지 약 2분) |
| **6. 최근 배포** | 배포 시각 직후부터 오류 | `ls -lt /opt/trova-backend/`(백업 `app.jar.bak-*`) | 5번 롤백 검토 |

서버 자원 확인:
```
ssh -i ~/.ssh/oracle_buildlog_vm ubuntu@193.123.161.217 'free -m; cat /proc/pressure/memory; uptime; ps -o pid,rss,etime,args -C java | cut -c1-120; sudo journalctl -u trova-backend --since "-30min" --no-pager | grep -ci OutOfMemory'
```
- 대시보드 "PSI" 그래프(5분 평균)에서 메모리 full이 20%를 넘으면 메모리 대기로 느려지는 중이다(2026-11 측정에서 멀쩡할 때 최대 21%, 무너질 때 26~36%). 서버의 `full avg10`은 최근 10초 값이라 더 크게 출렁인다.
- 같은 VM에 safeKor 수집기가 있다. 그 비밀 파일은 열지 않는다.

## 5. 장애 복구

**순서: 원인 확인 → 사용자 승인 → 실행 → 6절 확인.** 운영을 바꾸는 명령은 사용자가 직접 실행한다.

- **재시작** (약 2분 앱 멈춤, 시작 110초 + 예열). 재시작 전에 진행 중인 영상 분석이 있는지 먼저 본다:
  ```
  echo "select status, count(*) from processing_jobs where status in ('PENDING','PROCESSING') group by status;" | PROD_DB_ENV_FILE=<운영 DB 환경 파일> python3 monitoring/prod_readonly_psql.py
  ssh -i ~/.ssh/oracle_buildlog_vm ubuntu@193.123.161.217 'sudo systemctl restart trova-backend'
  ```
  재시작 동안 지표 끊김·헬스 체크 경보가 같이 울릴 수 있다 → 필요하면 Grafana에서 10분 묵음(Silence)을 먼저 건다.
- **롤백(최근 배포가 원인)**: 배포 도구(devflow deploy)가 남긴 백업으로 되돌린다.
  ```
  ssh -i ~/.ssh/oracle_buildlog_vm ubuntu@193.123.161.217 'ls -t /opt/trova-backend/app.jar.bak-* | head -3'
  ssh -i ~/.ssh/oracle_buildlog_vm ubuntu@193.123.161.217 'sudo cp -p /opt/trova-backend/app.jar.bak-<시각> /opt/trova-backend/app.jar && sudo systemctl restart trova-backend'
  ```
- **설정 롤백**: JVM 옵션은 `/etc/systemd/system/trova-backend.service.d/gc.conf`. 384MiB 시절 원본은 `/opt/trova-backend/gc.conf.bak-384m`.
  drop-in 폴더에는 `.conf`로 끝나는 백업을 두지 않는다(systemd가 같이 읽는다).
- **외부 API 장애**: 기다린다. 한도 초과면 다음 날(태평양 자정)까지 영상 분석이 실패한다 — 사용자 공지 여부를 정한다.
- 해결이 안 되면: 이슈를 만들고, 오류 순간 기록(`/var/log/trova-incident/`)과 대시보드 캡처를 붙인다.

## 6. 정상화 확인

- [ ] 5xx 오류율이 10% 아래로 내려오고 Slack에 **RESOLVED**가 왔다.
- [ ] p95 응답이 평소 수준(1초 안팎)으로 돌아왔다.
- [ ] 서버 응답(헬스 체크)이 1이다: `curl -s https://193-123-161-217.sslip.io/actuator/health`
- [ ] 앱에서 홈 열기, 영상 링크 공유 1건이 끝까지 된다(비즈니스 "영상 분석 결과"에 success가 늘었다).
- [ ] 재시작했다면 예열 로그가 남았다: `journalctl -u trova-backend --since "-10min" | grep 예열`

## 7. 사후 분석

`docs/monitoring/2026-11-04-capacity-issue-log.md`에 한 절로 남긴다(측정·운영 기록과 같은 곳).

- 장애 시간(감지·해결, KST)과 길이
- 원인(추정과 확인을 구분)
- 사용자 영향(실패한 요청 수, 영향받은 기능)
- 실제로 한 복구
- 재발 방지 대책(코드·설정·용량)
- 모니터링·경보 개선(이번에 경보가 너무 늦거나 시끄럽지 않았나, 이 Runbook에 빠진 단계)
