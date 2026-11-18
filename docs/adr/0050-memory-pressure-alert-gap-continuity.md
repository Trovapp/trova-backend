# 0050. 메모리 압박 경보의 짧은 계산 공백을 마지막 유효값으로 잇는다

- 상태: 승인·반영 완료 · 날짜: 2026-11-18 · 근거: #428, PR #433, [r28 검토](../monitoring/2026-11-18-memory-pressure-alert-review.md), 경보 개선 작업 요청
- 사용자 승인 `grafana-alert-bg3yd8j9d5fr4a`에 따라 2026-11-18 12:04:41 KST Cloud 반영 완료. ADR 0041의 메모리 경보 계산 공백 처리를 아래와 같이 변경했다.

## 결정

운영 UID `bg3yd8j9d5fr4a`, 이름 `HighMemoryPressure`, 폴더 `Trova`, 그룹 `trova-host`를 유지해 기존 규칙을 수정했다. 실제 Cloud provisioning export를 `monitoring/grafana-cloud/alerting/rules-trova-host.json`에 저장했고 승인 전 후보는 삭제했다. validator는 실제 저장본 A/C와 운영 식별자·정책·중복·후보 재유입을 검사한다.

PSI instant 계산값이 없을 때만 `last_over_time((원래 PSI 계산식)[5m:1m])`으로 보완한다. 유효한 현재 rate 값이 생기면 그것을 택한다. 20%·2분·평가 1분, NoData→OK·오류 KeepLast는 유지하고 스왑 보조 B를 제거했다. 수집·앱 코드·유료 서비스는 추가하지 않았다.

반영 시각·사일런스·확인과 롤백은 [runbook 7절](../runbooks/memory-pressure.md#7-공백-보완-반영-완료-2026-11-18)에 기록했다. 배포·부하 측정은 [사일런스 절차](../runbooks/memory-pressure.md#배포재시작부하-측정-사일런스)를 따른다. ADR 0041의 다른 경보 정책과 ADR 0042의 수집 방식은 유지한다.

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

보완 때문에 정상 배포/회복 뒤 오탐이 생기거나, 첫 임계값 전에 장애가 반복되거나, 여러 서버로 늘어날 때. 실제 장애 공백/복귀 전이·Slack 전달과 사일런스 만료 후 상태는 후속 확인한다.

## 확인 방법

`python monitoring/grafana-cloud/validate_host_alert.py`와 해당 단위 테스트로 저장본·합성 PromQL을 검사한다. Cloud Preview·저장 후 Normal·원본 PSI 확인은 [반영 결과](../monitoring/2026-11-18-memory-pressure-alert-review.md#cloud-반영-결과와-저장본-동기화)를 참고한다. 실제 전이·Slack 검증 완료를 뜻하지 않는다.
