# 보조 Python 수정 리뷰·머지·운영 배포 (#286)

사용자가 선택한 PR285 최종 리뷰→머지→운영 배포와 기존 REST/Spring AI 2/Kafka 비교 정리를 완료했다. [PR285](https://github.com/Trovapp/trova-backend/pull/285)를 squash 머지한 main `bd196e107c4d668b70fa77b635bac81738fcc9c5`가 운영 소스다.

## 리뷰와 검증

- 공통 종료 helper가 기존 주 파이프라인의 종료 시도/예외/5초 예산/인터럽트 보존을 유지하는지, 보조5개 payload·결과 파서·API 기록/종료코드 경계를 검토했다.
- 새 작업 폴더만 소유하고 기존 폴더/파일을 삭제하지 않으며 종료 미확인 시 입력/출력을 보존하는 경계를 확인했다. 기본2분/주5분 제한 유지. 추가 차단 문제는 발견하지 못했다.
- 머지 main의 src/pipeline-test/build.gradle은 리뷰된 `a873ca1`과 동일했다. 새 워크트리에 루트.env 없이 `./gradlew build -q` 종료0: 1010개 중1007통과·실패/오류0·기존opt-in3skip.
- 동일 소스의 실제 Kafka·별도JVM·파일H2·Python 강제종료/재수신 opt-in 통과 기록은 [기존 근거](../bench/results/auxiliary-process-recovery-20261101.json)를 재사용했다. 큐 코드 변경 없는 이번 배포에서 성능용 실험/장애 주입은 반복하지 않았다.
- [사전 검증 JSON](../bench/results/release-286-preflight-20261101.json), [수정 검증 상세](auxiliary-process-review-284-20261101.md). 단일 JVM/관찰 자식 범위이며 다중 JVM exactly-once나 SIGKILL 정리를 보장하지 않는다.

## 운영 배포와 확인

- 배포 직전 분석/초안/재구성 진행각0과 인증읽기200을 확인했다. 새 파일 전송 해시 검증·기존 JAR 백업·서비스 정지·교체·기동 순서로 실행했다.
- Java만 바뀌어 JAR만 교체했다. Python 진입 스크립트7개는 로컬 소스와 운영의 SHA-256을 대조해 동일함을 확인했다. 환경·스키마·의존성 변경 없음.
- health 대기108.095초 후UP, service active·Kafka 유지·TRACE/FILTER_AUDIT/FILTER_TEXT_ONLY 미설정 확인.
- 인증없는 공유/자동초안/장소는401·세션쿠키없음. 완성된 상세캐시143 읽기200. 새 유료 상세 조회를 실행하지 않았다.
- 실제 영상 smoke107: HTTP202→DONE·장소6. 실제 journal 경로kafka, 한글제목존재/대체문자없음. Gemini 기록3개는 성공·시도각1·대기0. 이번 검사1건은 성능/정확도 비교 표본이 아니다.
- 완료/초안안정 후 이 검사가 만든 장소6/자동초안1만 로컬 비공개0600 백업 뒤 삭제했다. 잔여각0·완료job/API기록유지. 최종 진행각0.
- 운영 JAR/이전백업/Python7개 해시를 다시 확인했다. 이번 job/select/verify/itinerary 작업폴더잔여0·현서비스시작후실제WARN/ERROR레벨0·healthUP. 나머지 보조 실행기의 강제 취소는 로컬 fixture로 검증했고 운영에서 취소를 주입하지 않았다.
- devflow 배포 이력에도 수동 배포 결과를 기록했다. 키/토큰·사용자 원문/URL은 배포 근거에 저장하지 않았다.

| 파일 | SHA-256 |
|---|---|
| 새 app.jar | `f820ab28d6ba3e66fa7b8b2abbf9fb26f3a899d4409eb4390911383b5ed0e910` |
| 이전 app.jar | `ded293aa679a44335eaf72df1679e67767d40b63d790095fc2fa499f119478b3` |
| 유지 extract_places.py | `0343eefb60d9474aeabd46d165613547514e14328a66e3f3c7b9ae7ab4f87d77` |

복구 파일: `/opt/trova-backend/releases/main-bd196e1-20261101-024348/previous/app.jar`. 이번 복구 단위는 JAR이며 Python은 유지한다. rollback은 필요하지 않아 실행하지 않았다. 이전 배포의 백업 경로와 구분한다. Python7개 전체 해시·검사·smoke·정리 결과는 [배포 원본](../bench/results/production-release-286-20261101-024348.json)에 있다.

## 성능 비교 정리

[최신 종합 문서](../bench/system-comparison-summary-20261101.md)에 실제 도입 순서, Java 클라이언트/로컬 큐/운영 A/B, 자원과 신뢰성, 아직 없는 통제 비교를 분리했다. 원본3개의 SHA-256을 포함한 재계산 JSON과 네트워크 없는 표준 Python 재계산 스크립트를 추가했다. 과거 원본은 수정하지 않고 기존 문서에 최신 종합 링크만 추가했다.

실제 순서는 REST+async→REST+Kafka→Spring AI 2+Kafka다. 클라이언트 중앙값3656.64→3636.54ms(각n4)는 속도 이득 입증 근거가 아니고, 운영 Kafka 대기는135→574ms(각n4)로 늘었다. 전체 처리시간 차이는 LLM 변동·추가시도·대기와 분리할 수 없다. 새 smoke나24시간혼합테스트를 비교에 합산하지 않았다. 비교용 운영 모드 전환/추가 실제AI호출은 수행하지 않았다.

잠자기 복구는 사용자 지시대로 보류했다. 8080/시뮬레이터/앱OTA는 조작하지 않았다. 원래 워크트리와 기존 파일을 유지했다.
