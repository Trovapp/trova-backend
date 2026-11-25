# 검증

- `gradlew.bat build`: 성공, 5분 15초, 작업 8개 실행.
- JUnit XML 228개 합계: 테스트 1,489개, 실패 0개, 오류 0개, 건너뜀 22개.
- 문서용 Python 13개 AST 검사 통과.
- 과거 11-20 원자료로 집계기 검증: count 12, 평균 2.049378383초, p95 4.080218930초, SQL 320/12 재현.
- 현재 모든 HTTP 버킷 단조 증가 및 +Inf count 일치, 429 총 70건·5xx 증분 0·경계 lag/deferred gauge 0 확인.
- 서버·앱 구현 코드는 변경하지 않았다.
- 로컬 문서 링크 누락 0개, JWT·공유 링크 문자열 패턴 검사 통과, 텍스트 diff 공백 검사 통과.
- 최종 PDF의 본문·차트·표를 렌더링하여 한글 표시와 레이아웃을 확인했다. PDF는 줄바꿈 변환을 막기 위해 이 폴더에서 binary로 지정했다.
- 스킬의 일반 `scripts/check_docs.py`, `check_context.py`, `check_log.py`는 이 저장소에 없어 실행 대상이 아니다. 저장소 기록 위치인 `docs/experience-notes.md`에만 새 항목을 추가했다.

건너뛴 항목(통과로 세지 않음):

- `com.trova.backend.controller.PlaceListPerformanceTest`: 1
- `com.trova.backend.controller.PlaceListPostgresHttpTest`: 1
- `com.trova.backend.pipeline.AuxiliaryRunnerInterruptTest`: 4
- `com.trova.backend.pipeline.AuxiliaryRunnerLifecycleTest`: 5
- `com.trova.backend.pipeline.PipelineRunnerLaunchPolicyTest`: 3
- `com.trova.backend.queue.KafkaShareJobQueueTest`: 1
- `com.trova.backend.queue.ShareJobKafkaRoundTripTest`: 1
- `com.trova.backend.queue.ShareJobProcessRecoveryTest`: 1
- `com.trova.backend.recommendation.PersonalizationPgvectorEquivalenceTest`: 4
- `com.trova.backend.service.RouteOptimizerBenchTest`: 1

R-ID가 있는 기능 spec이 아닌 측정 문서 작업이므로 spec-check의 구현 일치율은 적용하지 않는다. PR에는 작업 지시별 산출물 증거와 제한을 별도로 기록한다.
