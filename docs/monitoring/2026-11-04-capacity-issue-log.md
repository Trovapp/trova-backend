# 운영 인덱스 반영·한계 찾기 — 문제 기록

작업 중 생긴 문제와 원인, 해결안, 사용자 선택을 시간 순으로 남긴다.
설계: `docs/superpowers/specs/2026-11-04-capacity-limit-and-prod-index-design.md`

## #1 git 명령이 동작하지 않음 (2026-11-04, 로컬)

**현상**: 워크트리에서 `git status` →
`fatal: not a git repository: /Users/gimtaehyeong/Downloads/buildlog/trova-backend/.git/worktrees/76116-…`.
같은 세션 초반에는 `git status`/`git fetch`가 정상이었다.

**확인한 것**
- 워크트리 `.git` 파일은 정상 경로를 가리킨다.
- `ls ~/Downloads/buildlog/trova-backend/.git/worktrees/` → `Operation not permitted`.
- 같은 폴더에서 `git worktree list` → `Unable to read current working directory: Operation not permitted`.

**원인 추정**: 파일이 사라진 게 아니라 macOS 개인정보 보호(TCC)가 이 앱(Orca)의 **다운로드 폴더 접근을 막고 있다**.
같은 유형의 문제가 Desktop에서 있었다(메모리 `reference_orca-desktop-access`). 세션 중간에 권한이 바뀐 이유는 확인하지 못했다.

**해결안**
1. (권장) 시스템 설정 → 개인정보 보호 및 보안 → 파일 및 폴더(또는 전체 디스크 접근)에서 Orca의 “다운로드 폴더” 허용 후 Orca 재시작.
   레포 위치를 바꾸지 않아 다른 워크트리에 영향이 없다.
2. 메인 레포를 다운로드 폴더 밖(예: `~/dev/trova-backend`)으로 옮기고 `git worktree repair`. 근본 해결이지만 다른 워크트리·스크립트 경로가 모두 바뀐다.
3. 당분간 git이 필요 없는 일(측정 스크립트 작성·로컬 테스트)만 먼저 하고, 커밋은 권한 해결 후 한 번에.

**영향**: Task 0(이미지 복원·충돌 해결·커밋·푸시)과 이후 모든 커밋이 막힌다.

**사용자 선택**: 1번(다운로드 폴더 권한 허용 후 Orca 재시작). 실행 방식은 Claude가 직접 순서대로(Native).
**결과**: 사용자 확인 — Orca는 이미 전체 디스크 접근 허용 상태인데도 “저번부터 계속” 안 됨. 추가 조사:

**추가 조사 (2026-11-04)**
- Claude Code 샌드박스를 끄고 실행해도 `ls ~/Downloads` → `Operation not permitted`. 샌드박스 원인 아님.
- 이 셸의 부모 프로세스: `zsh ← claude ← zsh ← login ← Orca Terminal Host`(PID 73679, 부모 launchd).
  실행 파일은 `~/Library/Application Support/orca/daemon-host/macos/terminal-host-3ac457…/app.noindex/Orca Terminal Host.app`.
- 이 터미널 호스트는 **2026-10-30 18:16부터 계속 살아 있다**. Orca 앱 창(`/Applications/Orca 2.app`, 11-03~04 시작)을
  재시작해도 터미널 세션 유지를 위해 이 호스트는 따로 계속 돈다.
- 서명은 정상(`codesign --verify` 통과), 번들 ID `com.stablyai.orca`, 팀 ID는 Orca 2.app과 같다.
- 같은 폴더에 10-30 10:57 생성된 다른 버전 호스트(`terminal-host-53e64c…`)도 있다 → Orca 업데이트마다 새 호스트가 만들어진다.

**원인 추정(확정 아님)**: 전체 디스크 접근 변경은 보통 **프로세스를 새로 띄워야** 적용된다. 실제로 파일에 접근하는 건
Orca 창이 아니라 10-30부터 떠 있는 터미널 호스트라서, Orca를 재시작해도 이 호스트는 예전 권한 상태 그대로다.
업데이트마다 호스트 위치가 바뀌어 “저번부터 계속” 재발하는 것도 이걸로 설명된다. 세션 초반에 git이 됐던 이유는 확인하지 못했다.

**해결안(갱신)**
1. (권장) 다른 Orca 터미널 작업이 없을 때 터미널 호스트를 종료(`kill 73679`) → Orca가 새로 띄움 → `git status` 확인.
   빠르지만 **이 세션 포함 Orca의 모든 터미널 세션이 끊긴다**. 다른 세션 작업 중이면 안 됨.
2. 메인 레포를 다운로드 폴더 밖(예: `~/dev/trova-backend`)으로 이동 + `git worktree repair`. 다운로드 폴더는 macOS 보호 폴더라
   밖으로 옮기면 권한 문제가 재발하지 않는다. 대신 모든 워크트리 경로 갱신, Claude 메모리 폴더(현재 레포 경로 기준) 이동 필요.
3. 당분간 git 작업만 macOS 기본 터미널(Terminal.app)에서 사용자가 실행.

**사용자 선택(갱신)**: 1번. 사용자가 `sander` Claude 세션 종료를 확인 → 2026-11-04 터미널 호스트(PID 73679) 종료.
같이 종료된 것: Codex 세션들(sander·이력서-정리·관리자-페이지-터미널·safe-korea), trova-app Expo 8081, safe-korea wrangler 8787 등.
**재시작 후 확인할 것**: `ls ~/Downloads`, 이 워크트리 `git status`. 되면 #1 해결로 기록, 안 되면 해결안 2·3으로.

**해결 확인 (2026-11-04 16:33)**: 터미널 호스트 종료 후 Orca가 새 호스트(16:32:50 시작)를 띄움.
새 세션에서 `ls ~/Downloads` 정상, 워크트리 `git status` 정상. → **#1 해결**.
원인 추정(권한 변경이 오래 떠 있던 터미널 호스트에 반영되지 않음)과 일치. 재발 시: Orca 업데이트·권한 변경 뒤
터미널 호스트를 다시 띄워야 한다(Orca 창 재시작만으로는 호스트가 안 바뀜).

## #2 테스트 계정 토큰을 Claude가 준비할 수 없음 (2026-11-04, 로컬)

**현상**: 1단계 통제 부하(`--mode places-load`)에는 운영 테스트 계정(user 1)의 단기 토큰 파일이 필요하다.
Claude가 qaflow 토큰 설정(`~/.config/qaflow/`)을 읽으려 하자 Claude Code 자동 권한 분류기가
“자격증명 생성(Credential Materialization)”으로 막았다.

**원인**: 실행기는 의도적으로 토큰을 발급하지 않는다(`run_observed_smoke.py` 설계). 토큰 발급은 운영 자격증명을 다루는 일이라
자동 모드에서 차단된다. 11-02 측정 때도 토큰은 비공개 임시 파일로 전달했다.

**해결안**
1. (권장) 사용자가 직접 테스트 계정 단기 토큰을 비공개 임시 파일(권한 600)로 만들고 **경로만** 알려준다. Claude는 내용을 읽거나 출력하지 않고
   `--token-file`로만 넘기며, 측정 후 삭제를 안내한다.
2. 사용자가 Claude Code 권한 규칙에 qaflow 토큰 도구 실행을 허용 → Claude가 직접 발급. 편하지만 자격증명 권한이 넓어진다.
3. 토큰 없이 진행: `/actuator/health`만 비교 — DB를 거치지 않아 인덱스 비교 의미가 없다(비권장).

**사용자 선택**: Claude 직접 발급을 요청 → 분류기 재차단. 기존 `~/.config/qaflow/trova-token` 재사용안 제시 후 사용자 선택 2번(사용자가 qaflow로 새로 발급, 경로만 전달). 대기 중.

## #3 운영 쿼리가 이미 0.26ms — 인덱스 효과가 k6에서 안 보일 가능성이 매우 큼 (2026-11-04, 운영)

**현상**: 운영 `saved_places`는 277행·184kB. 테스트 계정 장소 목록 쿼리 EXPLAIN ANALYZE 실행 0.264ms(계획 3.581ms),
Seq Scan으로 235행만 걸러낸다(`docs/monitoring/2026-11-04-prod-index/before-explain.txt`).
반면 11-02 운영 1 VU 장소 목록 응답 p95는 524ms였다. **응답 시간의 99.9% 이상이 DB 쿼리 밖**이다.

**의미**: 인덱스로 DB 쿼리가 0.26ms→0.1ms가 되어도 k6 p95(수백 ms, 네트워크 편차 큼)에서는 구분할 수 없다.
설계 때 예상한 “차이가 작을 수 있음”이 사실상 확정. 대신 **운영 응답 500ms가 어디서 생기는지**가 새 병목 후보다
(한국↔도쿄 네트워크·TLS, 1/8 OCPU에서의 JVM 처리, 앱↔Supabase 왕복 등). 2단계 보정과 면접 이야기에 더 중요한 숫자다.

**해결안**
1. (권장) 계획대로 통제 부하 전/후 측정은 하되, 결론을 “운영 규모에서는 차이 없음(쿼리 0.26ms), 데이터가 늘면 격리 실험처럼 커짐”으로 기록.
   여기에 운영 응답 시간 분해(서버 내부 처리 시간 vs 네트워크)를 추가해 500ms의 출처를 찾는다.
2. 통제 부하는 생략하고 인덱스 전/후 EXPLAIN + 배포 후 실사용 관찰만. 운영 부하를 안 주지만 Grafana 전/후 그래프 근거가 약하다.
3. 인덱스 반영은 그대로 하고, 운영 측정은 2단계 끝에 병목 수정분과 함께 한 번에.

**사용자 선택**: 1번(계획대로 전후 측정 + 운영 응답 시간 분해 추가).

## #4 인덱스 전 측정 중 요청 1건 3초 시간 초과 — 원인은 5초짜리 Full GC (2026-11-04 18:10, 운영)

**현상**: `--mode places-load` 18:07:16~18:10:05 KST. 359건 중 358건 정상(중앙 273ms, p95 425ms, 평균 291ms),
2분 49초 시점 18:10:05에 1건이 3,006ms로 시간 초과(status 0) → 도구가 `SERVER_ERROR`로 중단(계획 3분 중 2분 49초).
자원 감시는 정상(최소 여유 메모리 261MiB, 스왑 없음). 원본 `docs/monitoring/2026-11-04-prod-index/before/`.

**Prometheus로 확인한 원인**(`before/prometheus.json`)
- 18:10:05에 `jvm_gc_pause_seconds_max{action="end of major GC", gc="MarkSweepCompact"}` = **4.973초**. 같은 시각 시간 초과 발생.
- 같은 시각 `hikaricp_connections_active` 5(평소 0~1) → 멈춘 동안 5 VU 요청이 모두 묶였다. 연결 대기(pending)는 0.
- 평소 앱 내부 `/api/places` 평균 약 165ms, 프로세스 CPU 평균 2.5%. 즉 평소엔 여유롭고, **Full GC 한 번이 5초 동안 전부 멈춘 것**.
- 운영 JVM 옵션: `-Xmx384m -Xss256k -XX:+ExitOnOutOfMemoryError`, GC 지정 없음, vCPU 2·RAM 956MiB.
  `MarkSweepCompact`는 Serial GC의 Full GC 이름이다. 메모리가 작은 서버라 JVM이 자동으로 Serial GC(한 스레드, 전체 정지)를 고른 것으로 보인다.
- Full GC가 수백 MB도 안 되는 힙(old 영역 최대 256MiB)에 5초나 걸린 건, E2.1.Micro의 1/8 OCPU 기본 CPU 한도에서 CPU가 제한된 영향으로 추정(미확정).

**의미**: 이번 작업에서 처음 찾은 **진짜 운영 병목 후보**다. 인덱스(쿼리 0.26ms)와는 무관하며,
평소 p95 425ms인 서비스가 Full GC 순간 5초 멈춘다. 면접의 “처음 보는 멈춤 → 원인 → 해결” 이야기에 해당한다.
이 Full GC가 부하 때문인지 다른 원인(분석 작업·주기 작업)과 겹친 건지는 아직 모른다.

**해결안**
1. (권장) 인덱스 전 측정은 “2분 49초·359건, 1건 GC 시간 초과”로 인정하고 인덱스 반영·후 측정을 계획대로 진행.
   GC 멈춤은 **별도 병목으로 기록**하고, 1단계 후 원인 분석(GC 로그 켜기·빈도 확인)과 해결 비교(GC 종류·힙 설정)를 복제 환경에서 실험한 뒤 운영 반영 여부를 고른다.
2. 같은 조건으로 인덱스 전 측정을 한 번 더(3분) 해서 GC 멈춤이 재현되는지 먼저 본다. 근거는 단단해지지만 운영에 부하 한 번 더.
3. 인덱스 작업을 멈추고 GC 문제부터 먼저 분석·해결한다.

**사용자 선택**: (대기 중)
