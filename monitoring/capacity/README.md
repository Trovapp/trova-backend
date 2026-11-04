# 격리 환경 동시 장소 조회

운영 부하 테스트가 아니다. 기존 8080/9090/3300 서버는 건드리지 않는다.
Docker, Java 21, Python 3, k6 필요. 프로젝트 루트에서:

```sh
./gradlew bootJar
docker compose -p trova-capacity-307 -f monitoring/capacity/compose.yml up -d
CAPACITY_OUT=/tmp/trova-capacity-results python3 monitoring/capacity/run.py
docker compose -p trova-capacity-307 -f monitoring/capacity/compose.yml down -v
```

각 재실행 전 반드시 `down -v` 후 새 DB로 시작한다. runner는 seed 중복 시 중단한다.
앱과 DB는 internal network에만 연결하고 nginx로 loopback 요청만 전달한다.
실제 환경 변수/운영 .env를 읽지 않는다. 고정된 더미 비밀키는 이 격리 실험 전용이다.
JWT는 임시 디렉터리에 생성하며 완료 후 삭제한다. 실제 사용자 데이터는 없다.

앱 1 CPU / 768 MiB / Xmx384m, DB 1 CPU / 512 MiB. 160명의 사용자와
사용자별 1,000개 장소(100개 영상)를 준비한다. IP rate limit만 테스트에서 끈다.
로그인된 서로 다른 VU가 GET /api/places를 요청하고 응답 후 1초 쉰다.
이는 한 순간의 동시 in-flight 요청 수나 가입자 수가 아니라 폐쇄형 가상 사용자 수다.

각 단계 45초, 각 VU의 첫 15초를 지연/오류 평가에서 제외한다.
전체 요청 수 및 HTTP 지표에는 워밍업이 포함된다. 측정 요청 수/30은 대략적인
정상 구간 처리량이며 정확한 처리량은 Prometheus rate 시계열로 해석한다.
p95 < 1초 및 오류 < 1%는 실험 목표이지 운영 SLO가 아니다.
최초 실패 단계에서 상승을 중단하고 직전/실패 단계를 반복한 뒤 1 VU로 회복을 본다.
최고 단계까지 통과하면 상한을 찾은 것으로 표현하지 않는다.

`runs.json`: 각 단계 시간·평가값·cgroup CPU 전후 누적값.
`step-*.json`: k6 전체 요약. `prom-*.json`: 2초 간격 실제 Prometheus 시계열.
성공은 HTTP 200뿐 아니라 장소 수와 사용자별 접두어가 모두 맞아야 한다.
컨테이너 CPU throttling은 cgroup 누적값 차이로 계산한다.

## 인덱스 개선 전후 비교 (ABBA)

새 컨테이너/DB에서 같은 `bootJar`, `compose up` 이후 아래를 실행한다.

```sh
CAPACITY_MODE=index-abba CAPACITY_OUT=/tmp/trova-index-abba python3 monitoring/capacity/run.py
python3 monitoring/capacity/summarize.py /tmp/trova-index-abba
# matplotlib 설치 환경에서 그래프 생성
python3 monitoring/capacity/plot.py /tmp/trova-index-abba
```

동일 JVM·데이터에서 인덱스 없음(A1), 있음(B1), 있음(B2), 없음(A2) 순으로
20/40 VU를 각각 45초 실행한다. 먼저 1 VU 예열, 마지막 1 VU 회복을 수행한다.
블록마다 실행 계획과 40개 계정의 전체 응답 해시 동등성 검사 결과를 보존한다.
복합 인덱스 이외의 쿼리·데이터·자원 제한은 바꾸지 않는다.
첫 1 VU 예열은 JPA가 생성한 인덱스를 사용하며, ABBA 비교 표본에서 제외한다.
이것은 동일 VU 부하 비교이지 고정된 요청 도착률 비교가 아니다.
원본 결과 디렉터리는 재사용하지 말고 새 경로를 지정한다.

실험 결과는 `docs/monitoring/2026-11-02-index-abba.md`에 기록한다.
Grafana 대시보드 JSON은 결과 디렉터리에 저장하며, 원본 Prometheus를 제거하기 전에
고정 시간 범위의 화면과 시계열을 먼저 보존한다.
