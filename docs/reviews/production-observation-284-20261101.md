# 운영 읽기 점검 (#284)

2026-11-01 02:39 KST 관찰. 운영 소스는 기존 배포 `b473f2b`이며 이번 보조 실행기 수정은 아직 반영되지 않았다. 원본 집계는 [JSON](production-observation-284-20261101.json)에 보관했다.

- service active·health UP, Kafka 유지. JAR/Python SHA-256은 #282 배포 기록과 일치. TRACE/FILTER_AUDIT/FILTER_TEXT_ONLY 미설정.
- 분석/초안/재구성 PENDING·PROCESSING 각각0, 15분 초과 진행 작업0.
- 최근24시간 분석 완료14·실패0. 알려진 테스트 작업을 제외하면0건이므로 실제 사용자 트래픽 실패율을 산출할 표본이 없다. 새 배포 시작 이후 알려진 smoke1건만 존재한다.
- 완료14건의 분석시간 중앙19.31초·p90 31.51초. 이전 배포/서로 다른 모드/알려진 테스트를 섞은 설명용 집계이며 성능 개선 비교 근거가 아니다. 새 배포 smoke1건14.45초도 비교 표본으로 사용하지 않는다.
- API 기록131행 모두 success. collect14행에 알려진 시도16회·대기합계15,000ms가 기록됐다. filter/select 각14행은 각시도14·대기0. 나머지89행은 시도/대기 정보가 없어0으로 해석하지 않는다. 로그 행 수는 HTTP 요청 수와 같다고 보장하지 않는다.
- systemd journal 최근24시간2,466줄 중 실제 WARN/ERROR 레벨 항목0, 현 서비스 시작 이후도0. 메시지의 `ERROR`4개는 초안 완료 INFO의 검증 오류 개수 표기였고, `timeout`115개는 설정 출력 등 단순 문자열 매칭이었다. 레벨 위치를 기준으로 집계해 장애 수와 구분했다. 전체 로그 수집/보존 상태 밖의 오류 부재를 보장하지 않는다.

DB는 `SET TRANSACTION READ ONLY`, statement timeout15초와 명시 운영 환경 파일로 읽고 rollback했다. 서버에서는 서비스 상태·선택된 환경 키·파일 해시·journal 집계만 읽고 공개 health GET만 실행했다. 사용자/URL/원문 오류/키/토큰을 저장하지 않았다. 분석 상태는 `coalesce(analysis_status,status)`, 완료 시간은 `analysis_finished_at-created_at`으로 계산하고 updated_at을 분석시간에 쓰지 않았다. 알려진 테스트 ID 목록과 전체/제외 집계를 JSON에서 분리했다. 제외 결과가 실제 사용자 전체를 식별하는 것은 아니다.

서비스 재시작/파일 전송/데이터 수정/실제 API 분석/잠자기 설정 복구는 수행하지 않았다.
