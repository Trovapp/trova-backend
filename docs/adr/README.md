# 결정 기록 (ADR)

"무엇을, 왜 그렇게 정했나"를 한 곳에 모은다. 한 번 정한 것을 다시 묻거나 모르고 뒤집지 않기 위해서다.

## 문서 세 곳의 역할

| 문서 | 담는 것 | 언제 쓰나 |
|---|---|---|
| `docs/adr/` (여기) | 결정과 이유, 비교한 대안, 다시 볼 조건 | 방향·정책·구조를 정할 때 |
| `docs/experience-notes.md` | 한 작업에서 무엇을 했고 무엇을 확인했나 | PR이 끝날 때 |
| `CLAUDE.md` | 지금 지켜야 할 규칙 요약 + 위 두 문서를 가리키는 링크 | 규칙이 바뀔 때 |
| `docs/superpowers/specs`, `plans` | 기능 하나의 설계와 구현 순서 | 기능을 만들기 전 |

결정이 설계 문서 안에서 나왔다면, 설계 문서는 그대로 두고 여기에는 결정만 짧게 적고 링크한다.

## 쓰는 법

- 파일: `NNNN-짧은-제목.md`, 번호는 이어서 붙인다.
- 상태: `확정` / `대기`(정했지만 아직 반영 안 함) / `대체됨 → NNNN`(뒤집을 때는 지우지 않고 새 ADR을 쓰고 여기 표시).
- 이유는 가능하면 사용자 말이나 실측 근거로 적는다.
- 에이전트는 기존 ADR과 부딪히는 변경을 하기 전에 사용자에게 결정을 바꿀지 먼저 묻는다.

```
# NNNN 제목
- 상태: 확정 · 날짜: YYYY-MM-DD · 근거: (이슈·PR·문서·사용자 말)

## 결정
## 이유
## 비교한 대안
## 다시 볼 조건
```

## 목록

| 번호 | 결정 | 상태 | 날짜 |
|---|---|---|---|
| [0001](0001-decision-records.md) | 결정 기록·작업 기록·지침의 역할 나누기 | 확정 | 2026-10-08 |
| [0002](0002-cost-zero-free-tiers.md) | API 비용 0원, 무료 티어 + 재시도 | 확정 | 2026-08 |
| [0003](0003-validate-pipeline-first.md) | 파이프라인 검증 먼저, 그다음 API·배포 | 확정 | 2026-08 |
| [0004](0004-stt-gemini-audio.md) | STT는 Whisper 셀프호스팅 대신 Gemini 오디오 직접 입력 | 확정 | 2026-08-20 |
| [0005](0005-deploy-e2-micro-systemd.md) | 배포는 Oracle E2.1.Micro + systemd, k3s는 보류 | 확정 | 2026-09 |
| [0006](0006-places-without-coordinates.md) | 좌표 없는 장소는 목록에 남기고 "위치 확인 안됨" 표시(2026-10-18 사용자가 바로잡기 보완) | 확정 | 2026-09-28 |
| [0007](0007-travel-pass-bm.md) | 수익 모델: 무료 + 여행 패스 | 대기 | 2026-10-05 |
| [0008](0008-auto-draft-needs-approval.md) | 공유하면 일정 초안까지만 자동, 여행은 승인 후 | 확정 | 2026-10-07 |
| [0009](0009-code-style-as-is.md) | 코드 스타일은 지금 쓰는 방식 유지, 포맷 전환 보류 | 확정 | 2026-10-08 |
| [0010](0010-api-docs-springdoc-local-only.md) | API 문서는 springdoc으로 자동 생성, 운영 서버에서는 끈다 | 확정 | 2026-10-08 |
| [0011](0011-context7-mcp.md) | 라이브러리 문서는 Context7 MCP로 조회, 코드 탐색 MCP는 보류 | 확정 | 2026-10-08 |
| [0012](0012-admin-dashboard-web.md) | 관리자 운영 대시보드는 웹으로, 조치는 감사 기록과 함께, AI는 무료 한도와 로컬 플러그인으로 | 확정 | 2026-10-11 |
| [0013](0013-pwa-before-app-store.md) | 앱스토어 전에는 앱(Expo) 웹 버전을 PWA로, 아이폰 링크 넣기는 단축어로 | 확정 | 2026-10-12 |
| [0014](0014-admin-actions-and-suspension.md) | 관리자 조치에 사용자 상세(가린 값)·계정 정지 추가, 조회도 기록, 멈춘 작업 정리는 빼기 | 확정 | 2026-10-12 |
| [0015](0015-prod-metrics-via-ssh-tunnel.md) | 운영 서버 지표는 서버 안에서만 열고, Mac의 Prometheus가 SSH 터널로 가져간다 | 확정 | 2026-10-13 |
| [0016](0016-pwa-web-push.md) | PWA에 표준 웹 푸시(VAPID)로 일정 초안 준비·여행 날 비 소식 알림 (0013 일부 변경) | 확정 | 2026-10-14 |
| [0017](0017-android-native-app.md) | 안드로이드는 네이티브 앱(같은 Expo 코드, EAS 빌드·Expo 푸시), 아이폰은 PWA (0013 일부 변경) | 확정 | 2026-10-14 |
| [0018](0018-android-ota-updates-eas-update.md) | 안드로이드 앱은 무선 업데이트(EAS Update, fingerprint·채널·올리기 전 서버 주소 검사)로 JS 변경을 내보낸다 | 확정 | 2026-10-15 |
| [0019](0019-admin-web-no-cloudflare-access.md) | 관리자 웹 앞의 Cloudflare Access는 붙이지 않는다, 대신 관리자 카카오 계정 2단계 인증 (0012 일부 변경) | 확정 | 2026-10-20 |
| [0020](0020-spring-ai-for-gemini-calls.md) | Gemini 호출은 Spring AI 2(google-genai)로, 재시도는 우리 코드 한 곳에서만 | 확정 | 2026-10-30 |
| [0021](0021-share-job-queue-kafka-optional.md) | 영상 분석 시작은 교체 가능한 대기열로 — 운영은 Aiven 무료 Kafka, 브로커가 안 되면 서버 안 대기열로 | 확정 | 2026-10-30 |
| [0022](0022-pipeline-regression-eval-evalflow.md) | 장소 추출 회귀 측정은 evalflow 세트로, 정답은 eval/extraction/answers.csv 한 곳에서 생성 | 확정 | 2026-10-31 |

| [0023](0023-worktree-explicit-db-env.md) | 워크트리 루트 DB 환경은 자동 복사하지 않고 명시적으로 준비한다 | 확정 | 2026-10-31 |
| [0024](0024-single-jvm-analysis-exclusion.md) | 단일 JVM의 중복 분석은 선행 완료를 기다린 뒤 DB 상태를 재확인 | 확정 | 2026-10-31 |
| [0025](0025-replan-admission-quota.md) | 재구성 새 접수는 기존 장소 요청 한도에 포함하고 단일 JVM에서 직렬화 | 확정 | 2026-10-31 |
| [0026](0026-admin-action-atomic-audit.md) | 관리자 DB 변경과 실행 감사는 함께 커밋하고 큐 전달은 커밋 뒤에 | 확정 | 2026-10-31 |
| [0027](0027-place-details-single-flight.md) | 같은 장소의 진행 중 상세 생성을 단일 JVM에서 공유 | 확정 | 2026-10-31 |
| [0028](0028-python-process-lifecycle.md) | 보조 Python도 소유 프로세스 종료 확인 뒤 출력·파일 처리 | 확정 | 2026-11-01 |
| [0029](0029-analysis-finalization-transaction.md) | 분석 결과 교체와 완료를 한 트랜잭션으로 저장 | 확정 | 2026-11-01 |

| [0030](0030-opening-hours-inflight-sharing.md) | 영업시간은 진행 중인 동일 조회만 공유 | 확정 | 2026-11-01 |

| [0031](0031-known-non-destination-labels.md) | 명시된 비장소 이름만 좁은 출력 점검으로 제외 | 확정 | 2026-11-01 |

| [0032](0032-monitoring-persistence-and-scrape-status.md) | 로컬 모니터링 기록을 보존하고 수집 중단을 업무 지표 0과 구분한다 | 확정 | 2026-11-01 |

| [0033](0033-analysis-stage-elapsed-metrics.md) | 영상 분석 Java 단계와 실행 시간을 결과별로 계측한다 | 확정 | 2026-11-01 |

| [0034](0034-place-list-scalar-read.md) | 장소 목록에 필요한 값만 조회하고 요청 내 URL 정규화를 공유한다 | 확정 | 2026-11-02 |
| [0035](0035-place-list-user-order-index.md) | 장소 목록 사용자·정렬 복합 인덱스 | 확정 | 2026-11-02 |
| [0036](0036-known-users-id-cache.md) | 조회 API의 사용자 존재 확인을 메모리에 기억한다 | 확정 | 2026-11-05 |
| [0037](0037-prod-jvm-memory-and-capacity-decisions.md) | 운영 java의 GC·스왑 설정과 동시 사용자 한계 판단 | 확정 | 2026-11-08 |
| [0038](0038-analysis-cpu-priority-and-download-concurrency.md) | 내려받기 영상 분석은 동시에 1개만 (낮은 CPU 우선순위는 운영 측정 뒤 끔) | 확정 | 2026-11-08 |
| [0039](0039-direct-otlp-metrics-and-external-health-check.md) | 운영 지표는 서버가 직접 보내고, 서버 다운은 외부 헬스 체크로 감지한다 (alloy는 평소 끔) | 확정 | 2026-11-10 |
| [0040](0040-prod-heap-cap-144m.md) | 운영 java 힙 상한을 384MiB에서 144MiB로 낮춘다 (0037의 힙 결정을 바꿈) | 확정 | 2026-11-11 |
| [0042](0042-host-metrics-from-proc-in-app.md) | 서버 메모리·스왑·PSI·CPU steal은 앱이 /proc에서 읽어 지표 전송에 싣는다 | 확정 | 2026-11-11 |
