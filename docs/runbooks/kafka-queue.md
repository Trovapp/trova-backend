# Runbook: Kafka 대기열 (TrovaKafkaConsumerLag · TrovaKafkaDeadLetter)

Slack 알림의 "Runbook" 링크가 이 문서다. 운영에 영향을 주는 작업(재시작·설정 변경)은 **원인을 확인한 뒤 담당자(사용자) 승인을 받고** 한다.

- 대시보드: Grafana "Trova 필수 모니터링" 맨 아래 "대기열 — Kafka" 줄 — https://indigostork3365.grafana.net/d/trova-essentials
- 서버 접속: `ssh -i ~/.ssh/oracle_buildlog_vm ubuntu@193.123.161.217` (systemd `trova-backend`)
- 브로커: Aiven 무료 Kafka `kafka-trovapp`(토픽 5개 중 4개 사용·토픽당 파티션 2·125KiB/s). 콘솔은 사용자 계정
- 관련 결정: ADR 0021(대기열 구조·넘기기, 자동 초안 확장 #390, 실패 작업 토픽 #394)

## 1. 장애 개요

| 경보 | 심각도 | 조건 | 뜻 |
|---|---|---|---|
| `TrovaKafkaConsumerLag` | warning | 토픽별 처리 못 한 메시지 합 > **3개**가 **15분** | 들어오는 작업을 처리가 못 따라가거나, 리스너가 한 작업에 멈춤 |
| `TrovaKafkaDeadLetter` | warning | 최근 15분 실패 작업 토픽으로 보낸 메시지 > **0** (바로) | 리스너가 메시지를 처리하지 못함(DB 오류·잘못된 메시지) |

| 토픽 | 그룹 | 동시 처리 | 무엇 |
|---|---|---|---|
| `trova.share-jobs` | `trova-share-jobs` | 2 | 공유 → 영상 분석 |
| `trova.draft-jobs` | `trova-draft-jobs` | 1 | 일정 초안(분석 뒤 자동 + 사용자가 직접 만든 것) |
| `trova.replan-jobs` | `trova-replan-jobs` | 1 | 일정 재구성(비 예보 등으로 장소 바꾸기, #396) |
| `trova.dead-letters` | (읽는 리스너 없음) | – | 위 두 리스너가 처리하지 못한 메시지. 헤더에 원래 토픽·예외 종류·메시지 |

```promql
# 소비 지연 — 같은 값이 토픽 이름 trova.x와 trova_x 두 표기로 나와서 점 표기만 쓴다
sum by (topic) (kafka_consumer_fetch_manager_records_lag{job="trova-prod", topic=~"trova\\.[a-z-]+"})
# 실패 작업 — 서버가 뜰 때 토픽별로 0을 미리 만들어 두어 첫 1건도 increase가 센다
sum by (topic) (increase(trova_queue_dead_letter_total{job="trova-prod"}[15m]))
# 멈춘 작업 다시 보내기(ADR 0043) — 저장됐지만 대기열에 없던 작업. 0이 아니면 그 시각 전후 재시작·브로커 장애를 본다(대시보드 "넘김·실패 작업·다시 보냄" 패널)
sum by (queue) (increase(trova_queue_requeued_total{job="trova-prod"}[1h]))
```

**왜 3개·15분인가:** 공유는 사용자당 최근 7일 10건이고(#431, 이전 하루 10건·20건), 분석은 한 건에 30초~5분(동시 2), 초안은 6~20초(동시 1)다.
평소에는 쌓여도 몇 분 안에 0으로 돌아온다(2026-11 부하 측정에서 릴스 5개를 한꺼번에 넣었을 때 약 11분 만에 비었다, r26).
15분 넘게 3개 넘게 남아 있으면 처리가 멈췄거나 계속 밀리는 것이다.

**알아 둘 점**
- 소비 지연 값은 리스너가 메시지를 가져갈 때 갱신된다. 리스너가 한 작업에 멈춰 있으면 값도 그때 값에 멈춘다.
  한 작업이 15분(`max.poll.interval.ms`)을 넘으면 소비자가 그룹에서 빠지고 메시지를 다시 받는다(분석·초안은 끝난 작업을 건너뛴다).
- 서버가 아예 죽으면 이 지표가 나오지 않아 이 경보는 조용하다. 그때는 헬스 체크(`trova-health`)·지표 끊김 경보가 울린다.
- 브로커가 꺼지면 새 작업은 서버 안 대기열로 넘어간다(대시보드 "넘김" 선). 사용자는 차이를 모른다.

## 2. 최초 확인 (5분 안에)

1. 대시보드 "대기열 — Kafka" 줄: 어느 토픽이 쌓였나, "넘김"이 같이 늘었나(브로커 문제), "실패 작업"이 있나.
2. 처리 중인 작업이 있나(운영 DB는 읽기 전용 도구로만):
   ```
   echo "select status, current_stage, count(*), min(updated_at) from processing_jobs where status in ('PENDING','PROCESSING') group by 1,2;
   select status, count(*), min(updated_at) from trip_drafts where status in ('PENDING','PROCESSING') group by 1;
   select status, count(*), min(updated_at) from trip_replan_jobs where status in ('PENDING','PROCESSING') group by 1;" \
     | PROD_DB_ENV_FILE=<운영 DB 환경 파일> python3 monitoring/prod_readonly_psql.py
   ```
3. 리스너 로그:
   ```
   ssh -i ~/.ssh/oracle_buildlog_vm ubuntu@193.123.161.217 'sudo journalctl -u trova-backend --since "-30min" --no-pager | grep -E "실패 작업 토픽|Kafka에 넣지 못해|partitions (assigned|revoked)|파이프라인 시작|초안 완료|중복" | tail -40'
   ```

## 3. 원인별 확인

| 원인 | 보이는 것 | 복구 방향 |
|---|---|---|
| **1. 작업이 몰림** | 공유가 한꺼번에 많이 들어옴, 처리 중 작업이 계속 바뀜, 지연이 줄어드는 중 | 기다린다. 처리량 상한(분석 동시 2)은 서버 메모리 때문(ADR 0040) |
| **2. 한 작업이 멈춤** | 같은 작업이 오래 PROCESSING, "파이프라인 시작" 뒤 로그가 없음 | Python 자식 프로세스·Gemini 응답 대기 확인(`ps -o pid,etime,args -C python3`). 8분 넘게 갱신이 없으면 한 번 다시 보내고(StuckJobRequeueService), 15분 뒤 자동 정리(OrphanedJobRecoveryService) |
| **3. 리스너가 그룹에서 빠짐** | `partitions revoked`가 반복, 지연이 줄지 않음 | 서버 재시작 검토(사용자 승인). 처리 중이던 메시지는 다시 받는다 |
| **4. 브로커 문제** | "Kafka에 넣지 못해" 로그, 넘김 증가 | Aiven 콘솔에서 서비스 상태 확인. 무료 플랜은 꺼졌다 켜지면 토픽 데이터가 빌 수 있다(ADR 0021) |
| **5. 실패 작업(DLT)** | "실패 작업 토픽으로" 로그에 topic·value(작업 id)·예외 | 대부분 DB 연결 문제. 아래 4절로 원래 작업 상태를 본다 |

## 4. 복구

**순서: 원인 확인 → 사용자 승인 → 실행 → 5절 확인.** 운영을 바꾸는 명령은 사용자가 직접 실행한다.

- **실패 작업 토픽의 메시지를 읽어 다시 돌리지는 않는다** — 분석·초안을 다시 돌리면 Gemini를 또 부른다. 다만 그 작업 행이 PENDING(3분)·PROCESSING(8분)으로 남아 있으면
  `StuckJobRequeueService`가 작업당 한 번 다시 보낸다(ADR 0043, 로그 "멈춘 작업 다시 보냄").
  값(작업 id 또는 초안 id)과 원래 토픽은 로그 "실패 작업 토픽으로" 줄에 있다. 그 작업은 15분 뒤 FAILED로 정리되고,
  사용자는 앱에서 다시 시도할 수 있다. 관리자가 영상 분석을 다시 돌리려면 관리자 재시도 API를 쓴다(ADR 0026).
- **재시작**(약 2분 앱 멈춤): 처리 중이던 메시지는 커밋 전이라 다시 받는다(분석: #244 확인, 초안: 2026-11-12 운영 SIGKILL 실험).
  ```
  ssh -i ~/.ssh/oracle_buildlog_vm ubuntu@193.123.161.217 'sudo systemctl restart trova-backend'
  ```
- **Kafka를 잠시 빼기**: 브로커가 오래 불안정하면 `APP_QUEUE_TYPE=async`로 바꿔 서버 안 대기열만 쓴다(운영 `.env`, 재시작 필요). 되돌릴 때 다시 `kafka`.

## 5. 정상화 확인

- [ ] 대시보드 "처리 못 한 메시지"가 0으로 돌아왔고 Slack에 RESOLVED가 왔다.
- [ ] 처리 중 작업(2절 SQL)이 0이거나 줄어든다.
- [ ] 리스너 로그에 `partitions assigned: [trova.share-jobs-…]`, `[trova.draft-jobs-0, trova.draft-jobs-1]`가 있다.
- [ ] 공유 1건이 분석 → 초안까지 끝난다(로그 "DONE", "초안 완료").

## 여행 SSE 배포

`APP_TRIP_EVENTS_TYPE=memory`가 기본이다. Kafka를 켤 때는 `APP_TRIP_EVENTS_TYPE=kafka`, 기존 `KAFKA_BOOTSTRAP_SERVERS`와 Spring Kafka SSL 설정을 사용한다. 기존 무료 브로커에서 `trova.trip-events` 토픽 한 개(파티션 1, 기존 복제 수)를 생성할 여유와 읽기·쓰기·그룹 권한을 확인한다. 프로세스별 소비자 그룹은 자동 생성된다. 로컬 전달로 전환되면 `trova_trip_events_fallback_total`이 증가한다.

Caddy의 API reverse_proxy가 `text/event-stream`을 받으면 매 쓰기 직후 전달한다. SSE 경로에 별도 응답 버퍼를 두지 말고, 배포 후 테스트 계정으로 프록시를 거쳐 연결해 ready와 25초 간격의 heartbeat를 확인한다. 접근 로그에서 ticket 쿼리를 가린다. [Caddy 스트리밍 문서](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy#streaming)

## 6. 사후 분석

`docs/monitoring/2026-11-04-capacity-issue-log.md`에 한 절로 남긴다: 감지·해결 시각, 원인(추정과 확인 구분), 영향받은 작업 id,
실제로 한 복구, 경보 기준(3개·15분)이 너무 늦거나 시끄럽지 않았는지.
