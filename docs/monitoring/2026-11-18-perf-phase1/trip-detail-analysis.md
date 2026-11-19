# 여행 상세에 남은 259ms와 초안 상세 461ms 분석

작성: 2026-11-19, T17 / #471. 분석 기준 백엔드 `523308a`, 앱 `af3b078`.
애플리케이션·테스트·T16 계측 코드는 변경하지 않았다. 운영 DB·서버·API·Grafana에 접속하지 않았다.

## 결론과 관측 범위

**P1 뒤 여행 상세는 빨라졌다.** 처음 전달된 평균 355ms·p95 1,082ms는 약 7.7건의 표본1이었다. 코디네이터가 전달한 표본2는 GET 200 기준 평균 **352→259ms(-26%)**, p50 **345→255ms**, p95 **445→343ms**, 요청 수 **26→30**이다. 따라서 이번 질문은 “P1이 왜 효과가 없었나”에서 “남은 259ms를 무엇부터 줄일까”로 바꾼다. [전체 비교와 캡처](README.md#배포-후-표본2에뮬레이터시뮬레이터).

표본2는 2026-11-19 00:21:44~00:46:43 KST, Android 에뮬레이터와 iOS 시뮬레이터 동시 조작, 매니저가 Mac Grafana API로 조회한 `increase[26m]` 집계다. 배포 전은 11-18 00:00~21:36 KST다. 이 세션의 재측정이 아니며 같은 여행·장소 수를 통제한 AB 실험도 아니다. 표본2의 프로세스 CPU 평균 1.3%, heap 최대 107MiB, 404·5xx 증가 없음도 전달값이다. CPU 평균이 낮다는 것만으로 순간 스케줄링·GC·연결 대기를 배제할 수 없다.

여행 상세의 유력한 잔여 비용은 **원격 DB 조회 5회와 분리된 읽기 트랜잭션**이다. 사용자 확인 캐시 미사용과 EAGER 연관의 중복 조회를 로컬에서 확인했다. 장소·영업시간·사진을 위한 외부 호출은 이 GET 경로에 없다. 초안 상세는 별도 경로이며 READY/NEEDS_INPUT에서 사용자의 저장 장소 전체를 읽는 비용이 추가된다. 운영 259ms와 461ms를 단계별로 실측 분해했다는 뜻은 아니다.

## 여행 상세 요청 경로와 남은 SQL 5회

Java 경로는 `src/main/java/com/trova/backend/`를 기준으로 적었고 줄 번호는 기준 SHA의 값이다. SQL은 이번 H2 MockMvc 요청에서 Hibernate `StatementInspector`로 잡은 순서이며 SELECT 칼럼은 줄였다. `prepareStatementCount`는 BEGIN/COMMIT·소켓 왕복을 세는 지표가 아니다.

1. JWT: `security/JwtAuthenticationFilter.java:39`에서 서명을 파싱하고 메모리의 정지 사용자 목록을 확인한다. 이 필터는 DB·외부 API를 호출하지 않는다.
2. `TripController.getTrip` → `CurrentUserService.resolve` → `UserRepository.findById`. **이 GET은 `TripService`를 경유하지 않고 컨트롤러에서 저장소를 직접 호출한다.** 근거: `controller/TripController.java:245`, `service/CurrentUserService.java:43`.
3. 여행 조회·소유자 확인 → `toDetail`에서 모든 여행 장소와 모든 일차를 읽고 일차 ID로 묶어 DTO를 만든다(`TripController.java:250,290`).
4. Spring MVC의 JSON 메시지 변환기가 record DTO를 직렬화한다. 엔티티를 응답으로 넘기지 않으므로 이 단계에서 연관을 순회하는 SQL은 없다(`TripController.java:149,190,193`). `open-in-view=false`(`src/main/resources/application.yml:30`).

| 순서 | SELECT 개요 | 발생 위치·이유 |
|---|---|---|
| 1 | `select ... from users where id=?` | `CurrentUserService.java:62`. `resolveUserId`의 KnownUsers 캐시(`:28`)와 달리 예열 JWT에서도 매번 실제 사용자 행을 읽는다. |
| 2 | `select ... from trips t join users u on ... where t.id=?` | `TripController.java:250`, `entity/Trip.java:16`. `findById`가 기본 EAGER 소유자까지 조인한다. #1과 별도 영속성 컨텍스트이므로 사용자를 다시 로딩한다. |
| 3 | `select ... from trip_places tp join itineraries i ... join trips t ... join users u ... where t.id=? order by i.day_number,tp.visit_order` | `repository/TripPlaceRepository.java:19`. P1의 fetch join이다. 모든 장소를 한 번에 읽고 EAGER 일차·여행·사용자 추가 조회를 막지만 같은 여행·사용자 칼럼이 장소 행마다 반복된다. |
| 4 | `select ... from itineraries where trip_id=? order by day_number` | `TripController.java:293`, `repository/ItineraryRepository.java:12`. 장소가 없는 일차도 응답에 유지하기 위한 별도 조회다. |
| 5 | `select ... from trips t join users u on ... where t.id=?` | #4의 `Itinerary.trip` 기본 EAGER 로딩(`entity/Itinerary.java:16`). #3의 영속성 컨텍스트를 공유하지 않아 같은 여행을 다시 읽는다. 일차별 N+1은 사라졌지만 이 한 번은 남아 있다. |

`getTrip`과 `toDetail`에는 요청 전체를 묶는 `@Transactional`이 없다. #1·#2의 상속 `findById`는 Spring Data 기본 읽기 트랜잭션이고, 별도 선언 조회 #3·#4와 영속성 컨텍스트를 공유하지 않는다. 실제 생성 SQL에서 #5가 나타남을 확인했다. SQL 수를 줄이려고 무조건 컨트롤러 전체에 트랜잭션을 더하면 BEGIN/COMMIT·readOnly 상태 전환 왕복까지 달라질 수 있다. 엔티티를 덜 읽는 조회 전용 DTO가 더 명확한 개선 후보다.

## SQL 밖의 비용과 앱 화면의 추가 요청

- 응답 조립은 장소 P개를 `groupingBy`로 묶고 일차 D개와 장소 P개를 DTO로 바꾸는 O(P+D) 작업이다(`TripController.java:290`). 장소 수는 SQL 수가 고정돼도 제한되지 않으며 memo도 TEXT다(`entity/TripPlace.java:62`). fetch join의 중복 칼럼·엔티티 생성, JDBC 결과 디코딩과 DTO·JSON 할당은 남는다.
- `TripPlaceResponse.from`은 저장된 필드만 읽는다(`TripController.java:155`). 영업시간 조회·사진 URL 생성·Google/Kakao/Gemini 호출은 없다. `PlaceReviewService`가 주입돼 있어도 여행 GET에서 호출하지 않는다. 장소 상세 모달의 API와 혼동하지 않는다.
- 커넥션 획득, 트랜잭션 종료, 네트워크 RTT는 H2의 SELECT 실행 시간과 다르다. 운영 Hikari 대기·DB 실행 계획·해당 요청의 커밋 시간은 이번에 측정하지 않았다. Supabase PgBouncer transaction 모드 및 `prepareThreshold=0` 설정은 `application.yml:20`에 있다.
- 1/8 OCPU에서 보안 필터·JWT·엔티티 변환·JSON, 다른 요청과의 경쟁·GC가 시간을 쓸 수 있다. 현재 경로의 운영 CPU 시간을 분해하지 않았으므로 “나머지는 전부 CPU”라고 결론 내리지 않는다.

앱 소스는 별도 읽기 전용 워크트리 `trova-sample2-ios-metro`, SHA `af3b078`을 확인했다. 아래 줄은 `trova-app/src/screens/TripDetailScreen.tsx` 기준이다.

| 화면에서 하는 일 | 근거 | 여행 GET 시간과의 관계 |
|---|---|---|
| 여행 상세 + 찜 + 폴더 조회 | `:254`, `:503,504` | 각자 별도 HTTP 요청. 공유 서버·DB 경쟁은 가능하나 다른 요청 시간을 여행 GET에 더하면 안 된다. React Query 캐시 상태에 따라 네트워크 생략 가능. |
| 여행 상세가 준비되면 공유 링크 조회 | `:257`의 `enabled: tripTitle !== undefined` | 여행 상세 뒤 시작하는 별도 요청이며 staleTime 5분이다. 표본2 평균 222ms를 여행 GET의 259ms에 포함하지 않는다. |
| 준비된 여행·계획 도구가 표시되는 단계에서 선택 일차 gap 조회 | `:512`의 `enabled` 조건 | 별도 `/api/trips/{tripId}/days/{day}/gap-recommendations`다. 서버 `TripController.java:548` → `GapRecommendationService.java:75,125` → Google Nearby Search(`:134`), 카탈로그·임베딩·개인화(`:146`)를 수행할 수 있다. 상세 GET 내부 외부 호출이 아니다. |

화면 로딩 분기는 `tripQuery.isLoading`만 검사한다(`TripDetailScreen.tsx:523`). 따라서 지도·일정의 첫 표시와 추천 준비 완료를 따로 측정해야 한다. 이번 iOS 캡처는 지난 여행·빈 일차로 보이며, gap의 실제 실행이나 대표 여행의 크기를 증명하는 캡처가 아니다. 앱 JS 실행·지도 렌더링·실제 네트워크 타임라인은 측정하지 않았다.

## 이번 로컬 측정

환경: Mac, Temurin JDK 21, Gradle Wrapper 9.5.1, 기존 테스트의 인메모리 H2 PostgreSQL 모드, OSIV 꺼짐. 루트 `.env`·개발 DB·운영 DB·8080·외부 API는 사용하지 않았다. 기존 `TripDetailQueryCountTest` 3개가 통과했고 SQL `[5,5,5,5]`, 삭제 영향 `[3,3,3,3]`을 재현했다.

세부 측정은 저장소 밖 `/private/tmp/t17-probe/.../DetailTimingProbe.java`와 `/private/tmp/t17-probe.init.gradle`로 별도 probe source set을 만들었다. 실제 앱 코드는 그대로 두고 저장소의 test runtime classpath를 재사용했다. 임시 JUnit 1개 통과. SQL 캡처는 측정 반복 중 한 요청에서만 출력했고 DEBUG SQL·매 요청 통계 로그는 끈 상태였다. fixture 생성은 측정에서 제외했다. 각 시나리오 10회 예열 뒤 순차 30회, 요청마다 독립 영속성 컨텍스트다.

| 여행 시나리오 | SELECT/요청 | JSON UTF-8 바이트 | MockMvc 전체 중앙값 |
|---|---:|---:|---:|
| 1일·2장소 | 5 | 660 | 2.061ms |
| 3일·6장소 | 5 | 1,816 | 1.520ms |
| 5일·10장소 | 5 | 2,981 | 1.178ms |
| 7일·14장소 | 5 | 4,149 | 1.193ms |
| 30일·300장소 | 5 | 81,666 | 2.299ms |

전체 요청과 별도의 반복으로 컨트롤러와 같은 순서로 각 저장소를 호출하고, 같은 DTO를 조립한 뒤 Spring MVC에 등록된 실제 JSON converter로 출력했다. 실제 HTTP 응답과 바이트 길이 동일을 단언했다. 단계별 중앙값(ms):

| 여행 | 사용자 조회 | 여행·소유자 조회 | 장소 fetch join | 일차+EAGER 재조회 | DTO 조립 | JSON converter |
|---|---:|---:|---:|---:|---:|---:|
| 1일·2장소 | 0.153 | 0.153 | 0.208 | 0.230 | 0.006 | 0.055 |
| 3일·6장소 | 0.115 | 0.110 | 0.168 | 0.156 | 0.008 | 0.060 |
| 5일·10장소 | 0.086 | 0.081 | 0.143 | 0.123 | 0.007 | 0.038 |
| 7일·14장소 | 0.064 | 0.058 | 0.136 | 0.092 | 0.005 | 0.035 |
| 30일·300장소 | 0.050 | 0.042 | 0.606 | 0.094 | 0.041 | 0.140 |

장소 이름 등은 합성값이다. 단계 측정은 저장소 호출 전체를 포함하며 JDBC 실행만 분리한 시간이 아니다.
단계별 중앙값 합은 HTTP 중앙값과 일치하지 않는다. 순차 실행의 JIT 예열로 뒤 시나리오가 빨라질 수 있어 일차별 시간 차이를 비교하지 않는다.

H2는 네트워크가 없고 Mac CPU를 쓰며 Tomcat/Caddy/실제 단말 통신도 없다. 작은 합성 데이터에서는 DTO와 JSON만으로 수백 ms가 드는 징후가 없지만 Oracle의 절대 시간·큰 memo 비용은 미측정이다. SELECT 5회 고정과 불필요한 재조회 확인이 이 측정의 주된 근거다.

실행 명령(JDK 21의 JAVA_HOME을 명시하고 같은 Gradle Wrapper main 사용):

```sh
java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain test --tests com.trova.backend.controller.TripDetailQueryCountTest --offline --console=plain
java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain probeTest --init-script /private/tmp/t17-probe.init.gradle --offline --console=plain
```

## 초안 상세 `/api/trip-drafts/{id}`: 461ms의 후보

표본2 평균 461ms·p50 440ms·p95 602ms·약 9건. 배포 전 값은 없으므로 회귀라고 부르지 않는다. P3의 `/auto` 153ms는 별도의 목록 DTO 경로다.

경로는 `TripDraftController.get`(`:130`) → `CurrentUserService.resolve` → `TripDraftRepository.findById` → 소유자 검사 → `TripPlannerService.currentJobIds`(`:112`) → `DraftResponse.from`(`TripDraftController.java:76`) → MVC JSON 변환이다. 외부 API·초안 재생성·큐 enqueue는 이 GET에서 하지 않는다.

READY/NEEDS_INPUT이고 아직 여행으로 승인되지 않은 초안에서는:

1. 사용자 SELECT 1회와 초안+EAGER 사용자 조인 SELECT 1회가 실행된다.
2. `currentJobIds`의 별도 readOnly 트랜잭션에서 원래 job ID들의 `processing_jobs ... where id in (...)`를 읽는다(`TripPlannerService.java:120`). 기본 EAGER `ProcessingJob.user` 때문에 사용자 SELECT 1회가 추가된다(`entity/ProcessingJob.java:38`).
3. 사용자의 **모든 SavedPlace**와 processingJob·그 사용자·저장 장소 사용자를 EntityGraph로 조인 조회한다(`TripPlannerService.java:123`, `repository/SavedPlaceRepository.java:17`). 필요한 것은 영상 URL과 최대 job ID인데 주소·영업시간·영상 메모 등 넓은 행까지 읽는다.
4. 모든 저장 장소에 대해 `VideoKey.of(sourceUrl)`와 최대 ID 계산을 수행한다. 초안에 든 영상 수와 무관하게 사용자 전체 저장 이력에 비례한다. 원래 job 목록 순서·중복 제거·없을 때 원래 ID fallback을 보존한다(`:127`). P3의 “최신 DONE 분석”과 달리 여기서는 **장소가 남아 있는 최대 job ID**이며 상태 필터가 없다. 최적화할 때 바꾸면 안 된다.

이번 READY 합성 초안 실측(원본 영상 1개, 각 job에 저장 장소 1개, summaryJson/draftJson은 `{}`, 예열10+반복30):

| 사용자의 전체 저장 장소 수 | SQL | 로딩 엔티티/요청 | 응답 바이트 | MockMvc 중앙값 |
|---|---:|---:|---:|---:|
| 1 | 5 | 6 | 268 | 1.305ms |
| 20 | 5 | 44 | 268 | 1.301ms |
| 100 | 5 | 204 | 270 | 1.347ms |

SQL은 고정돼도 SavedPlace·ProcessingJob 로딩이 2N+4로 증가하는 것을 확인했다. H2의 100개까지 시간이 거의 같은 결과는 운영 461ms의 원인을 확정하지 않는다. 실제 저장 장소 수·행 크기·요청 상태·summaryJson/draftJson 크기를 모른다. DTO는 이 두 JSON을 파싱하지 않고 문자열로 반환하므로 큰 JSON이면 DB 전송·역직렬화·JSON 문자열 escaping 비용이 남는다(`TripDraft.java:61,65`, `TripDraftController.java:80`).

PENDING/PROCESSING/FAILED 또는 tripId가 있는 초안은 최신 번호 계산의 본 조회를 건너뛴다(`TripPlannerService.java:115`). 다만 메서드의 트랜잭션 프록시는 통과하므로 이 분기에서 실제 커넥션·왕복이 0인지는 별도 확인이 필요하다. READY의 5회를 모든 상태에 적용하지 않는다. 앱은 PENDING/PROCESSING/상태 미확인 때 1초 폴링한다(`trova-app/src/screens/PlanTripScreen.tsx:115`); 진행 중 응답과 READY 응답을 섞어 평균을 비교하지 않아야 한다.

## 원인 후보 순위와 확신도

| 순위 | 후보 | 근거 | 확신도·남은 확인 |
|---|---|---|---|
| 1 | 여행 상세의 원격 DB 왕복 및 사용자/여행 중복 로딩 | SQL 5회 캡처, `CurrentUserService.java:62`, `TripController.java:293`, EAGER 여행 재조회. 기존 서버→DB RTT 31.5ms | **높음**: 제거 가능한 비용의 존재. 259ms에서의 정확한 비중은 중간: 이번 운영 RTT·커밋·span 시간 없음. |
| 2 | 초안 상세의 전체 저장 이력 조회와 별도 트랜잭션 | `TripPlannerService.java:112,120,123`, READY SQL 5회·엔티티 6→204 | **높음**: 데이터에 비례하는 낭비. 461ms의 주원인인지는 중간: 상태·행 수·DB span 필요. |
| 3 | 낮은 CPU 할당에서 엔티티 구성·보안 필터·동시 API 경쟁·GC/풀 대기 | fetch join 중복 칼럼, 별도 앱 요청, 1/8 OCPU. 전달 CPU 평균 1.3%는 순간 대기를 설명하지 못함 | **낮음~중간**: 가능성은 있지만 이번 운영 단계별 증거 없음. GC·Hikari·CPU와 같은 시간축 대조 필요. |
| 4 | 큰 응답의 JSON 직렬화·할당 | 여행 300장소 81.7KB, memo TEXT, 초안 JSON 두 문자열. 여행 합성 DTO 조립 최대 중앙 0.041ms, JSON 0.140ms | **낮음**: 일반 여행의 주원인이라는 증거 부족. 실제 응답 크기가 없고 초안은 큰 JSON 미측정. |

외부 API는 여행/초안 GET 내부 병목 후보에서 제외한다(호출 경로 확인 확신도 높음). gap-recommendations는 별도 화면 완료 시간과 서버 자원 경쟁의 후속 조사 대상이다. 표본1의 p95만으로 배포 회귀·쿼리 최적화 실패·GC 장애를 선언하지 않는다.

## 개선안 비교와 기대 효과

기존 [11-04 기록](../2026-11-04-prod-index.md)의 12줄에 있는 서버→Supabase RTT **31.5ms**와 [11-05 격리 지연 실험](../2026-11-05-db-roundtrips.md)의 “사용자 확인 SELECT+COMMIT 약 2왕복”을 기준으로 잡는다. 이는 11-19 RTT 재측정이 아니며 SELECT 수와 실제 소켓 왕복 수는 다르다.

조건부 산식: 제거한 SELECT마다 순차 1왕복이 줄고 나머지 조건이 같다면 **(5−n)×31.5ms**. 5→4는 31.5ms, 5→3은 63ms, 5→2는 94.5ms다. 여행 평균 259ms에 단순 적용하면 각각 약 228/196/165ms다. 이 값은 목표 검토용 추정이며 운영 보장치가 아니다. P1의 이전 1/3/5/7일 SQL 6/10/14/18→5는 이 계산상 31.5/157.5/283.5/409.5ms 감소 후보였으나 일차·장소 분포가 다른 표본 평균에 그대로 적용할 수 없다. 관측된 93ms 개선으로 평균 일차나 RTT를 역산하지 않는다.

| 개선안 | 효과·예상 SQL | 위험·보존할 동작 | 작업량(설계·회귀 포함 추정) |
|---|---|---|---|
| A. 여행 상세의 사용자 확인을 `resolveUserId`로 전환 | 예열 JWT **5→4**, SELECT만 약31.5ms 절감 후보. 기존 사용자 확인 트랜잭션 종료까지 없어지면 추가 절감 가능 | 낮음~중간. 최초 JWT는 존재 확인 추가1회; 탈퇴401·OAuth 세션·타인/없음404 유지. 여행 소유자 EAGER 중복은 남음 | 소: 반일~1일 |
| B. 여행 조회 전용 DTO + 소유자 조건 + 일차/장소 left join | 여행 헤더/소유자 1회 + 빈 일차를 포함한 일차·장소 평면 DTO 1회, 예열 JWT **5→2** 목표. 약94.5ms + 엔티티/중복 칼럼 감소 후보. 독립적 단계로 장소·일차를 분리한 **5→3**도 가능 | 중간. 빈 여행/빈 일차, 일차·방문 정렬, 날짜 null, 응답 필드와 권한 유지. 컬렉션 여러 개의 무분별한 fetch join 대신 필요한 값만 조회; 실제 왕복을 검증 | 중: 1~2일 |
| C. 초안 상세의 최신 번호 계산을 작은 projection으로 교체 | 사용자 ID 확인·초안 DTO·원본 job URL·장소가 남은 job ID/URL만 읽기. 예열 READY **5→3** 목표(초안1+원본job1+저장job 메타1), 약63ms + 넓은 장소/엔티티 로딩 감소 후보. 저장job 메타는 중복 제거 가능 | 중간. #416 재분석/위치 변경 안내, VideoKey URL 별칭, 최대ID·순서·fallback, 승인/폴링 분기를 보존. P3의 DONE 조건으로 대체하면 의미가 달라짐 | 중: 1~2일 |

**추천: P4에서 여행 상세 5회를 확인한 뒤 B를 우선 적용하고, 더 느린 초안 상세의 상태·행 크기를 확인해 C를 별도 작업으로 진행한다.** A는 가장 작은 단계지만 B와 겹치므로 둘을 독립 효과로 합산하지 않는다. 이번에는 개선 방향 비교만 하며 정책·구조 변경을 확정하거나 코드를 구현하지 않았다.

여행의 5 SELECT는 RTT 단순합 157.5ms다. #1·#2 읽기 트랜잭션의 COMMIT가 각각 추가 왕복이라면 63ms가 더 들 수 있다. 다만 JDBC readOnly 전환·드라이버 묶음·PgBouncer·서버 RTT를 재지 않았으므로 “총7왕복=220.5ms, 나머지38.5ms는 CPU”라는 확정 분해는 하지 않는다. 트랜잭션이 없어지는 효과를 위 SELECT 절감액에 다시 이중 계산하지 않는다.

## P4(T16)가 배포되면 볼 것

계측 코드는 T16 담당 범위로 남겼다. 아직 합쳐지지 않은 지표명·라벨을 가정한 실행용 PromQL은 적지 않는다. 실제 `/actuator/prometheus`의 HELP/TYPE와 담당 문서로 집계 단위·동기/비동기·실패/0쿼리 포함 여부를 먼저 확인한다.

1. 같은 배포 SHA·GET·200·URI 템플릿·시간 구간으로 **요청 수, SQL/요청 분포, HTTP 평균·p50·p95**를 함께 본다. 요청 수가 적으면 구간을 늘린다. `increase`의 소수 건수와 히스토그램 분위수는 추정치다. ID·사용자·원문 SQL을 지표 라벨에 넣지 않는다.
2. 여행 상세 SQL이 일차와 무관하게 **5에 고정**되면 P1 경로가 운영에서도 반영된 근거가 된다. 일차별로 증가하거나 5보다 큰 요청이 많다면 배포 버전·인증 경로·다른 분기·계측 범위부터 확인한다. P4가 집계형이면 일차별 상관은 알 수 없으므로 승인된 fixture별 재현/샘플 trace가 추가로 필요하다.
3. 초안 상세는 READY/NEEDS_INPUT·미승인과 진행 중/승인 뒤를 별도 샘플로 확인한다. 전자의 예상 SELECT는 **5**, 후자는 본 최신 번호 쿼리를 생략한다. 두 상태를 섞은 평균이 3~4라는 이유만으로 최적화됐다고 판단하지 않는다. T16에 상태 라벨이 없다면 기존 샘플 trace나 별도 허용된 재현으로 분기한다.
4. SQL이 5여도 느리면 **SQL 수 지표만으로 원인을 확정할 수 없다.** 샘플 trace의 JDBC/트랜잭션 시간 합, Hikari acquire/pending, GC pause·CPU·메모리 압박을 같은 구간으로 비교한다. DB span이 대부분이면 B/C의 우선순위가 강화된다. DB span이 짧고 acquire 대기가 길면 풀 경쟁, 둘 다 짧으면 CPU/직렬화·관측되지 않은 구간을 조사한다. 결과 행 수와 응답 바이트는 P4의 쿼리 수만으로 보이지 않는다.
5. B를 실제 배포한 뒤 예열 여행 요청이 **2회**(초회 사용자 확인 +1 가능)로 줄고, 비슷한 여행 크기·부하에서 HTTP 시간도 함께 줄면 가설이 지지된다. SQL만 줄고 시간이 유지되면 RTT/트랜잭션·응답 크기·CPU를 추가 측정한다. gap·share-link의 별도 URI 시간과 앱 첫 표시 시간도 분리한다.

이번 확인: 코드 경로 전체, 생성 SQL, H2 SQL 수·엔티티 수·응답 바이트·MockMvc/단계 중앙값, iOS 캡처 육안 검사. 미확인: 운영 실행 계획·현재 RTT·단계 span·풀 대기·실제 응답 크기·배포 전후 동일 fixture·앱 렌더링 시간·Android 캡처. 로컬 측정의 임시 파일은 커밋 대상이 아니며 분석 결과만 남긴다.

필수 전체 `build --offline`도 실행했다. JUnit XML 합산 **1,235개 중 1,227개 통과·2개 실패·6개 skipped**, `BUILD FAILED in 1m 39s`다. 실패는 기존 `ApiSessionCreationIntegrationTest`의 JWT/웹 세션 두 테스트가 fixture를 넣다가 H2 `Table "USERS" not found (this database is empty)`로 종료한 것이다. 해당 클래스만 다시 실행하면 **2개 모두 통과**, `BUILD SUCCESSFUL in 4s`였다. README P2/P3에도 기록된 공유 H2 컨텍스트 수명 간섭과 같은 증상이며 이 문서 작업에서 코드를 수정하지 않았다. 전체 build 통과로 보고하지 않는다.

## P5 구현 결과 (2026-11-19, #476)

B안을 적용했다. `getTrip`은 `resolveUserId`(예열 뒤 메모리 확인) → `TripRepository.findDetailHeader`(id·소유자 ID 조건, 머리 4칸) → `findDetailRows`(일차 left join 장소, `order by i.day, i.id, tp.visitOrder, tp.id`)의 scalar DTO 두 개로 응답을 만든다. 엔티티 매핑(EAGER)은 바꾸지 않았고, 날짜·이름 변경 응답의 `toDetail(Trip)`과 삭제 영향 경로는 그대로다.

- SQL(로컬 H2, 예열 JWT): 1/3/5/7/30일 모두 **5→2회**, 삭제 영향은 3회 그대로. 첫 JWT 요청은 사용자 존재 확인 1회가 더해진다.
- 응답: 이전 `toDetail`을 옮긴 참조 조립 + MVC 실제 JSON 변환기 출력과 **바이트 단위 동일**(빈 일차·날짜 미정·장소 0개·일차 0개·메모/시간/이동수단·특수문자·30일 약 300장소). 남의/없는 여행 404, 인증 없음·탈퇴 사용자 401 유지.
- 변형 검사: 방문 순서 역순 → 2건 실패, inner join(빈 일차 제외) → 4건 실패, 원복 후 통과.
- 로컬 MockMvc 중앙값(H2, 전체 예열 200회 + 시나리오별 20회 뒤 51회, 전후 각 1회 실행): 1일·2장소 0.784→0.656ms, 3일·6장소 0.708→0.564ms, 5일·10장소 0.647→0.509ms, 7일·14장소 0.672→0.550ms, 30일·300장소 1.927→1.283ms. 네트워크 없는 H2라 운영 절감(왕복 3회 ≈ 94.5ms 추정)을 대신하지 않는다. 운영 효과는 배포 뒤 표본으로 확인한다.
