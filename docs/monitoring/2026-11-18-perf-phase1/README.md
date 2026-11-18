# 1단계 성능 작업 기록 — 여행·영상·관리자 조회

기록일: 2026-11-18. P1: [PR #463](https://github.com/Trovapp/trova-backend/pull/463), P2: [PR #465](https://github.com/Trovapp/trova-backend/pull/465), [이슈 #464](https://github.com/Trovapp/trova-backend/issues/464).
아래 SQL 수는 **이 PC의 로컬 H2 실측**이고, Grafana 캡처는 **배포 전 운영 기준선**이다. SQL 감소를 운영 응답 시간 개선으로 환산하지 않는다.
캡처는 P2 배포 전 스냅샷이며, 당시 운영 배포 SHA와 P1의 운영 반영 여부는 확인하지 않았다.

## 배경: 운영 기준선

2026-11-18 17:39~17:40 KST에 Grafana Explore에서 최근 24시간을 조회했다. 기존 Chrome 세션의 **새 창 하나만** 열어 조회·캡처하고 그 창만 닫았다. 다른 창·탭, 서버 셸, 운영 DB에 접근하지 않았고 Grafana 설정을 저장하지 않았다. API 경로는 ID 대신 템플릿으로 집계했다. 캡처에 토큰·개인정보가 없는지 확인했다.

| GET 경로 | 최근 24시간 p95 (초) | 요청 수 추정 (`increase[24h]`) | 상태 |
|---|---:|---:|---|
| `/api/trips/{id}` | 0.444280 | 27.012126 | 200 |
| `/api/places/videos/{jobId}/trip` | 0.532437 | 1.000593 | 200; 404 증가분 0 |
| `/api/trips/{id}/share-link` | 0.337781 | 9.004042 | 404; 200 증가분 0 |

`increase`는 수집 구간 끝을 외삽하므로 정수가 아니다. p95는 상태별로 나누지 않은 히스토그램 추정치이며, 특히 영상 여행 요청 약 1건은 성능 비교 표본으로 부족하다. 작업 요청에는 두 없음 조회의 정상 404가 오류처럼 기록됐다는 운영 관찰이 있었지만, 이번 캡처 구간에서 영상 여행 404의 증가분은 0이었다. 이를 새로 발생한 오류로 세지 않는다.

쿼리(데이터 소스 `grafanacloud-prom`, 화면 시간 범위 `Last 24 hours`, Instant):

```promql
histogram_quantile(0.95, sum by (le, uri) (
  increase(http_server_requests_seconds_bucket{
    job="trova-prod",method="GET",
    uri=~"/api/trips/\\{id\\}(/share-link)?|/api/places/videos/\\{jobId\\}/trip"
  }[24h])
))

sum by (uri, status) (
  increase(http_server_requests_seconds_count{
    job="trova-prod",method="GET",
    uri=~"/api/trips/\\{id\\}(/share-link)?|/api/places/videos/\\{jobId\\}/trip"
  }[24h])
)
```

### 캡처

![세 API의 최근 24시간 p95](grafana-p95-before.png)

![세 API의 최근 24시간 상태별 요청 수](grafana-requests-before.png)

![최근 24시간 서버 여유 메모리·Java RSS·스왑](grafana-memory-before.png)

메모리는 Trova 필수 모니터링의 패널 29(`서버 여유 메모리·java 메모리`)를 캡처했다. 그래프에는 서버 available, Java RSS, 스왑 사용량이 함께 표시된다. 이 작업으로 메모리가 줄었다는 주장은 하지 않는다.

## 작업별 SQL 수 전/후

테스트 트랜잭션 없이 MockMvc HTTP 요청을 예열한 뒤 Hibernate `Statistics.getPrepareStatementCount()`로 요청 전체의 SQL 수를 센다. 영상 작업마다 SavedPlace를 적어도 한 개 넣어 EAGER 추가 조회까지 드러낸다.

| 작업 | 항목 수 | 변경 전 SQL | 변경 후 SQL | 근거 |
|---|---|---|---|---|
| P1 여행 상세 | 일차 1·3·5·7 | 6·10·14·18 | 5·5·5·5 | PR #463, `TripDetailQueryCountTest` |
| P1 관리자 삭제 영향 | 일차 1·3·5·7 | 3·5·7·9 | 3·3·3·3 | PR #463, `TripDetailQueryCountTest` |
| P1 재구성용 장소 조회 자체 | 일차 1·3·5·7 | 미측정 | 1·1·1·1 | PR #463, 전체 재구성 작업 수 아님 |
| P2 같은 영상의 여행 | 작업 1·5 | 8·16 | 7·7 | `VideoAdminShareQueryCountTest` |
| P2 관리자 사용자 상세 | 실패 작업 1·5 | 13·17 | 12·12 | 같은 테스트 |
| P2 공개 공유 여행 | 일차 1·3·5·7 | 6·10·14·18 | 5·5·5·5 | 같은 테스트 |

실행 출력 발췌:

```text
변경 전:
P2 admin jobs=1,5 SQL=[13, 17]
P2 video jobs=1,5 SQL=[8, 16]
P2 public days=1,3,5,7 SQL=[6, 10, 14, 18]
5 tests completed, 3 failed
BUILD FAILED in 1m 33s

변경 후:
P2 admin jobs=1,5 SQL=[12, 12]
P2 video jobs=1,5 SQL=[7, 7]
P2 public days=1,3,5,7 SQL=[5, 5, 5, 5]
BUILD SUCCESSFUL in 1m 6s
```

변경 전 새 SQL 수 테스트 세 개가 실제 실패함을 확인했다. 없음 응답·권한 테스트 추가 후에도 구현 전 실행하여 7개 중 SQL 수 세 개와 JSON null 한 개가 실패하고 권한·응답 정렬은 통과함을 확인했다. 마지막에는 최근 10개 경계 테스트를 더했다.

## 방법 비교와 변경 범위

- 영상 여행: `VideoKey`로 같은 영상을 먼저 고른 뒤 SavedPlace를 한 번에 읽는다. 단순 IN 조회는 EAGER 연관을 작업마다 다시 읽으므로 기존 저장소 방식과 같은 EntityGraph(`processingJob`, `processingJob.user`, `user`)를 적용했다. 여행 선택·소유자 확인은 기존 흐름을 유지한다.
- 관리자 상세: (가) 링크별 최대 ID 집계 조회와 (나) 이미 읽은 최근 작업 활용을 비교했다. 같은 사용자의 작업을 상태 필터 없이 ID 내림차순으로 읽으므로 표시할 작업보다 새 작업은 반드시 앞에 있다. 따라서 (나)를 선택하고 먼저 나온 링크인지 Set으로 판단한다. 상위 10개 밖의 작업은 더 오래됐으므로 추가 SQL이 필요 없다. 기존 `isRetryable(job)`과 실행 직전 `retryableJob`의 DB 재확인·409·감사 흐름은 그대로다.
- 공유 공개 상세: P1의 정렬된 여행별 장소 조회를 재사용하고 일차 ID로 묶는다. 빈 일차, 방문 순서, 공개 필드 제한을 유지한다.
- P1 정리: `TripReplanGraph`의 미사용 Itinerary import·저장소 주입 제거, `TripController`·`TripDetailQueryCountTest`의 완전한 클래스 이름을 import로 정리했다.

## 없음 응답 결정과 앱 호환 근거

**소유한 자원은 있지만 아직 여행·활성 공유 링크가 없을 때만 `200`, `Content-Type: application/json`, 본문 `null`을 보낸다.** 타인 자원·존재하지 않는 원본 자원은 404, 미인증은 401을 유지한다. 해제한 공유 링크의 소유자 조회도 null이다. 공개 토큰의 조회·삭제·생성 상태 코드는 바꾸지 않는다.

빈 본문은 앱의 `res.json()`에서 파싱 오류가 나고 `{}`는 참으로 평가돼 링크/여행이 있다고 오인할 수 있어 사용하지 않는다. 본문 네 글자 `null`을 HTTP 테스트로 검사한다.

앱 저장소의 함수 이력은 `git log -L`로, 호출부는 `git log -p -G`로 처음 도입부터 v10까지 확인했다.

- [`getVideoTrip` 최초 도입 4199d0e](https://github.com/Trovapp/trova-app/commit/4199d0e86475e2b936fe2c5f020484e8b06d44bb): 404는 null, 성공은 `res.json()`을 반환하며 이후 함수 본문 변경이 없다. 화면은 최초부터 `videoTripQuery.data ?? null`, 존재 여부 분기 후 필드 접근을 사용했다. 이후 호출부 변경도 null 분기를 유지한다.
- [`getShareLink` 최초 도입 e8cdc0c](https://github.com/Trovapp/trova-app/commit/e8cdc0c3615d8a179e945099a9392d645ba54d82): `shareLinkOutcome`은 2xx를 ok로 분류하고 함수는 `res.json()`을 반환한다. 공유 시트는 최초부터 `linkQuery.data` 분기와 `linkQuery.data?.url`을 사용했다. 후속 두 커밋에서도 null 분기는 유지된다.
- [v10 소스 af3b078](https://github.com/Trovapp/trova-app/tree/af3b0787d3caecf23d0f195c5980ad91e8748027): 코디네이터가 제공한 EAS 빌드 `379054e5`의 커밋. 두 함수와 호출부를 확인했다.
- v7의 정확한 빌드 커밋은 기록에 없다. 코디네이터에 따르면 OTA `01a1d431` 적용 상태이며 앱 경험 기록 43줄이 근거다. **v7 바이너리 실행 확인은 하지 않았다.** 대신 해당 함수와 호출부가 최초 도입부터 모든 이력에서 null을 처리함을 확인했다. 기능 도입 전 버전은 해당 API 호출부가 없다.
- 최초 도입·v10의 실제 두 함수 본문을 읽어 가짜 HTTP 응답 `404`와 `200 + JSON null`을 각각 넣는 로컬 Node 검증 4건도 통과했다. 두 응답 모두 null 반환, 200에서만 JSON 파싱을 호출함을 확인했다. 실제 앱 네트워크/화면 E2E를 실행한 것은 아니다.

정상 없음이 200이 되므로 별도로 404 지표를 숨기지 않는다. 권한 위반과 없는 원본 자원의 404는 그대로 관측한다.

## 검증 환경과 전체 결과

- 이 PC의 Ubuntu WSL, JDK 21, 프로젝트 Gradle Wrapper 9.5.1, H2. 루트 `.env`, 운영 DB·서버, 로컬 8080, 개발 DB 테스트 계정, Gemini는 사용하지 않았다. 별도 허용된 Grafana 브라우저 읽기만 수행했다.
- P1의 기존 WSL JDK/Gradle 캐시를 재사용했다. CRLF wrapper 스크립트를 고치지 않고 같은 Wrapper의 main으로 `test build`를 실행했다. 임시 init script는 산출물 위치만 `/tmp/trova-perf-p2-runtime/build`로 바꾼다. 테스트 제외·전역 설치·설정 변경은 없다.
- 첫 전체 실행: 1,231개 중 2개 실패·6개 건너뜀. 기존 `ApiSessionCreationIntegrationTest`가 H2 `USERS` 테이블이 없다는 오류로 실패했다. 새 테스트 컨텍스트가 공유 `testdb`의 create-drop 수명에 간섭하지 않도록 새 클래스만 독립 H2 이름을 사용하도록 수정했다.
- 최종 전체 실행: **1,231개 중 1,225개 통과, 6개 기존 조건부 건너뜀, 실패·오류 0**(JUnit XML 합산). 새 회귀 테스트 8개 모두 통과. `BUILD SUCCESSFUL in 3m 49s`, `8 actionable tasks: 2 executed, 6 up-to-date`. `git diff --check` 통과.

실행한 Wrapper 명령(임시 init script는 `allprojects { layout.buildDirectory = file('/tmp/trova-perf-p2-runtime/build') }` 한 줄):

```sh
java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain \
  test build --init-script build/p2-wsl.init.gradle --console=plain
```

## 배포 전/후 비교 (배포 뒤 작성)

같은 길이의 관찰 구간, 요청 수·상태 분포·서버 부하를 함께 기록한다. 이번 PR에서는 배포하거나 운영 API에 테스트 요청을 보내지 않았다.

| 지표 | 배포 직전 구간/값 | 배포 후 구간/값 |
|---|---|---|
| 여행 상세 p95 / 요청 수 | | |
| 영상 여행 p95 / 요청 수 | | |
| 공유 링크 p95 / 요청 수 / 404 수 | | |
| Java RSS / 서버 available / 스왑 | | |

## 남은 것

- 머지·배포 뒤 같은 조건의 운영 비교. 특히 영상 여행은 요청 표본을 충분히 확보해야 한다.
- SQL 문장 수는 고정됐지만 영상 조회는 여전히 사용자의 작업 목록을 읽는다. 데이터 행 수·메모리 사용까지 고정된 것은 아니다.
- 관리자 최신 판단은 한 번 읽은 목록의 스냅샷이다. 조회 직후 새 작업이 생길 수 있으므로 실제 재시도 실행에서 기존 DB 재확인을 유지한다.
- P1과 이번 작업 모두 H2에서 검증했다. 운영 PostgreSQL 실행 계획과 p95 개선 폭은 확인하지 않았다.
