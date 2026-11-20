# P8 1단계: 대안 장소 19.2초 분해

작성: 2026-11-20, T24. **분석·로컬 측정만 수행했고 운영 코드 개선은 구현하지 않았다.**
운영 서버·운영 DB·Grafana에 접속하지 않았고 Google/Gemini/서울 API 실제 호출, Python 태깅 실행, `.env` 읽기, git 명령을 하지 않았다. 시작 시 작업 폴더에 임시 파일 생성·삭제로 쓰기 가능함을 확인했다.

## 결론과 20초 분해의 한계

전달받은 운영 표본은 2026-11-19 23:38~23:58 KST, 앱 9건, 평균 19.2초, p95 30초 이상(버킷 상한), SQL 67.4항목/요청이다. 요청별 후보 수·캐시 상태·실내 필터·외부 호출 시간이 없으므로 **19.2초를 외부 X초/DB Y초로 실측 분배할 수 없다.** p95도 정확한 값으로 취급하지 않는다.

| 시간 몫 | 확인한 것 | 운영 시간 해석 |
|---|---|---|
| 외부 대기 | Google 검색 1~2번 → 필요시 실내 태깅 → 미저장 임베딩 1묶음 → 후보별 서울 혼잡도 → 상위 두 후보 Gemini 이유를 모두 순차 실행 | 유력한 큰 몫이지만 이번 표본의 초 단위 실측 없음. 특히 이유 2번은 각각 전체 호출 제한 20초이므로 이것만 최대 약 40초의 대기를 만들 수 있음 |
| DB | 개인화 후보마다 임베딩 읽기 1회 + 임베딩 있으면 top-5 신호 1회. 카탈로그 신규 INSERT, 호출 로그, 임베딩 JDBC batch 항목도 포함 | 67.4 × 30~54ms = **2.02~3.64초**는 모든 항목을 개별 왕복으로 가정한 민감도 계산일 뿐 실제 DB 시간/상한이 아님. batch·쿼리 실행·커밋·풀 대기 때문에 그대로 적용 불가 |
| 내부 처리 + 로컬 DB | 실제 HTTP 경로를 외부 스텁·H2로 측정. 후보 20개에서도 밀리초 단위(아래 표) | Mac의 H2·예외 폴백 포함 시간. Oracle 1/8 OCPU의 CPU/스케줄링/GC 시간을 대변하지 않음 |
| 미분류 잔여 | 19.2초에서 위 가상 DB 계산만 빼면 15.56~17.18초 | 외부 시간의 실측이 아니라 **외부 + 내부 + 누락 DB 비용**의 가정상 잔여. 외부 병목을 단정하는 증거로 사용하지 않음 |

SQL N+1은 확정이며 개선 가치가 있다. 다만 DB 개선만으로 19.2초가 1초 미만이 된다고 약속할 수 없다. 기존 구간 로그와 외부 타이머를 같은 시간창에서 확인하는 것이 다음 판단의 근거다.

## 끝까지 읽은 동기 요청 경로

`TripController.findAlternatives` → `CurrentUserService.resolve`(사용자 엔티티 SELECT) → `DailyQuotaService.consumePlaceCall`(메모리) → 이동수단 파싱 → `AlternativeFinderService.findAlternatives` → 응답 DTO 직렬화.

1. `TripPlaceRepository.findById` 및 EAGER 일차/여행/사용자, 소유권 확인. 좌표 없으면 빈 응답.
2. `findNext`: 같은 일차 장소 SELECT 및 EAGER 연관. 다음 장소를 찾음.
3. Google Nearby Search. 기본 카테고리 검색이 성공했지만 원본 제외 3개 미만이면, 명시 category가 없는 경우에만 종류 제한 없이 한 번 보충. 검색 결과를 ID로 중복 제거. 요청당 최대 20+20개 원시 결과(조건 때문에 실제 합집합은 더 작을 수 있음).
4. `PlaceCatalogService.upsertAll`: googlePlaceId IN 조회, 신규만 각자 INSERT ON CONFLICT 후 신규 묶음 재조회. 기존 행은 그대로 재사용.
5. 자기 자신과 여행에 이미 있는 장소를 제외하기 위해 여행 전체 TripPlace SELECT/EAGER 연관. 숙소·역 등 제외.
6. `indoor=true`이면 space가 없는 후보를 Python 태깅 1묶음으로 처리. 카테고리 후처리·아침 필터.
7. `ensureEmbeddings`: 존재 여부 IN 조회 → 없는 후보만 Gemini batch → API 사용량 로그 batch → 임베딩 UPDATE batch. **거리/이동시간 필터보다 앞**이라 이후 버릴 후보도 생성 대상일 수 있음.
8. 후보마다 좌표·거리·이동시간 필터 → 서울 혼잡도(지원 이름일 때) → `retrieveAndScore`의 1~2 SQL → 기본 점수와 개인화 곱셈.
9. 유한 점수 우선 → 기본 검색 우선 → 점수 내림차순(동점 순서 보존). 상위 2개만 저장해 둔 신호로 `explainFromSignals`. 신호 없으면 생성 안 함. 설명을 위해 신호 SQL을 다시 읽지는 않음.
10. 모든 이유 생성이 끝난 뒤 응답. 이 HTTP 경로에는 요청 문장용 `queryScores`나 Google 상세/리뷰 조회가 없음(채팅 도구 경로와 구분).

## 외부 호출, 제한, 캐시

| 구간 | 호출 수/순서 | 타임아웃·재시도(코드) | 캐시 및 비용 관점 |
|---|---|---|---|
| Google Nearby | 기본 1 + 조건부 보충 1, 순차. 검색 1회 최대 결과 20 | 연결 3초/읽기 5초, 429·5xx·ResourceAccessException에 최대 3시도, 대기 0.5+1초. 두 검색 최대 6 HTTP 시도. 전체 요청 deadline은 없음 | 검색 결과 캐시·동시 요청 공유 없음. 카탈로그 저장은 검색 호출을 생략하지 않음 |
| 실내 Gemini 태깅 | indoor=true 및 미태깅 후보 있을 때 1묶음 subprocess, 순차 | Java 프로세스 제한 2분. Python HTTP timeout=120초, 최대 5시도; 잘못된 JSON/검증 실패면 repair 1회(각각 최대 5시도, 프로세스 제한 내). 429·5xx/네트워크 재시도, 일 한도 소진은 즉시 실패 | 기존 space는 재사용. 이 경로의 새 `applySpaceTag`에는 save/서비스 트랜잭션이 없어 영속 재사용을 보장하지 않음(정적 확인; 반복 실내 요청 실측 미수행) |
| Gemini 임베딩 | 미저장 M개에 ceil(M/100) 묶음, 이 경로의 정상 후보 상한에서는 0~1 HTTP 요청 | 공용 RestClient 연결 3초/읽기 5초, 앱 재시도 없음 | DB embedding 있는 ID 제외. **후보마다 외부 호출하지 않음**. 사용량은 HTTP 요청 수가 아니라 문장 수; 실패하면 다음 요청에 재시도 대상 |
| 서울 혼잡도 | 거리 필터를 통과한 지원 이름 K개에 K회, 순차 | 연결 3초/읽기 5초, 앱 재시도 없음 | `SeoulCongestionAreaCache`는 지원 지역명 집합이며 응답 캐시가 아님. 키 없으면 생략 |
| Gemini 추천 이유 | 상위 2개 중 유한 신호 있는 E개(0~2), 순차 | Java ChatModel/SDK 둘 다 재시도 끔. 커스텀 OkHttp 전체 callTimeout 20초/회 | 이유 캐시 없음. 같은 후보 재조회마다 최대 2회. 신호·점수는 요청 안에서 재사용 |

Google/임베딩/서울의 connect+read 제한은 단계별 제한이며 HTTP 전체 wall-clock의 엄밀한 상한이 아니다. SDK 텍스트 호출의 전체 20초 제한과 구분한다. 설명 지연은 `SpringAiConfig`에 과거 평균 6.0초/최대 8.6초 기록이 있지만, **2026-11 운영 표본의 측정값으로 재사용하지 않는다.**

## SQL 67.4의 정체

측정 단위는 `RequestDbQueryMetrics`: Hibernate StatementInspector SQL 제출 + 두 직접 JDBC 저장소의 batch **항목 수**. 실패 SQL도 포함, 트랜잭션 제어/드라이버 batch 재작성/DB 내부 SQL/비동기 작업은 제외한다. 따라서 67.4는 네트워크 왕복 67.4회와 같지 않다.

| 경로 | SQL 항목 수 | 반복성 |
|---|---:|---|
| 사용자 조회 | 1 | `resolveUserId` 캐시 경로를 쓰지 않음 |
| 원본 TripPlace 조회 | 1 | EAGER 연관 JOIN 포함 |
| 다음 장소용 일차 목록 | 2 (로컬 픽스처) | 목록 + 일차 연관 조회 |
| 검색 호출 로그 | S=1~2 | 검색 재시도 각각이 아니라 논리 검색별 기록 |
| 카탈로그 | 1 + 신규 U개 있으면 U+1 | 기존 후보 묶음 조회는 이미 해결됨. 신규 INSERT는 후보별 |
| 여행 전체 장소 제외 목록 | 2 (일차 1개 픽스처) | 여행의 일차 수에 따라 EAGER 추가 조회가 늘 수 있음 |
| 임베딩 존재 확인 | 1 (후보 있을 때) | 묶음 |
| 미저장 임베딩 기록·저장 | M 로그 + 성공한 M UPDATE | 두 batch 호출도 계측상 2M. 생성 실패면 로그 M만 |
| 개인화 | C + B | 최종 후보 C개 각각 임베딩 조회; 그중 저장된 임베딩 B개 각각 top-5 조회. 임베딩이 있으면 사용자 신호가 없어도 두 번째 SQL 실행 |
| 추천 이유 로그 | E=0~2 | 이유 외부 호출별 INSERT |
| 혼잡도 로그 / 태깅 로그 | K / T | 외부 호출 결과 기록 |

일차 1개·원본 있음·후보 있음의 기준식은 **8 + S + C + B + (U>0 ? U+1 : 0) + M로그 + M성공UPDATE + E + K + T**. S=1, 신규/태깅/혼잡도 없으면 **9+C+B+E**. 빈 Google 결과는 카탈로그 이후를 건너뛰어 로컬 5회다. 일차가 더 많거나 오류 처리 중 로그 저장 실패가 있으면 기준식에서 달라질 수 있다.

- 따뜻한 후보 20개 모두 임베딩 있음, 이유 2개: 9+40+2 = **51항목**.
- 신규 후보 11개 모두 생성 성공, 이유 2개: 9+22+12+22+2 = **67항목**(가능한 시나리오, 운영 요청을 재현했다고 주장하지 않음).
- 신규 후보 20개, 임베딩 없음, 생성·저장 성공, 이유 없음: 9+40+21+40 = **110항목**, 이 중 40은 batch 항목이다(로컬 아래에서 검증).

따라서 SQL 67.4 전체를 개인화 N+1이라고 부를 수는 없다. **후보당 1~2회 개인화가 확실한 N+1이고, 신규 카탈로그·batch 계수·여행 EAGER·외부 호출 로그가 나머지 설명 변수다.** 운영의 후보/신규/미임베딩 수를 확보해야 실제 비중이 정해진다.

## 로컬 측정 (H2, 외부 스텁)

재현 파일: [AlternativeLatencyMeasurementTest.java](../../../src/test/java/com/trova/backend/controller/AlternativeLatencyMeasurementTest.java).

```sh
./gradlew test --offline --console=plain --tests '*AlternativeLatencyMeasurementTest'
```

실제 JWT + MockMvc 컨트롤러, 실제 JPA 저장소/개인화/카탈로그/호출 기록/SQL 지표를 사용한다. Google·Gemini 임베딩·Gemini 텍스트·서울 혼잡도·Python 태깅은 Mockito 빈으로 대체. H2 URL 고정, 테스트 설정의 더미 키, OTLP 전송 꺼짐. 원본 1개/일차 1개, 명시 cafe 필터로 보충 검색은 제외, 후보명은 서울 지원 지역명이 아니며 indoor는 미설정이다. 각 시나리오 별 사용자·카탈로그 ID를 분리한다.

각 조합 첫 요청 SQL → 10회 예열 → 30회 HTTP 요청 중앙값. 응답 후보 수와 warm SQL 수를 검증하고 Hibernate Statistics 준비 수와 P4 지표를 대조한다. 타이밍은 요청/응답 파싱과 검증까지 포함한 로컬 wall-clock이며 CPU 시간만이 아니다. 로그 설정은 개인화 실패 경고와 Hibernate 오류를 억제했고 서비스 구간 INFO는 유지했다.

세 가지 모드:

- `missing-column`: 원래 H2처럼 embedding 컬럼 없음. 존재 확인과 후보별 첫 SQL이 실패, Gemini 호출 없음. 신규 카탈로그 저장은 실제 실행.
- `stored-vector`: 테스트 전용 VARCHAR embedding을 만들어 카탈로그와 임베딩 문자열을 사전 입력. 후보별 첫 조회는 성공, 두 번째 pgvector 연산 SQL은 H2에서 실패. 후보당 **2번 제출**을 검증하며 pgvector 성공 실행 시간은 검증하지 않음.
- `new-vector`: H2 전용 `vector` DOMAIN을 VARCHAR로 만들고, 스텁 Gemini가 [1,0]을 반환. 신규 카탈로그/사용량 batch INSERT/임베딩 batch UPDATE를 실제 H2에서 실행. 첫 요청 생성 1회, 예열 이후 0회를 검증. pgvector 거리 연산은 여전히 실패하므로 설명은 생성되지 않음. 실제 768차원 벡터 처리나 운영 pgvector 검증이 아님.

| 모드 | 후보 수 | 첫 요청 SQL (Hibernate) | 예열 SQL/요청 | 중앙값 ms | min~max ms |
|---|---:|---:|---:|---:|---:|
| missing-column | 0 | 5 (5) | 5.0 | 2.205 | 1.882~2.622 |
| missing-column | 1 | 12 (12) | 10.0 | 3.149 | 2.766~3.858 |
| missing-column | 5 | 20 (20) | 14.0 | 3.222 | 2.590~6.596 |
| missing-column | 20 | 50 (50) | 29.0 | 4.252 | 3.793~6.107 |
| stored-vector | 0 | 5 (5) | 5.0 | 1.045 | 0.972~1.204 |
| stored-vector | 1 | 11 (11) | 11.0 | 2.278 | 1.963~2.733 |
| stored-vector | 5 | 19 (19) | 19.0 | 2.913 | 2.486~8.690 |
| stored-vector | 20 | 49 (49) | 49.0 | 5.205 | 4.676~5.982 |
| new-vector | 0 | 5 (5) | 5.0 | 0.681 | 0.628~0.855 |
| new-vector | 1 | 15 (13) | 11.0 | 1.577 | 1.334~1.918 |
| new-vector | 5 | 35 (25) | 19.0 | 2.490 | 2.008~8.259 |
| new-vector | 20 | 110 (70) | 49.0 | 4.499 | 4.179~6.135 |

최종 대상 테스트 **1개 통과, 실패 0, 건너뜀 0**(12개 조합, 합계 492 HTTP 요청). 전체 테스트는 실행하지 않았다. 원시 요약은 [local-results.txt](local-results.txt).

저장된 임베딩 20개는 예열 SQL **49회=9+2×20**. 신규 20개는 첫 요청 **110항목**, Hibernate만 보면 70회이고 차이 40은 실제 JDBC batch 두 곳의 20항목씩이다. 같은 후보를 반복하면 스텁 임베딩 호출은 최초 1회에서 추가 0회로 떨어진다. 이로써 개인화 N+1과 batch 계수, 임베딩 재사용을 각각 구분했다. 모드 실행 순서에 따른 JIT 예열 차이가 있으므로 서로 다른 모드의 ms 차이를 성능 개선량으로 읽지 않는다.


초기 측정 실행에서는 테스트 픽스처의 LAST_SYNCED_AT 누락으로 실패했고, 모드 확장 시 Mockito 재설정이 기존 Answer를 null로 호출해 실패했다. 픽스처와 스텁 설치 위치를 수정했다. 운영 코드는 수정하지 않았다.

## 이미 있는 지표로 볼 수 있는 범위

`ApiCallLogService`의 Timer는 **`trova.api_call` → `trova_api_call_seconds_{sum,count,...}`**, 라벨 **provider, operation, success**다. 저장소의 Grafana Cloud essentials/기존 overview·learning 대시보드에서 같은 이름을 사용한다. 배포 소스·대시보드 정의로 확인했고 이번에 실제 운영 시리즈를 조회하지 않았다.

| provider | operation | 포함/주의 |
|---|---|---|
| google-places | nearby-search-alternative | 논리 검색 전체, 내부 재시도·대기 포함. 보충은 별도 기록 |
| gemini | recommendation-explanation | 이유 1건 생성 시간, 외부 실패도 기록 |
| gemini | place-embedding-batch | **문장마다 같은 batch latency를 중복 기록**. sum은 실제 외부 wall-clock 합계가 아니고 count도 HTTP 호출 수가 아님 |
| seoul-opendata | citydata-congestion | 서울 API 1회 시간 |
| gemini | tag_places / tag_places.repair | Python 마커를 읽어 기록. 재시도·대기 포함; 프로세스 시작/입출력·강제 종료는 전체가 타이머로 잡히지 않을 수 있음 |

위 타이머에는 HTTP uri/요청 ID가 없다. 채팅 도구·다른 추천 경로와 같은 operation을 공유하므로 특정 alternatives 9건만 뽑아 합계 낼 수 없다. `record`는 DB 저장 **후** 타이머 기록이라 저장 실패 시 표본이 빠질 수 있고, 전달된 latency는 호출 로그 INSERT 시간을 제외한다. `recordBatch`는 문장 수로 중복 가중하므로 평균조차 batch 크기에 가중된 평균이다.

기존 지표 조회식(이 문서에서 실행하지 않음):

```promql
sum by (provider, operation, success) (
  increase(trova_api_call_seconds_sum{job="trova-prod",operation=~"nearby-search-alternative|recommendation-explanation|place-embedding-batch|citydata-congestion|tag_places.*"}[20m])
)
/
sum by (provider, operation, success) (
  increase(trova_api_call_seconds_count{job="trova-prod",operation=~"nearby-search-alternative|recommendation-explanation|place-embedding-batch|citydata-congestion|tag_places.*"}[20m])
)
```

`http_server_requests_seconds_*`는 전체 응답, `trova_db_queries_per_request_*`는 uri/method별 SQL 항목 분포이며 SQL 시간을 재지 않는다. HTTP는 status를 구분할 수 있지만 P4 SQL 지표는 status가 없어 분모를 무조건 같다고 보지 않는다. Spring AI `gen_ai.client.*` observation 등록도 있으나 alternatives 전용은 아니며 실제 export 이름/라벨은 미확인이다. 수동 RestClient builder에 대해 자동 `http.client.requests`가 모든 외부 호출을 커버한다고 가정하지 않는다.

이미 `AlternativeFinderService`에 다음 INFO 로그가 있다:

`대안 찾기 구간별 시간: 검색=… 카탈로그저장=…(N곳) 실내태그=… 임베딩=… 혼잡도=… 개인화조회=…(C곳) 추천이유=…`

`PlaceEmbeddingService`도 `장소 임베딩 묶음: N개, 요청=…ms, 저장=…ms`를 남긴다. 우선 기존 운영 로그에서 표본 시간창의 이 줄을 확인하면 단계 실측을 복원할 수 있다. 단, 원본/형제/여행목록 조회·필터·정렬·직렬화는 첫 로그의 구간 합에 모두 포함되지 않고, 임베딩 저장 로그 시간은 API 사용량 로그 저장 이후부터다. 조기 반환/오류 요청은 마지막 구간 로그가 없을 수 있다.

부족한 관측 개선안은 **외부 논리 호출마다 정확히 한 번의 Timer**(provider/operation/outcome + 제한된 caller), 그리고 alternatives 단계별 Timer(search/catalog/tagging/embedding/congestion/personalization/explanation)다. 기존 사용량 지표는 보존하고 batch 문장 수 카운터와 호출 시간은 분리한다. 요청 ID/좌표/후보명/userId는 metric 라벨로 넣지 않는다. 이 단계에서는 계측도 구현하지 않았다.

## 개선안 순위 (다음 단계 승인용)

아래 효과는 측정한 전후값이 아니라 구조에서 계산한 기대치다. 일차 1개/따뜻한 C=20개/임베딩 있음/이유 2개를 비교 기준으로 삼는다.

| 순위/안 | 기대 효과 | 위험·보존 조건 | 작업량·API 비용 |
|---|---|---|---|
| 0. 기존 로그 조회 + 부족한 호출/단계 타이머 | 20초 중 실제 지배 구간·실내/신규/이유 차이를 확정 | batch 사용량 중복 합계 금지, 낮은 라벨 개수, 오류/조기반환도 고려 | 소~중. 외부 호출 증가 없음 |
| 1. P7 방식 개인화 묶음 조회 | **2C→1**, C=20이면 40→1; 전체 예시 51→12항목. 왕복 39회 ×30~54ms = **1.17~2.11초** 절감 가설(묶음 DB 실행 시간 별도) | 대안은 이유용 name/mood/신호 목록도 필요. P7의 점수 전용 맵을 그대로 쓰면 부족. 후보별 기존 top-5/유한값/평균 순서/기본·보충 우선/동점 보존(ADR 0051), pgvector 실제 동일성 검사 필요 | 중. Google/Gemini 횟수 그대로 |
| 2. 이유 2건 제한 병렬화 및 요청 내 중복 공유 | 순차 t1+t2→대략 max(t1,t2); **min(t1,t2)**만큼 단축 가능 | 같은 상위 2개만, 전역 동시성 제한·취소·deadline 필요. 기존 ThreadLocal SQL 지표는 자식 작업 누락하므로 별도 관측 필요. 응답 순서/신호 보존 | 중. 호출 수 유지하되 RPM 순간 집중 관리. 새 재시도 금지 |
| 3. DB 기본 조회 정리 + 신규 카탈로그 INSERT 묶음 | 사용자 엔티티/형제·여행 EAGER 반복 제거, 신규 U개 개별 트랜잭션 감소 | 사용자 권한·탈퇴·여행 전체 제외·다음 장소 순서 보존. 외부 대기 동안 DB 트랜잭션을 길게 잡지 않음 | 중. 외부 비용 불변 |
| 4. 검색 결과 짧은 캐시/동시 요청 공유 | 동일 위치·종류·반경 반복 Google 호출 감소. 서울 혼잡도도 짧은 freshness 예산으로 가능 | 완성 개인화 응답을 사용자 간 공유하지 않음. 키/최대 항목/TTL, 최신성·저장 조건 검토. 실패 영구 캐시 금지 | 중. Google 호출 감소, 비용 증가 없음 |
| 5. 임베딩 재사용 보강·필터 뒤 생성·필요분 사전 계산 | 이미 batch+영속 재사용 중. 거리 필터에서 버릴 후보 생성 방지, 동시 요청의 동일 미저장 장소 중복 생성 방지. 사전 계산은 cold 응답 대기 이동 | 무차별 카탈로그 백필은 현재보다 문장 사용량 증가하므로 제외. 기존에 생성할 대상만 처리/중복 방지, 모델·텍스트 변경 시 무효화 정책 필요 | 중. 문장 수 같거나 감소. 단순 추가 batch화는 이미 완료 |
| 6. 호출별 예산/timeout 단축 + 선택적 병렬 혼잡도 | 긴 꼬리 제한. 부가 배지/설명은 실패 시 빠른 빈 값 응답 가능 | 공용 Gemini timeout 일괄 단축은 다른 초안/추출 품질 손상. 호출 목적별 예산으로 분리, 사용자 재시도로 비용 늘지 않도록 실패 재호출 제어 | 중. 시도 수 유지/감소; 병렬 후보 수 제한 |
| 7. 목록 먼저, 이유·혼잡도 나중에 | 초기 체감 응답에서 이유·혼잡도 대기를 제거; 총 작업시간은 그대로일 수 있음 | 앱/API 계약·폴링/취소/권한/작업 수명 필요. 재조회마다 Google·Gemini 다시 실행하면 비용 증가하므로 기존 작업 재사용 필수. 개인화 랭킹을 나중으로 보내면 목록 순서 변동 | 대(백엔드+앱). 중복 없는 설계가 전제 |

실내 태깅이 지배한다면 순위를 바꿔 **새 태그의 영속 저장/재사용과 호출 예산**을 먼저 검토한다. 보충 검색은 기본 결과를 알아야 필요 여부가 결정되므로 무조건 병렬 선호출하면 Google 비용이 늘어난다. 전 후보 이유 생성, 상세/리뷰 추가 검색, 무차별 임베딩 백필은 제안하지 않는다.

## 완료·미측정·다음 선택

완료: 경로/클라이언트/ADR 0051/P7 분석, 외부 스텁 HTTP/H2 SQL 증가율·내부 wall-clock, 외부 타이머 의미와 누락 범위, 개선안 비교. 추가 파일은 이 문서와 측정 테스트/측정 결과 파일이며 src/main·운영 설정 변경 없음.

미측정: 운영 9건의 요청별 구간 시간·후보/신규/실내 분포, 실제 외부 지연과 재시도 비율, 운영 DB 쿼리 실행/네트워크/풀 대기, Oracle CPU, pgvector 성공 처리시간·순위, 실내 태그 재요청, 혼잡도·추천 이유 성공 분기. H2 VARCHAR/domain 모드는 pgvector 대체가 아니며 19.2초 재현 테스트가 아니다.

다음 단계 선택 전에는 개선 구현을 시작하지 않는다.

1. (Recommended) 기존 표본 시간창의 구간 로그·operation 타이머를 매니저가 조회하고 20초 실측 분해를 확정한다.
2. 개인화 SQL 묶음 조회를 별도 단계로 승인하고, 이유 신호까지 보존하는 pgvector 동일성 검증을 진행한다.
3. 이유 생성이 지배하면 두 건 제한 병렬화 또는 점진 로딩 중 UX/비용 조건에 맞는 방식을 선택한다.

## 근거 파일

- [P4 계측·운영 표본](../2026-11-19-p4-metrics/README.md), [P7 분석·검증](../2026-11-19-p4-metrics/gap-recommendations-analysis.md), [ADR 0051](../../adr/0051-personalization-finite-scores.md)
- `src/main/java/com/trova/backend/controller/TripController.java` / `service/CurrentUserService.java` / `service/DailyQuotaService.java`
- `recommendation/AlternativeFinderService.java`, `PersonalizationService.java`, `PlaceEmbeddingService.java`, `PlaceCatalogService.java`, `GooglePlacesApiClientImpl.java` (위 main 패키지 아래)
- `embedding/GeminiTextClientImpl.java`, `GeminiEmbeddingClientImpl.java`, `config/SpringAiConfig.java`, `RestClientConfig.java`
- `pipeline/PlaceTaggingRunner.java`, `pipeline-test/tag_places.py`, `pipeline-test/extract_places.py`, `congestion/SeoulCongestionApiClientImpl.java`
- `repository/TripPlaceRepository.java`, `PlaceRepository.java`, `UserPreferenceSignalRepository.java`, `PlaceEmbeddingJdbcRepository.java`, `ApiCallLogJdbcRepository.java`
- `service/ApiCallLogService.java`, `config/RequestDbQueryMetrics.java`, `monitoring/grafana-cloud/dashboards/trova-essentials.json`

## 2단계 구현 (2026-11-20)

사용자가 선택한 규칙 문장 방식으로 변경했다([ADR 0052](../../adr/0052-alternative-rule-reasons.md)). 기존 Gemini 자유 문장 → 원래 장소 기준 직선 거리·취향·종류·평점·리뷰의 확인된 값 최대 두 개다. 상위 2개만 이유를 표시하고 응답 JSON의 필드, 후보 순서·필터는 유지한다. 직선 거리는 검색 반경/이동시간과 구분하며 현재 사용자 위치라고 표현하지 않는다.

문구 예시(괄호는 입력 조건이며 화면에 표시하지 않음):

1. `원래 장소에서 직선 350m · 같은 음식점이에요` (거리 0.35km, 같은 음식점, 점수 0)
2. `원래 장소에서 직선 1.3km · 같은 카페예요` (거리 1.25km, 같은 카페, 평점 없음)
3. `취향과 비슷해요 · 평점 4.5` (거리/종류 없음, 양수 유한 점수, 평점 있음)
4. `평점 4.5 · 리뷰 1,200개` (거리 없음, 종류 불일치, 개인화 0점)
5. `리뷰 12개` (거리·평점·종류 없음, 개인화 0점)

ADR 0051의 유한값 원칙에 더해 점수는 양수일 때만 취향 문구를 쓴다. 0점은 신호 없음/실패와 구분할 수 없고, 음수도 취향 근거가 아니다. 조회한 신호가 반드시 찜은 아니므로 `찜한 곳`이라고 하지 않는다. 검색용 `defaultTypeFor`는 박물관과 공원까지 같은 관광으로 묶으므로 이유의 종류 일치에 쓰지 않았다. 데이터가 모두 없으면 null이다.

### 바꾼 조회와 복구

- 기본 모드에서 `personalizationScores` → P7 `findCandidateSimilarities`를 재사용한다. 개인화 C+B번을 후보 묶음 1번으로 줄인다. 기존 top-5/유한값/평균 순서를 그대로 사용한다.
- 여행 전체 제외 목록은 `EntityGraph`로 일차·여행·사용자를 같이 읽어 기본 EAGER 추가 SELECT를 제거한다. 권한 확인과 다음 장소 선택 방식은 유지한다.
- 신규 카탈로그 INSERT는 보류했다. 공유 경로의 ON CONFLICT/동시 저장/계측 변경보다 기존 EAGER 조회 정리가 위험이 낮고, 기본 모드의 큰 N+1은 이미 제거했다. 따라서 신규 후보 INSERT와 임베딩 저장 항목은 여전히 후보 수에 비례한다.
- `ALTERNATIVES_GEMINI_EXPLANATION_ENABLED=true`로 설정하고 재시작하면 기존 Gemini 이유와 후보별 개인화 조회로 되돌아간다. 기본 false이며 채팅 등 다른 경로의 생성기는 변경하지 않았다. 복구 모드는 SQL 절감도 포기한다.

### 로컬 전후 SQL·시간

1단계와 같은 외부 스텁 HTTP/H2 측정(각 10회 예열 + 30회, 중앙값), 2단계 원시는 [phase2-local-results.txt](phase2-local-results.txt)에 저장했다. 아래 전 값은 1단계 기록을 재사용한 것으로 이번 실행과 같은 프로세스/동시 측정이 아니다. JIT/부하 차이가 있으므로 ms 차이는 운영 지연 개선량이 아니다. H2의 stored-vector도 pgvector 성공 실행이 아닌 SQL 제출·예외 폴백 측정이다.

| 모드 | 후보 | 예열 SQL 전 → 후 | 중앙값 ms 전 → 후 |
|---|---:|---:|---:|
| missing-column | 1 | 10 → 9 | 3.149 → 2.969 |
| missing-column | 5 | 14 → 9 | 3.222 → 3.047 |
| missing-column | 20 | 29 → 9 | 4.252 → 2.296 |
| stored-vector | 1 | 11 → 9 | 2.278 → 1.627 |
| stored-vector | 5 | 19 → 9 | 2.913 → 1.558 |
| stored-vector | 20 | 49 → 9 | 5.205 → 1.965 |
| new-vector | 1 | 11 → 9 | 1.577 → 1.473 |
| new-vector | 5 | 19 → 9 | 2.490 → 1.429 |
| new-vector | 20 | 49 → 9 | 4.499 → 1.595 |

빈 후보는 전후 5항목 그대로다. 신규 20개 최초 요청은 110 → 70항목(Hibernate 70 → 30), 차이 40항목은 전후 동일한 임베딩 로그·UPDATE batch다. 기본·따뜻한 후보·단일 일차·Google 1회·태깅/혼잡도 없음 조건의 SQL은 **9항목**이며 후보 수와 무관하다. 신규 저장과 Google 보충/태깅/혼잡도까지 모든 요청이 9라는 뜻은 아니다.

실제 Mac Docker Testcontainers의 PostgreSQL 17/pgvector 0.8.1에서도 신호 있는 사용자로 HTTP API를 요청해 후보 **1·5·12개 모두 SQL 9회**를 확인했다(건너뜀 없음). Gemini text/embedding은 0회, Google은 각 요청 1회다. 별도 기존 모드 대조에서는 사용자 5명, 14후보(무작위·음수·0벡터·임베딩 없음·신호 없음)를 사용해 이유 외 모든 응답 필드와 순서가 같았고, 기본 모드의 text 호출 0회와 Google/embedding 횟수도 확인했다. P7의 top-5/비트 단위 점수 동일성 테스트도 함께 통과했다.

### 실패 먼저·변형 검사

구현 전 규칙 클래스 부재 컴파일 실패 후 null 골격으로 규칙 3개 실패, SQL 고정 기대 실패를 확인했다. 새 기본 모드 테스트는 기존 경로에서 예상 순서 불일치로 실패했고, pgvector 모드 비교는 복구 플래그 부재로 실패했다. 이후 구현으로 대상 테스트가 통과했다.

세 변형을 각각 적용하고 실패를 확인한 뒤 원복했다: (1) 개인화 `>0`을 `>=0`으로 바꿈 → 규칙 테스트 3개 실패, (2) 서비스 기본 Gemini 플래그 true → 기본 모드 테스트 1개 실패, (3) 묶음 점수를 후보별 `retrieveAndScore`로 바꿈 → SQL 고정 테스트 1개 실패. 최종 전체 테스트 결과는 아래 완료 기록을 따른다.

### 운영 예측과 한계

사용자가 제공한 2026-11-19 23:38~23:58 타이머는 Gemini 이유 count 약 15.2, sum 136.7초로 회당 약 9초다. 요청 9개로 나눈 약 **15초/요청의 이유 대기 제거**를 기대한다. 기본 모드에서는 해당 외부 호출이 실제로 0회다. 공용 타이머의 다른 호출자 혼입 가능성은 1단계 해석처럼 남는다.

따뜻한 후보 20개는 설명 로그 포함 예시 51 → 9항목, 그중 개인화 40 → 1과 EAGER 1 감소로 조회 왕복 40회 감소다. 30~54ms 가정이면 추가 약 **1.20~2.16초** 감소 민감도이나 묶음 SQL 실행 시간/풀/네트워크가 달라 합산 SLA로 약속하지 않는다. 임베딩 batch 타이머 sum 76초는 문장별 중복 기록 가능성 때문에 절감량에 더하지 않았다. 운영 DB·외부 API·.env 접근 없이 로컬에서만 검증했으며, 운영 실측은 배포 뒤 매니저가 확인한다.

### 최종 전체 검증

`./gradlew test --offline --console=plain`을 다른 Gradle 작업 없이 단독 실행: **1,296개, 통과 1,290, 실패/오류 0, 기존 조건부 건너뜀 6**(1분 38초). 기존 실패 0·새 실패 0이며 pgvector 4개는 모두 실행·통과했다. 건너뜀은 기존 선택 실행 성능/프로세스 복구/Kafka 테스트들이다. 변형은 모두 원복한 상태다.

`./gradlew build --offline --console=plain`도 성공했다(전체 test 결과 UP-TO-DATE 재사용).
