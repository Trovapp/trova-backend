# Trova 모니터링 실습

운영 서버 → SSH 터널 → Mac Prometheus(15초 수집) → Grafana 구조다.
운영 서버에 새 프로세스를 추가하지 않는다. Mac/Docker/터널이 꺼지면 수집도 중단된다.
이 구성은 24시간 감시가 아니며, 수집이 끊긴 구간을 나중에 복원하지 못한다.

## 접속과 읽는 순서

- [처음 보는 대시보드](http://localhost:3300/d/trova-monitoring-lab)
- [기존 상세 대시보드](http://localhost:3300/d/trova-overview)
- [수집 상태](http://localhost:9090/targets), [경보 상태](http://localhost:9090/alerts)

1. 수집 상태가 1인지 본다. 0/데이터 없음이면 먼저 터널·Docker·서버를 확인한다.
2. 휴대폰에서 영상 한 건을 제출하고 시작/완료 시각을 적는다. 외부 API·공유 한도를 먼저 확인한다.
3. Grafana 시간 범위를 실행 전후로 맞춘다. 단계별 평균과 분석 시작 전 대기를 따로 읽는다.
4. 정확한 한 건의 전체 처리 시간과 성공 여부는 DB/로그와 대조한다.

새 화면은 수집 중단을 업무 지표 0으로 채우지 않는다. 요청이 없는 구간이나 아직 생성되지 않은
시계열은 데이터 없음으로 보인다. 기존 상세 화면 일부는 0 폴백이 있으니 새 화면의 수집 상태를 먼저 확인한다.

| 지표 | 실제 범위 / 한계 |
|---|---|
| HTTP 평균 시간 | 요청 접수 응답. 비동기 분석 완료 시간 아님. 현재 HTTP 히스토그램이 없어 p95 계산 불가 |
| pipeline stage | download/audio/frames/gemini. Python 결과 반환 뒤 기록하므로 실패·강제종료 전체를 포함하지 않음 |
| queue wait | DB 작업 생성 → 분석 시작. Kafka/내부 전달 경로별 측정, DB 확인 포함 |
| 외부 API 시간 | 제공자·작업별 호출 기록. 병렬 호출 합은 사용자 대기 시간과 다름 |
| executor queued | JVM 실행기 대기 작업. Kafka 메시지 적체량 아님 |
| JVM 힙 / process CPU | Java 메모리/CPU. 서버 전체 메모리와 Python·ffmpeg 자원은 별도 |

`increase()`는 구간 경계를 보정해서 소수가 될 수 있고, 최초 계측 이전 표본이 없으면 첫 호출을 놓칠 수 있다.
표본 1~몇 건으로 p95 개선률이나 처리량을 주장하지 않는다. 새 Java 단계 지표는 지도 검색·선택·검증·저장과 예외 종료까지 계측한다.
자동 일정 생성 및 강제 종료된 분석은 별도 관찰이 필요하다. 사용자 ID/URL/job ID를 지표 라벨에 추가하지 않는다.

## 실행

```bash
docker compose -f docker-compose.monitoring.yml up -d
TROVA_SSH_HOST=ubuntu@서버주소 TROVA_SSH_KEY=비밀키경로 bash monitoring/tunnel.sh
```

터널 스크립트는 포그라운드에서 실행하며 Ctrl-C로 종료한다. 이미 18080 포트에 터널이 있으면
새 연결은 실패한다. 기존 연결의 소유자를 확인하고 중복 실행하지 않는다. 자동 재연결은 아직 없다.
로컬 개발 서버 대상이 404여도 운영 대상 `trova-prod`가 UP이면 운영 수집은 정상이다.
개발 서버를 관찰할 때만 해당 서버 소유 세션에서 `ACTUATOR_EXPOSE=health,prometheus`를 적용한다.

## 데이터 보존과 전환

- 고정 Compose 프로젝트: `trova-monitoring`.
- 고정 볼륨: `trova-prometheus-data`, `trova-grafana-data`.
- Prometheus 보관: 30일 또는 1GB 제한 중 먼저 도달한 조건. WAL/head 등으로 실제 디스크는 1GB를 넘을 수 있다.
- Grafana DB도 볼륨에 저장한다. `docker compose down -v`는 데이터 삭제이므로 사용하지 않는다.
- 9090/3300은 127.0.0.1 전용이다. Grafana 익명 Admin 설정을 외부에 공개하지 않는다.

기존 컨테이너가 있으면 바로 `up`으로 덮지 않는다. 실제 mount와 프로젝트를 inspect하고,
Grafana 대시보드 API 응답을 백업한 뒤 두 컨테이너를 정상 정지한다. Prometheus `/prometheus`의
전체 파일(WAL 포함)과 Grafana `/var/lib/grafana`를 새 볼륨으로 복사한다. 이미지 기준 소유권은
Prometheus 65534:65534, Grafana 472:0이다. 기존 컨테이너는 이름을 바꿔 정지 상태로 남긴다.
새 컨테이너 시작 후 과거 시계열, 기존 패널, 운영 UP을 비교한다. 기존/새 Prometheus가 같은
데이터 볼륨을 동시에 열어서는 안 된다.

이번 전환의 원본 컨테이너는 `trova-prometheus-backup-305`, `trova-grafana-backup-305`로 남겼다.
Grafana 파일과 API 백업은 이 Mac의 `/tmp/trova-monitoring-305-backup`에 있다(임시 백업이며 영구 보관 아님).
되돌릴 때 새 컨테이너를 `docker compose ... down`으로 내린 뒤 원본 이름을 복원하고 시작한다.
원본은 이관 직전 상태이므로 전환 이후 기록은 새 볼륨에만 있다. 다른 워크트리의 예전 Compose로
동일 이름 컨테이너를 시작하지 않는다.

## 경보와 검증

`TrovaProductionScrapeUnavailable`: 운영 수집 실패/대상 누락이 1분 지속되면 발생하고 복구되면 해제한다.
이는 Prometheus의 경보 규칙이며 이메일/메신저 전송은 연결하지 않았다. Prometheus 자체가 꺼지면
평가도 멈춘다. 24시간 감시나 알림 전달 검증을 완료했다고 표현하지 않는다.

```bash
docker compose -f docker-compose.monitoring.yml config --quiet
docker exec trova-prometheus /bin/promtool check config /etc/prometheus/prometheus.yml
docker run --rm --entrypoint /bin/promtool -v "$PWD/monitoring:/work:ro" -w /work prom/prometheus:v3.0.1 test rules alerts.test.yml
```

모의 시계열로 짧은 중단에는 미발생, 1분 지속 시 발생, 복구 시 해제, 대상 누락 시 발생을 검증한다.
운영 서버를 강제로 중단하지 않는다.

## 운영 부하 테스트는 가능한가?

가능하지만 한계까지 밀어붙이는 스트레스 실험과 구분한다. 현재 서버에서 먼저 할 것은 낮은 부하의
관찰 실험이다. 2026-11-01 확인 시 RAM 956MB, available 185MB, swap 사용 312MB였다.
메모리 상태 한 번만으로 안전 용량을 정할 수 없다. 이전 300 VU 헬스체크는 영상 분석 용량의 증거가 아니다.

실행 전 서버/수집 UP, 진행 중 작업, 외부 API·테스트 계정 한도, 다른 세션의 측정/배포 여부를 확인한다.
2026-11-01 기존 측정 문서에는 테스트 계정 19/20건 사용 기록이 있으므로 최신 한도를 다시 확인해야 한다.

1. 인증 없는 헬스체크부터 1 VU, 최대 30건, 최대 60초로 관찰한다. 이것은 연결·기본 응답 확인일 뿐이다.
2. 본인 테스트 계정의 읽기 API로 DB 경로를 관찰한다. URL/응답/데이터 변경 여부를 검토한 뒤 별도 스크립트를 만든다.
3. 영상 분석은 한도 확인 후 한 건씩 시작한다. 동일 입력·버전·캐시·재시작 여부·완료 시간을 기록한다.
4. 동시성 증가는 앞 단계의 영향과 외부 API 예산을 보고 별도 결정한다. 한도 해제나 재시도 무한 반복은 하지 않는다.

초기 중단 기준 제안(실측 SLO 아님): 5xx/타임아웃 발생, p95가 기준의 2배 이상으로 지속,
대기 작업이 실험 종료 후에도 감소하지 않음, available 메모리 100MB 미만 또는 swap 입출력 증가 시 중단.
요청 발생기를 중단해도 이미 접수한 분석은 계속되므로 새 작업을 더 넣지 않고 완료/복구를 관찰한다.
네트워크 지연·에러는 k6 threshold로, 서버 메모리와 대기열은 별도 관찰로 확인해야 한다.

개선 전후에는 같은 서버/요청/동시성/입력/캐시 조건을 맞추고 실패와 느린 표본도 남긴다.
결과에는 표본 수, 관측 기간, p95 또는 중앙값의 의미, 외부 API 변동, 수집 공백을 명시한다.

참고: [k6 중단 임계값](https://grafana.com/docs/k6/latest/using-k6/thresholds/),
[기존 운영 비교의 한계](../docs/bench/plain-performance-guide-20261101.md).

### 준비된 헬스체크 스크립트

`health-smoke.js`는 운영 주소를 고정한 읽기 관찰 스크립트다. 기본 GET `/actuator/health`는 인증 없이,
`MODE=places`는 단기 테스트 토큰으로 GET `/api/places`만 호출한다. 영상 분석이나 저장 API는 호출하지 않는다.
기본 동시 1명/최대 30건/60초, 요청 사이 1초 대기,
요청 타임아웃 3초다. 외부 API 무료 한도를 소비하는 실험이 아니다.

- health는 200 + JSON `status=UP`, places는 200 + 장소 목록 형태가 아니면 즉시 중단. 리다이렉트·재시도 없음.
- 단일 응답 2초 이상이면 중단, p95 1초 이상은 10초 이후 threshold 평가에서 중단.
- 설정된 전체 반복을 끝내지 못하면 실패로 기록한다. 중단 결과를 정상 완료로 표현하지 않는다.
- CLI로 동시성/반복 수를 덮어쓰면 시작 전에 거부한다. 요청 수는 1~30만 허용한다.
- 메모리/스왑/작업 적체는 이 스크립트가 자동 감시하지 않는다. 별도 관찰자가 중단해야 한다.
- 수치는 실측 SLO가 아닌 초기 중단 기준이다. 이 테스트로 최대 처리량을 주장하지 않는다.

아래는 **향후 운영 실행용**이다. 실행 직전에 서버/수집 상태, 진행 작업, 다른 측정 여부를 확인한다.
2026-11-02에는 자원 감시 실행기를 통해 실행했고 결과는 아래 기록에 남겼다.

```bash
# 결과 파일은 매 실행마다 새 경로로 지정한다. 실행 전/후 시각도 기록한다.
TARGET=production RUN_PRODUCTION=YES REQUESTS=30 \
  k6 run --summary-export /tmp/trova-health-smoke-새실행명.json monitoring/health-smoke.js
```

로컬 fixture만 사용하는 검증:

```bash
python3 -m unittest discover -s monitoring -p 'test_health_smoke.py' -v
```

실제 k6로 성공 시 요청 상한, 500/200 DOWN/잘못된 JSON/302에서 한 번 후 중단,
느린 응답 중단, 잘못된 대상·상한 초과·CLI 부하 덮어쓰기 시 네트워크 요청 0건을 확인한다.
단일 영상 관찰과 이 스크립트 실행은 시간을 분리해 지표가 섞이지 않게 한다.

### 자원 감시를 포함한 실행과 실제 결과

2026-11-02 관찰에서 스왑 읽기 한 페이지만 늘어도 중단하던 초기 기준은 무부하 상태에도 반응했다.
이후 실행에는 여유 메모리 100MiB 미만, 새 swap-out, swap-in 속도 1MiB/s 이상,
memory PSI some avg10 1% 이상을 임시 중단 기준으로 사용한다. HTTP 제한은 그대로다.
이는 서비스 SLO나 입증된 안전 용량이 아니다. 운영 수집이나 SSH 관찰이 실패해도 중단한다.

```bash
python3 monitoring/run_observed_smoke.py --run-production --mode health \
  --host ubuntu@서버주소 --ssh-key 비밀키경로 --output /tmp/새실행명
# places 모드에는 --token-file 비공개단기테스트토큰파일 이 추가로 필요하다.
```

출력 디렉터리가 이미 있으면 덮어쓰지 않는다. 토큰 발급/계정 선택은 실행기가 하지 않는다.
원시 k6 로그에 토큰이나 응답 본문을 추가하지 않는다. 실험 후 임시 토큰 파일을 정리한다.

[실제 결과·그래프 해석](../docs/monitoring/2026-11-02-read-smoke.md): health 30건 완료,
places 18건에서 자원 기준으로 중단. 특정 앱 코드 병목은 미확정이며 성능 개선률을 주장하지 않는다.
[실험용 Grafana](http://localhost:3300/d/trova-read-smoke)는 기존 대시보드가 제외하던 health 요청도 표시한다.

[시뮬레이터 실제 화면 관찰·그래프 해석](../docs/monitoring/2026-11-02-simulator.md):
영상 목록·지도·일정 초안·여행·찜·마이페이지 조회, 정상 분기의 404와 출처 미확인 401 구분,
수집 SSH 연결 복구 및 유휴 요청 비교. 앞선 k6 실험과 계정·데이터가 달라 개선율로 비교하지 않는다.

[장소 목록 코드 개선·로컬 반복 비교](../docs/monitoring/2026-11-02-place-list-performance.md):
필요한 필드만 조회하고 같은 작업의 URL 파싱을 공유한다. 합성 장소 1,000개 조건에서
조회·DTO 생성 중앙값 약 68~69%, 스레드 할당량 약 64~65% 감소. 운영 응답 시간 개선율은 아니다.

후속 [PostgreSQL HTTP 비교](../docs/monitoring/2026-11-02-postgres-http.md)는 실제 인증·직렬화를 포함해
중앙값 49~52% 감소를 확인했다. [운영 배포·앱 재검증](../docs/monitoring/2026-11-02-place-list-release.md)도
완료했으며, 재시작 직후 느려진 표본은 별도로 보존하고 운영 개선율을 주장하지 않는다.


### Java 단계별 계측 (ADR 0033)

화면 ⑤는 준비/추출/지도 검색/후보 선택/검증/저장을 나눠 보여주고, ⑥은 분석 실행 평균을 보여준다.
각 단계의 상태 DB 갱신 시간도 포함한다. 전체 실행은 분석 선점 커밋 뒤 시작해 저장 커밋/실패 시점에
끝나며 큐 대기·선점 DB 처리·실패 상태 복구·자동 초안은 제외한다. 사용자에게 보이는 전체 대기 시간은 아니다.
기존 Python 단계는 Java extracting 안에 들어가므로 두 그래프의 시간을 더하지 않는다.

성공한 단계 평균에는 다음 단계에서 실패한 작업도 포함될 수 있다. 실패 단계 평균과 실행 결과별 증가량을
함께 본다. no_places는 장소 없음, discarded는 저장 전에 이미 종료된 작업이다. 중복 작업 건너뛰기는 세지 않는다.
선택/검증할 후보가 없으면 외부 호출 없이 빠르게 끝날 수 있다. Timer 28개(단계 6 × 결과 4 + 전체 결과 4)를
미리 등록하므로 첫 수집부터 0 표본이 나오지만 첫 수집 전에 완료된 작업은 증가량에서 놓칠 수 있다.
히스토그램/p95는 추가하지 않았다. 배포 후 새 분석이 없으면 평균 시간은 계속 데이터 없음이다.
