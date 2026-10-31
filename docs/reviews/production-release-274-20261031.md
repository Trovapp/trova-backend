# 운영 배포 완료: 2ebf4b6 (#274)

2026-10-31 사용자의 순차 진행 승인에 따라 한도/감사 수정 → 운영 배포 → 기록 반영 순서로 진행했다. 배포 소스는 `2ebf4b69a08afa7ba5fd2560f95ed604fe729a76`다. #262·#263·#265에 더해 #272(재구성 한도·동시 접수), #273(관리자 조치·감사 커밋)이 포함됐다.

## 배포와 검증

- 작업 전 분석·초안·재구성 PENDING/PROCESSING 각각 0건 확인.
- JAR와 변경된 Python `extract_places.py`를 함께 전송하고 해시 확인, 이전 파일 쌍을 같은 디렉터리에 백업한 뒤 서비스 정지→교체→기동했다.
- 서비스 active, 재기동 후 약 102.4초에 health UP. 새 JAR/Python 해시와 Kafka 설정 확인. 환경 파일은 수정하지 않았고 TRACE/AUDIT/TEXT_ONLY는 활성화하지 않았다.
- 인증 없는 공유·자동 초안·장소 조회는 401이며 세션 쿠키가 생기지 않음을 확인했다.
- 기존 운영 테스트 사용자와 영상으로 공유 #105 생성: HTTP202 → DONE, 장소7개. 후보 수집/필터/선택의 작업별 기록은 각각 1시도·대기0이었다. 실제 생성된 초안까지 완료 후 확인했다.
- 테스트 장소7개와 자동 초안1개를 사용자 로컬의 비공개 파일(mode600)에 백업 후 해당 작업 소유 행만 삭제했다. 잔여 장소/초안0, 완료 작업105와 API 기록은 유지.
- 운영 관리자 계정 삭제/정지 조치를 실제 사용자에게 실행한 것은 아니다. 해당 경계는 실제 JPA/H2 트랜잭션 테스트로 검증했다.

## 파일과 복구

| 파일 | 새 SHA-256 |
|---|---|
| app.jar | `5b2ce3ea17ae1379f0cc5e62f4752a381fe124368a6c4b32bec145b641d0e8fd` |
| pipeline-test/extract_places.py | `0343eefb60d9474aeabd46d165613547514e14328a66e3f3c7b9ae7ab4f87d77` |

이전 파일 쌍의 루트: `/opt/trova-backend/releases/main-2ebf4b6-20261031-171838/previous`. 아래 두 파일을 함께 되돌려야 한다.

- `/opt/trova-backend/releases/main-2ebf4b6-20261031-171838/previous/app.jar`
- `/opt/trova-backend/releases/main-2ebf4b6-20261031-171838/previous/pipeline-test/extract_places.py`

이전 해시와 배포 경로/완료/정리 원본은 [배포 JSON](../bench/results/production-release-274-20261031-171838.json)에 있다. 실제 rollback은 필요하지 않아 실행하지 않았다. 기능 실패 시 이전 JAR만 복원해서 새 Python과 섞지 않는다. 환경 파일·DB 스키마를 바꾸는 배포가 아니므로 환경/DB rollback 작업은 포함하지 않았다.

## 로컬 검증과 범위

- 배포 main의 `./gradlew build -q`: 968개 중965통과, 실패/오류0, 명시 실행용3skip.
- 실제 Kafka/별도 JVM 강제 종료·재수신 opt-in을 별도로1개 통과·skip0 확인했다. 파이프라인 Python48개도 통과.
- [사전 집계](../bench/results/release-274-preflight-20261031.json), [재시작 결과](../bench/results/release-274-kafka-process-recovery-20261031.json).
- 실제 영상1건 성공은 배포 smoke이며 정확도/속도 개선의 통계적 입증이 아니다. 기존 REST/Spring AI 및 async/Kafka 비교 수치와 섞지 않는다.
- 단일 JVM 접수/분석 중복 방지, DB/브로커 사이 커밋 후 전달의 한계는 ADR0024~0026 그대로다.
- 운영 sleep 설정은 사용자가 보류한 상태를 유지했다.

## 후속 리뷰 상태

#267 검토의 한도 누락·감사 분리 커밋과 동시 재구성 중복 접수는 #272/#273으로 수정·배포됐다. 같은 장소 동시 캐시 미스의 상세 호출 중복, 잘못된 Expo 티켓 성공 판정, 기존 UTF-8 진행 로그 분할 디코딩은 남아 있다. 이 후속 작업을 자동으로 시작하지 않는다.
