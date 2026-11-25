# 운영 서버 증거 (매니저 Mac에서 수집, 읽기만)

수집: 2026-11-25 12:34:52~ KST, 30초 간격으로 운영 서버 `127.0.0.1:8080/actuator/prometheus` 전체를 SSH로 읽어 gzip 저장. 비밀값·토큰·SSH 키는 담지 않았다.

## 1. 시간창별 전/후 스냅샷 — `prom/`, `snapshots.json`
- 창 시작 이전의 마지막 스냅샷, 창 끝(+1초) 이후의 첫 스냅샷을 골랐다. 파일 이름의 시각은 수집을 시작한 KST 시각(응답까지 1초 안팎).
- 겹침 주의: 말로 바꾸기 before(13:00:25)는 대안 찾기 끝(13:00:44)보다 앞이라 대안 마지막 몇 건이 섞일 수 있다. 오류 before(14:09:41)도 빈 시간 끝(14:09:56)과 겹친다. URI 라벨로 분리하면 된다.
- 프로세스 재시작 없음: `trova-backend` ActiveEnterTimestamp = 2026-11-24 22:39:29 KST(#526 배포). 모든 창에서 카운터 리셋 없다.

## 2. 단계 로그 — `journal-steps.log`
`journalctl -u trova-backend` 2026-11-25 12:38~14:11 KST 전체 235줄. URL·이메일·token/ticket/key 값은 `<url>`·`<email>`·`<redacted>`, `user=<숫자>`는 `user=<u>`로 가렸다. 1열은 journal UTC 시각, 2열은 앱 로그 KST 시각.
- 영상 5건: `ProcessingJob 59xx` 단계 로그(대기·gemini·지오코딩·selecting·saving).
- 대안: `대안 찾기 구간별 시간` 60줄(창 밖 포함 여부는 시각으로 거른다), `장소 임베딩 묶음` 5줄.
- 오류: `ApiErrors status=… traceId=…` 74줄.

## 3. traceId 대조
error-observations.jsonl의 traceId 4건이 모두 journal에 있다(401×2 `1306b7a7…`·`12b796a2…`, 404×2 `bfe22e57…`·`b2b1e587…`). 상태 코드도 일치.

**측정 중 429 응답 70건**(journal, 앱이 일부러 만든 오류 아님) — prometheus 기준(14:10:47 누적):
| URI | 429 count |
|---|---|
| GET `/api/trips/{id}/events` (SSE) | 51 |
| GET `/api/trips/{tripId}/days/{day}/gap-recommendations` | 19 |
시각 분포: 13:24~13:30(상세), 13:43, 13:51~13:57(같이 고치기), 14:01~14:09(빈 시간). SSE 재연결·빈 시간 추천 한도에 걸린 것으로 보인다 — 원인 확인과 개선안 대상.

## 4. 운영 빌드 — 해시
| 파일 | sha256 | 수정 시각(UTC) |
|---|---|---|
| `/opt/trova-backend/app.jar` | `74da2b68a0c62e025e0e536544cc8ae45c143ab9058e525d74c077118db209ad` | 2026-11-24 13:39:22 (#526 배포) |
| `pipeline-test/run_pipeline.py` | `d80ae71f4af3639cbb5ce9d60a07f685394f026bbd23b9d4f3f5ab2ecc47719e` | 2026-11-12 12:42:59 |
| `pipeline-test/extract_places.py` | `c69c897199f247e8a3f6317a62b0c667088479335ea473bab6754681776713f2` | 2026-11-04 02:03:24 |

레포 main(ef5bbb3, #518 포함) 해시: run_pipeline.py `4795ca07…1c46`, extract_places.py `605a56c9…7990`.
**운영 Python은 #518 버전이 아니다.** 운영 두 파일은 `pipeline-test.bak-20261124-154243`(#518 이전 백업)과 해시가 같다 — 11-24 복구 이후 #518의 Python 변경(제목 조회·Gemini 동시 시작 등)은 운영에 없다. JAR 쪽(#518 Java 변경)은 #526 JAR에 포함. JAR 안에 build-info/git.properties는 없다.

## 5. 경보 규칙 — 운영 Grafana(읽기만, `/api/prometheus/grafana/api/v1/rules`)
Trova 폴더 규칙 7개, 14:3x KST 조회 시 모두 `health=ok`, `state=inactive`(울리지 않음), 라벨 `environment=production, service=trova-backend, severity=warning|critical`.
| 그룹 | 규칙 | 저장소 정의와 쿼리 |
|---|---|---|
| trova-http | HighHttp5xxErrorRate (critical) | 같음 |
| trova-http | HighHttpLatencyP95 (warning) | 같음(`/api/shares` 제외) |
| trova-kafka | TrovaKafkaConsumerLag | 같음(`topic=~"trova\\.[a-z-]+"`) |
| trova-kafka | TrovaKafkaDeadLetter | 같음 |
| trova-host | HighMemoryPressure | — |
| trova-cloud-availability | 지표 부재(absent process_cpu_usage) | — |
| trova-cloud-logs | error 로그 증가(logback_events_total) | — |

운영 지표 이름·라벨 확인(14:10:47 스냅샷): `http_server_requests_seconds_count/bucket`에 `status`·`uri` 라벨 있음, `kafka_consumer_fetch_manager_records_lag`에 `topic` 라벨 있음(`trova.*`와 `trova_*` 두 형태가 모두 나오지만 규칙 정규식은 점 형태만 잡는다 — 중복 계산 없음), `trova_queue_dead_letter_total` 있음.
**`trova_trip_events_fallback_total`은 운영에 없다.** 있는 것은 `trova_trip_events_deferred_discarded_total`, `trova_trip_events_deferred_size`.
주의: HighHttpLatencyP95는 SSE `/api/trips/{id}/events`를 빼지 않는다. SSE는 30초 연결로 끝나서 버킷 p95가 30초로 붙는다(아래 패널 20·22). 요청 수가 20건을 넘는 5분 동안 SSE 비중이 5%를 넘으면 잘못 울릴 수 있다 — 개선안 후보.

## 6. Grafana 캡처 — `grafana/`(trova-essentials, 12:35~14:12 KST, 라이트 테마)
| 파일 | 패널 | 눈에 띄는 것 |
|---|---|---|
| panel-18-rps.png | 초당 요청 수(상태별) | 13:25~13:33·14:02~14:10에 0.5 req/s 안팎, 429 계열이 함께 보임 |
| panel-20-latency-avg-p95-p99.png | 응답 시간 평균·p95·p99 | 13:23~13:36·14:00~ 30초 고정 = SSE. 13:24 평균 2분은 SSE 연결 종료 시점 |
| panel-22-api-p95.png | API별 p95 | 대화 POST 5.5초대, 대안 GET 2.8→0.7초, 빈 시간 GET 1~2초, `GET REDIRECTION` 9.6초(13:51) |
| panel-19-4xx-5xx.png | 4xx/5xx 비율 | 5xx 0. 4xx 최대 25%(13:47)·20%(13:55~13:58) — 대부분 429 |
| panel-08-cpu.png | CPU | 최대 java 54%(14:10), 평소 5% 미만 |
| panel-13-heap.png | 힙 | 사용 70~136MiB, 상한 144MiB. 12:48 committed 189MiB 순간값 |
| panel-25-analysis-time.png | 영상 분석 시간 | 성공 평균 약 9.4초, 최장 10.7초, extracting 평균 6.9초 |
| panel-27-external-api-calls.png | 외부 API 호출 수(1시간 이동합) | google-places nearby-search-alternative 최대 약 110, gemini conversation-turn 60 |
| panel-33-kafka-lag.png | Kafka 밀린 메시지 | 전 구간 0 |
