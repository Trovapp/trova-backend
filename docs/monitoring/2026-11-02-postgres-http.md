# 격리 PostgreSQL에서 HTTP 성능 비교

사용자 승인에 따라 로컬 PostgreSQL 17.11 Docker 컨테이너에서 합성 데이터만 사용했다.
포트는 `127.0.0.1:15432`에만 바인딩했다. 테스트는 고정된 `trova_bench` DB를 만들고 종료 시 제거하며
운영 DB URL이나 계정을 읽지 않는다. HTTP 서버도 임의의 loopback 포트에만 열린다.

## 비교 방법

`PlaceListPostgresHttpTest`는 실제 Spring Boot HTTP 서버를 시작한다. JWT 인증, 사용자 조회,
PostgreSQL 쿼리, 필터·DTO 변환, JSON 직렬화, 클라이언트의 전체 응답 수신을 포함한다.
기존 구현을 테스트 전용 `/api/bench/places-legacy`에 두고 개선된 `/api/places`와 번갈아 호출한다.
이 비교 엔드포인트는 test 소스에만 있어 운영 JAR에는 포함되지 않는다.

- Apple M5 / RAM 32GiB / Temurin Java 21.0.9 / Docker PostgreSQL 17.11.
- `postgres:17` image digest: `sha256:3b0cda93be7c8dba2ed484d19393d93845dc3dafb88973e248ba8e6d99d784e1`.
- JDBC `prepareThreshold=0`은 운영 설정과 동일하게 맞췄다.
- 독립 JVM 실행 3회, 데이터 100개·1,000개 조건별로 두 경로 각각 20회 워밍업 + 50회 측정.
- 단일 클라이언트가 순차 호출하고 매 반복에서 먼저 실행할 경로를 교대한다. 동시 부하 용량 시험은 아니다.
- 기존·개선 응답의 전체 JSON 문자열이 매번 같은지 확인한다. 1,000개 응답 크기 447,012 bytes.
- 각 실행의 측정 200건, 3회 총 600건은 전부 HTTP 200, JSON 동등성 검사 통과.
- 데이터 입력·서버 기동·워밍업은 측정에서 제외했다. TLS·인터넷·운영 서버 자원·원격 DB 지연은 포함하지 않는다.

## 결과: 장소 1,000개

| 실행 | 기존 중앙값 | 개선 중앙값 | 기존 p95 | 개선 p95 |
|---|---:|---:|---:|---:|
| 1 | 12.62ms | 6.35ms | 16.60ms | 8.78ms |
| 2 | 12.22ms | 5.92ms | 16.36ms | 9.59ms |
| 3 | 12.61ms | 6.46ms | 18.70ms | 12.26ms |

중앙값은 약 49~52%, p95는 약 34~47% 감소했다. p95는 각 실행의 50개 표본에서 nearest-rank로 계산했다.
운영 응답이 이 비율로 줄었다는 뜻은 아니다. 메모리 할당량 64~65% 감소는 별도 H2 구간 측정의 결과이며,
이번 HTTP 실험에서 새로 측정한 값처럼 합치지 않는다.

100개 조건의 중앙값은 기존 5.21~5.71ms, 개선 3.38~3.47ms였다.
원본의 최대값·각 요청 시간까지 남겼으므로 좋은 실행만 골라 보고하지 않는다.

- [요약 수치](2026-11-02-postgres-http/summary.json)
- [실행 1 CSV](2026-11-02-postgres-http/run-1.csv)
- [실행 2 CSV](2026-11-02-postgres-http/run-2.csv)
- [실행 3 CSV](2026-11-02-postgres-http/run-3.csv)

## 재현

아래 컨테이너와 DB는 이 실험 전용이다. 같은 이름/포트를 다른 작업이 사용한다면 재사용하지 않는다.

```bash
docker run -d --name trova-place-bench-305 --label trova.task=305 \
  -p 127.0.0.1:15432:5432 \
  -e POSTGRES_USER=trova_bench -e POSTGRES_PASSWORD=local-disposable-bench \
  -e POSTGRES_DB=trova_bench postgres:17
docker exec trova-place-bench-305 pg_isready -U trova_bench -d trova_bench
TROVA_POSTGRES_HTTP_BENCH=1 ./gradlew test --tests '*PlaceListPostgresHttpTest' --rerun-tasks
# build/reports/place-list-postgres-http.csv를 실행별로 보관한다.
docker rm -fv trova-place-bench-305
```

운영 배포·시뮬레이터 결과는 별도 배포 보고서에서 확인한다.
