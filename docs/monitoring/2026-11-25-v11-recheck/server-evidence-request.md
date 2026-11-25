# Mac 원자료 결합 항목

경로별 최종 KST 시간창은 README의 마지막 표와 request-times.txt를 기준으로 한다. 각 창의 첫 요청 이전/마지막 완료 이후 30초 스냅샷을 선택하고 실제 스냅샷 시각을 별도 기록한다. 데이터가 들어오기 전에는 값을 0으로 채우지 않는다.

| 경로 | HTTP 템플릿·method | 추가 증거 |
|---|---|---|
| 영상 5건 | POST `/api/shares`는 접수 시간만 | `trova_analysis_execution_seconds`, `trova_analysis_stage_seconds`, `trova_pipeline_stage_seconds` 및 수집·필터·지오코딩·제목 단계 로그; 자동 초안은 분석 완료와 분리 |
| 대안 30건 | GET `/api/trip-places/{id}/alternatives` | `대안 찾기 구간별 시간` 30건 대조; 임베딩 묶음 로그; 여러 일차와 조회 2개 장소 반복 조건 |
| 대화 30건 | POST `/api/conversations/{sessionId}/messages` | 실제 보이는 후보 17건 단일·11건 복수·2건 없음; `conversation-candidates.csv`와 시각 결합; `conversation-turn` 타이머·도구별 호출 구분 |
| 상세 | GET `/api/trips/{id}` | SSE ticket·연결·members/activity·빈 시간 추천 등 동반 요청을 핵심 상세 조회와 분리 |
| 수정 | PATCH `/api/trip-places/{id}/details`, `/order` | 메모·순서를 각각 집계; 버전/변경 기록 비용과 누락 JDBC 계측 주의 |
| 공동 편집 | 위 PATCH + `/api/trips/{id}/events` | 연결 완료 HTTP count는 활성 SSE 수가 아님; `trova_trip_events_fallback_total`, local-delivery-failures, deferred/discarded; Kafka `trova.trip-events` 소비 lag |
| 빈 시간 | GET `/api/trips/{tripId}/days/{day}/gap-recommendations` | 다른 경로의 자동 호출과 분리; 빈 응답·후보 포함·한도 오류 분리 |
| 오류 | GET `/api/public/trips/{token}`, `/api/trip-drafts/auto` | error-observations.jsonl의 traceId로 INFO journal 대조; 헤더·본문 일치 및 HTTP status 라벨 |

각 창마다 HTTP count·sum·버킷 차분, 요청당 SQL sum/count, 외부 API service/operation/success별 count·sum을 보관한다. 임베딩 batch 타이머는 문장별 중복 계수이므로 외부 HTTP 실호출 수로 쓰지 않는다. 프로세스 시작 시간이 달라지면 리셋 구간을 분리한다. SQL 지표에는 status 태그가 없고 동기 요청에서 명시적으로 계측한 항목만 들어간다.

운영 배포 SHA/시작 시각, 개인정보·토큰·URL 파라미터를 제거한 단계 로그만 필요하다. SSH 키·환경 파일·인증 토큰은 전달하지 않는다. 경보는 저장소 정의와 실제 운영 이름/라벨의 대응을 읽기만 하며, 테스트 경보를 발생시키지 않는다.


추가: #518 배포 기록은 Python 두 파일을 11-24 15:58:36에 복구했다고 적는다. #526에서도 Python 해시는 유지됐다. JAR SHA 외에 실제 run_pipeline.py/extract_places.py 해시와 병렬 제목 조회 적용 여부를 읽기 전용으로 확인해야 한다.
