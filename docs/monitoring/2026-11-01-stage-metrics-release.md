# Java 분석 단계 계측 운영 적용

사용자 요청에 따라 후보 선택·검증·저장 및 분석 실행 시간 계측을 운영에 적용했다.
과거 job 125의 11.844초 구간을 소급 분해한 것은 아니며, 다음 분석부터 원인을 좁힐 수 있게 한 변경이다.

- 코드 커밋: `ed9adde` (`feat/#305-monitoring-foundation`). 로컬 기능 브랜치이며 main 병합은 아직 하지 않았다.
- 운영 기준 JAR: `394ba70011cb7f5a1612c5f293aeb63890352babbe3f88c1ebce9fd9b760f88a`.
- 현재 HEAD의 기존 런타임 소스는 운영 배포 기록의 main `fff19cd`와 같았다. 이번 런타임 수정은
  `PipelineStageMetrics`, `PlaceExtractionService` 두 파일이다.
- 기존 검증된 배포 도구 `docs/bench/scripts/deploy_operational_fix.py`를 재사용했다.
  도구가 JAR과 extract_places.py를 함께 교체하지만 Python은 기존과 동일한 해시이며 코드 변경은 없다.
- 진행 중 분석/초안/재구성 0건을 확인하고 백업 후 교체했다. 환경·큐 설정·스키마는 변경하지 않았다.
- 정상 health 복귀 111.274초, rollback 없음. 인증 없는 보호 API는 계속 401, 세션 쿠키 없음.
- 운영 원본 백업: `/opt/trova-backend/releases/operational-296-ed9addeeaa-20261101-233635/previous`.

## 검증 결과

- `./gradlew build` 통과: 총 1033, 실패 0, 오류 0, 기존 선택 실행 제외 3.
- MockClock 단계 합/완료 뒤 시간 제외/중복 종료 검사, 실제 서비스의 실패·중복·취소 분기 검사 통과.
- 운영 `/actuator/prometheus`에서 단계별 24개 + 실행 결과별 4개 Timer의 count 표본 확인.
- 추가 히스토그램 없음. 새 지표 count는 모두 0: 이번 배포 검증에서 AI/영상 분석 요청을 만들지 않았다.
- 운영 Prometheus 대상 UP, Grafana 16개 패널의 파일/실제 설정 일치 및 새 4개 PromQL 정상.
- 최종 진행 작업 0건, 마지막 job 126 유지. 사용자가 제출한 두 영상의 데이터를 지우지 않았다.
- 운영 저부하 테스트는 실행하지 않았다.

## 읽는 법과 다음 검증

[Grafana](http://localhost:3300/d/trova-monitoring-lab) ⑤에서 Java 단계별 평균, ⑥에서 분석 실행 평균을 본다.
새 분석이 없으므로 평균 패널의 데이터 없음은 정상이다. 배포 전에 끝난 작업에는 새 지표가 생기지 않는다.
실행 시간은 큐 대기/선점 DB 처리/실패 상태 복구/자동 초안을 제외한다.
기존 Python Gemini 단계는 Java extracting 내부에 포함되므로 합산하지 않는다.

다음 정상 사용 때 한 작업의 로그와 DB 완료 시간, 새 단계별 지표를 대조한다.
이 배포는 계측 보완이며 실제 병목 확정이나 성능 향상 검증은 아니다.
다른 세션에서 main 기준으로 재배포하기 전에 이 기능 브랜치의 병합 여부를 확인해야 한다.

증거: [빌드](2026-11-01-stage-metrics-validation.json),
[배포](2026-11-01-stage-metrics-deployment.json),
[운영 지표 확인](2026-11-01-stage-metrics-postflight.json),
[측정 정의 ADR](../adr/0033-analysis-stage-elapsed-metrics.md).
