# 검증

- `gradlew.bat build`: 성공, 5분 15초, 작업 8개 실행.
- JUnit XML 228개 합계: 테스트 1,489개, 실패 0개, 오류 0개, 건너뜀 22개.
- 문서용 Python 11개 AST 검사 통과.
- 서버·앱 구현 코드는 변경하지 않았다.
- 로컬 문서 링크 누락 0개, JWT·공유 링크 문자열 패턴 검사 통과, 텍스트 diff 공백 검사 통과.
- PDF 초안 14쪽의 본문·차트·표를 렌더링하여 한글 표시와 레이아웃을 확인했다. PDF는 줄바꿈 변환을 막기 위해 이 폴더에서 binary로 지정했다.
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
