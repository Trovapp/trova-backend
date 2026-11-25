# 경로별 개선안 — 구현 변경 없이 제안

파일 경로는 `src/main/java/com/trova/backend/` 아래이며, 줄 번호는 기준 소스 fe29e23이다. 예상 효과는 후속 동일 조건 실험에서 확인해야 하며 실측 개선율로 제시하지 않는다.

| 경로 | 근거 파일:줄 | 제안 및 예상 효과 | 난이도·위험 |
|---|---|---|---|
| 상세 GET | controller/TripController.java:265 | 필요한 DTO/회원 표시 정보만 묶어 조회하고 작은 힙에서 응답 객체 할당량을 줄인다. 쿼리 수와 할당률을 함께 재측정한다. | 중간; 권한·멤버 표시 누락 회귀 |
| 메모 PATCH | service/TripService.java:495, service/TripChanges.java:61 | 활동 기록의 보관 수 정리를 매 쓰기에서 일정 간격 정리로 옮기면 INSERT+DELETE 비용을 줄일 수 있다. | 중간; 보관 개수 일시 초과·정리 누락 |
| 순서 PATCH | service/TripService.java:663, :685 | 전체 형제 목록 재시간 계산과 전체 saveAll 대신 실제 변경 행만 갱신할 수 있는지 검토한다. 일차 장소 수가 클수록 할당·flush 비용 감소를 기대한다. | 중간; 순서·방문시간 일관성 회귀 |
| 같이 고치기 | service/TripChanges.java:58, events/TripStreams.java:91 | 서버는 이미 controller/TripConflictHandler.java:18에서 409와 최신 상태를 보낸다. 정상 10명 여행의 작성자 3 VU에서 나온 409를 별도 집계하고, 클라이언트의 병합·재시도·메모 디바운스를 검증한다. 전파 때 여행별 구독자 인덱스를 쓰면 모든 연결 순회를 줄일 수 있다. | 중간~높음; 중복 쓰기·잘못된 권한 캐시 |
| SSE | events/TripStreams.java:26, :44, events/TripStream.java:62, :81 | 연결 수 gauge·사용자별 거부 사유·슬롯 유지 시간을 계측한다. 클라이언트의 중복 구독 취소와 재연결 backoff를 함께 적용하고, 닫힘 감지/heartbeat 주기를 검증한다. 3개 제한 상향 전 유령 슬롯 원인을 먼저 확인한다. | 중간; heartbeat 단축은 DB·쓰기 트래픽 증가 |
| 대안 | recommendation/AlternativeFinderService.java:213, recommendation/PlaceEmbeddingService.java:56 | 동일 후보의 미생성 임베딩을 한 번의 in-flight 작업으로 합치고 TTL·실패 backoff를 둔다. 첫 검색이 겹칠 때 외부 호출·힙 할당 중복을 줄인다. | 중간; 오래된 후보·실패 캐시 고착 |
| 말로 바꾸기 | conversation/ConversationService.java:49, :65 | 독립적인 모델 호출과 도구 실행 지연을 분리 계측하고, 429에는 프로젝트·모델별 circuit breaker를 둔다. 이번 2 VU에서 제공자 15회/분 한도 1건이 HTTP 200 fallback이었다. fallback·모델별 quota 비율을 별도 SLI로 삼고 요청량을 모델 호출 수 기준으로 제어한다. | 중간; 모델별 한도 혼동·정상 요청 차단 |
| 빈 시간 | controller/TripController.java:595, service/DailyQuotaService.java:92, recommendation/GapRecommendationService.java:96 | 여행 버전·일차별 추천 캐시와 in-flight 합치기를 적용하고 자동 재조회 디바운스를 둔다. 한도가 화면 진입에도 소모되므로 캐시 적중 시 한도 차감 정책을 검토한다. | 중간; 최신 일정 누락·과금 방어 약화 |
| 관측 부하 | config/HostProcMetrics.java:108, :127, :167 | 파일 텍스트 캐시는 이미 있다. Gauge마다 반복하는 정규식·문자열 파싱 결과도 수집 주기별 스냅샷으로 재사용하고 Kafka 메트릭 시리즈를 필요한 범위로 제한하는 방안을 검증한다. 3초 수집의 스레드 덤프에서 실제로 해당 파싱과 Prometheus 인코딩이 실행 중이었다. | 중간; 지표 신선도·누락 위험 |
| 서버 자원 | reproduce/compose.yml | 후기 40 VU는 CPU 약 0.122코어·기간 97~99% throttled였다. 먼저 0.25 CPU를 같은 부하 ABBA로 검증하고, 프로세스 RSS와 힙 외 메모리를 관측하며 컨테이너 메모리 조합을 비교한다. 현재 결과만으로 증설 효과의 배수를 단정하지 않는다. | 낮음~중간; 비용·네이티브 메모리 부족 |
| 관측 | config/RequestDbQueryMetrics.java:76, service/TripChanges.java:61 | 직접 JDBC 활동/권한 SQL을 요청당 SQL 카운터에 포함하고 SSE 연결 수·전파 지연을 추가한다. 누락된 비용의 원인 분해가 가능해진다. | 중간; 중복 계측·고카디널리티 |
| 경보 | monitoring/grafana-cloud/alerting/rules-trova-http.json | HTTP p95에서 `/api/trips/{id}/events` 연결 수명을 제외하고 SSE 거부·전파 지연 경보를 분리한다. 장기 연결을 느린 API로 해석하는 오경보를 줄인다. | 낮음; 잘못된 정규식으로 일반 API 누락 |

운영의 SSE 429 51건·빈 시간 429 19건은 오늘 재측정 원자료의 관측이다. 격리에서 같은 조건의 제한을 재현하더라도 각 운영 요청의 당시 활성 슬롯·남은 quota·trace별 상태가 없어 전부 같은 원인이라고 단정할 수 없다. 운영 Python이 #518 이전이라는 사실은 영상 경로에 관한 비교 제한이며, 이번에는 영상을 부하 대상에서 제외했다.
