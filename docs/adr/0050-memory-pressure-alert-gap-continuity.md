# 0050. 메모리 압박 경보의 짧은 계산 공백을 마지막 유효값으로 잇는다

- 상태: 대기 · 날짜: 2026-11-18 · 근거: #428, PR #433, [r28 검토](../monitoring/2026-11-18-memory-pressure-alert-review.md), 경보 개선 작업 요청
- 사용자 승인·Cloud 반영 대기 중인 결정안이다. 현재 Cloud 정책(ADR 0041)을 변경하지 않았다.

## 결정

후보는 복구 경로 `alerting/` 밖의 `proposals/high-memory-pressure-gap.json`에만 두고 현재 Cloud 저장본 `rules-trova-host.json`은 유지한다. 후보 전용 UID `candidate-memory-pressure-gap`, 이름 `[Candidate] HighMemoryPressure gap continuity`와 `isPaused: true`를 사용한다. 운영 UID `bg3yd8j9d5fr4a`와 이름 `HighMemoryPressure`는 기존 규칙에만 남긴다. 후보 전용 그룹 `trova-host-candidate`도 운영 그룹과 구분한다. 자동 Cloud provisioning/import는 레포에 없지만, 수동 복구 때 폴더 전체를 읽는 실수를 막기 위해 경로와 식별자를 함께 분리한다. validator는 두 영역의 충돌·후보 재유입을 거부한다.

후보의 PSI instant 계산값이 없을 때만 `last_over_time((원래 PSI 계산식)[5m:1m])`으로 보완한다. 유효한 현재 rate 값이 생기면 그것을 택한다. 20%·2분·평가 1분, NoData→OK·오류 KeepLast는 유지하고 후보에서 스왑 보조 B를 제거한다. 수집·앱 코드·유료 서비스는 추가하지 않는다. 승인 후 기존 규칙을 수정하고 export 갱신·후보 삭제·롤백하는 단계는 [runbook 7절](../runbooks/memory-pressure.md#7-승인-대기-변경-cloud-미반영)에 따른다. 후보의 UID·이름·그룹·일시중지 상태는 운영에 복사하지 않는다.

승인 후 반영할 때의 배포·부하 측정 사일런스는 [runbook 승인 대기 절](../runbooks/memory-pressure.md#후보-승인반영-시-필요한-사일런스)을 따른다. ADR 0041의 다른 경보 정책과 ADR 0042의 수집 방식은 유지한다.

## 이유

r28 첫 Pending(15:49:10) 이후 두 번 계산 공백으로 리셋돼 15:57:10에야 발화했다. 외부 Synthetic은 이미 15:40:50에 발화했다. 누락된 다운 감지가 아니라 PSI 경보 연속성 문제를 고친다. 마지막 계산값 보완이면 기록 기준 약 6분 앞당길 것으로 예상하지만 원시 r28 시계열 재생이나 Cloud 검증 결과는 아니다.

4분 넘는 공백 뒤 첫 표본 하나로는 rate를 계산할 수 없어 다음 표본까지 약 1분은 이전 보완값일 수 있다. 원래 rate 창과 보완 창을 합쳐 약 10분 오래된 압박이 남을 수 있다. 처음부터 무자료이거나 보완이 만료되면 별도 끊김 경보·Synthetic이 필요하다. 높은 PSI 직후 재시작도 자동 구분하지 못해 사일런스가 필요하다.

## 비교한 대안

| 대안 | 판단 |
|---|---|
| NoData→Alerting | 배포 공백·조회 장애를 메모리 압박으로 오인하고 MissingSeries와 동작 차이도 있음 |
| 과거 최대값 유지 | 높은 값→낮은 값→공백일 때 마지막 정상 관측을 무시함 |
| 대기 시간·임계값 축소 | 정상 회차의 짧은 압박도 울릴 수 있음 |
| 기존 외부 경보 유지 | 가장 먼저 울렸으므로 1차 대응 신호로 유지하되 PSI 연속성은 별도로 개선 |

자세한 비용·오탐·예상 시간 비교와 승인할 변경·롤백은 [보고서](../monitoring/2026-11-18-memory-pressure-alert-review.md)에 있다.

## 다시 볼 조건

보완 때문에 정상 배포/회복 뒤 오탐이 생기거나, 첫 임계값 전에 장애가 반복되거나, 여러 서버로 늘어날 때. 승인 전에는 Cloud가 기존 규칙을 계속 사용한다.
