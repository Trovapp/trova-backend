# 오류 순간 기록 (#358, ADR 0039)

alloy를 평소 끈 운영 서버에서, 문제가 난 **그 순간**의 OS·JVM 상태를 파일로 남긴다. 기록만 하고 서비스는 건드리지 않는다.

- 1분마다(systemd 타이머) 헬스(`/actuator/health`, 5초)와 메모리 full 대기(`/proc/pressure/memory` avg10)를 본다.
- 헬스가 UP이 아니거나 full 대기가 10% 이상이면 `/var/log/trova-incident/<UTC 시각>-<이유>/`에 남긴다.
  - `system.txt`: 메모리 대기·CPU·IO 대기, `free -m`, `vmstat 1 5`, 메모리 상위 프로세스, 스왑 사용 상위 프로세스
  - `prometheus.txt.gz`: 그 순간 `/actuator/prometheus` 전체
  - `app.log.gz`: 최근 5분 `trova-backend` 로그
- 같은 문제가 이어지면 10분에 한 번만, 최근 60개만 보관. 한 번 기록에 약 7초, 파일은 수십 KB.
- 운영에서 시험(2026-11-10, 기준 0으로 강제): 3개 파일 생성, jvm 지표 60줄 확인. 평소(기준 미달)에는 아무것도 남기지 않음.

## 설치 (운영 서버, 사용자가 실행)

```
scp -i ~/.ssh/oracle_buildlog_vm monitoring/incident-snapshot/{incident_snapshot.sh,trova-incident-snapshot.service,trova-incident-snapshot.timer} ubuntu@193.123.161.217:/tmp/
ssh -i ~/.ssh/oracle_buildlog_vm ubuntu@193.123.161.217 'sudo install -m 755 /tmp/incident_snapshot.sh /usr/local/bin/trova-incident-snapshot && sudo install -m 644 /tmp/trova-incident-snapshot.{service,timer} /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable --now trova-incident-snapshot.timer && systemctl list-timers trova-incident-snapshot.timer --no-pager'
```

## 보기

```
ssh -i ~/.ssh/oracle_buildlog_vm ubuntu@193.123.161.217 'ls /var/log/trova-incident; journalctl -t trova-incident --since today'
```

기준을 바꾸려면 서비스에 `Environment=MEM_FULL_LIMIT=…`(또는 `COOLDOWN`, `KEEP`)을 더한다.
