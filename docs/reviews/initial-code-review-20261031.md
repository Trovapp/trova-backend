# 저장소 첫 점검 (#260)

## 범위와 한계

Java 운영 코드 259개와 테스트 166개, Python 35개(이번 변경 포함)를 전체 파일 목록과 정적 검색으로 분류했다. 상세히 읽은 흐름은 공유 요청·대기열·파이썬 프로세스·2단계 추출·카카오 지오코딩·저장·자동 초안, 인증 설정, 일정 그래프와 AI 호출 설정이다. 모든 파일의 모든 줄을 상세 감사한 것은 아니며, 아래 결과는 첫 구조 점검이다. 관리자·푸시·추천·재계획의 개별 분기는 추가 상세 검토 대상이다.

## 구조

- controller/security: JWT·OAuth2·공유 키 인증, 사용자별 저장소 조회, 관리자 권한 경계.
- queue/service: ShareJobQueue → async 또는 Kafka → PlaceExtractionService → 단계별 lifecycle 트랜잭션.
- pipeline: Python 프로세스와 진행/호출 기록 파싱. Python이 후보 수집·선별을 수행.
- geocoding: 카카오 주소·이름 검색, 대안 선택, 불확실한 좌표 검증. 저장 시 matchedName으로 이미 이름을 보정한다.
- planner/conversation/replan: 일정 초안 생성·코드 보정·검증·AI 재수정, 대화와 일정 교체.
- recommendation/embedding/weather/congestion/push/admin: 추천 데이터와 외부 연동, 알림, 운영 지표.

## 발견한 후속 검토 사항

### P1: Kafka 전송 타임아웃 후 async 폴백의 중복 처리 가능성

`KafkaShareJobQueue.enqueue()`는 send future를 5초 기다리다가 실패하면 async로 넘긴다. 타임아웃은 브로커 수신 취소를 보장하지 않는다. 늦게 도착한 Kafka 메시지와 async가 같은 작업을 처리할 가능성이 있다. `ShareJobListener`는 PROCESSING도 처리 대상으로 통과시키며 `ProcessingJobLifecycleService.markProcessing()`에는 원자적 선점 조건이 없다. 후속 제어 시나리오에서 async/Kafka 두 경로 진입을 재현했다. 실제 Gemini 호출·저장 중복까지 실행하지 않았으며 #261에서 추적한다. 이번 작업에서는 대기열 정책을 변경하지 않았다.

### P2: 진행 로그의 UTF-8 분할 디코딩

`StderrProgressTail.poll()`은 읽은 바이트 조각마다 UTF-8 문자열로 바꾼 뒤 줄을 붙인다. 한국어 문자 중간에서 읽기가 끊기면 앞뒤 조각이 각각 대체문자로 변해, 나중에 줄을 합쳐도 복구되지 않는다. 바이트 단위 줄 조립 또는 상태를 유지하는 디코더가 필요하다. 최종 결과 파싱과 별도인 중간 진행 표시 문제다.

### 평가와 운영 결과의 경계

기존 70%대 결과는 지오코딩 전 AI 원문 이름 평가다. 실제 저장 시 카카오 이름 보정이 이미 있으므로 서비스 최종 정확도로 제시하면 안 된다. 기존 부분 문자열 채점은 다른 지점·포괄 지명과 중복 출력을 정답으로 인정할 수 있다. 이번에 엄격 채점과 legacy 채점을 분리하고 별칭 후보는 사람 검토 대상으로만 남겼다.

## 이번 구현과 검증

- 엄격 채점: Unicode 정규화·공백 제거 후 정확한 이름을 1:1로 대응. 중복 예측은 정밀도 감점. 미확인 별칭 자동 승인 없음.
- 기존 부분 문자열 recall/precision은 ADR 0022대로 유지하고, strict_recall/strict_precision을 추가. 기본 정책 변경은 별도 결정 대상. 서로 다른 채점 버전 간 수치를 성능 개선으로 해석하면 안 된다.
- 기존 원본 10건 오프라인 재채점: 완전 통과 2/10, recall 0.6637, precision 0.6418. API 호출 없음.
- 진단: opt-in 후보/최종 이름 추적. unmatched는 철자 보정을 포함하므로 제외로 단정하지 않는다. 별도 audit 옵션에서 후보별 판단 이유·근거를 모델에 요청하며, 모델 보고값임을 명시한다. 운영 기본값은 꺼짐.
- Java 전체 빌드: 930 tests, failures/errors/skipped 0. Python 41 tests 통과. Node 채점 회귀 검사 통과.
- promptfoo echo 제공자로 기존 출력 10개를 외부 API 없이 재채점: 실행 오류 0, 엄격 완전 통과 2/10. 이때 종료 코드 100은 예상한 채점 실패 8건 때문이다.
- 후속 사용자 선택으로 실제 영상 3개를 검증했다. 상세: `docs/eval/audit-validation-20261031.md`. 초기 진단 1건 실패와 판단/최종 결과 불일치를 확인해 진단 실패를 분리했다. 수정한 프로토콜의 실제 재측정과 운영 반영은 아직 하지 않았다.
