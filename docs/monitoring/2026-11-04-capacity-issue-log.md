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

**사용자 선택**: 1번(결과 인정, 인덱스 계속, GC는 1단계 후 별도 분석). Rust 전환 질문에는 JVM 설정 해결 우선을 권했고 사용자 동의.

## #5 PR #308 머지를 Claude가 할 수 없음 (2026-11-04 18:2x, 로컬)

**현상**: `gh pr merge 308 --squash`가 Claude Code 자동 권한 분류기에서 “Merge Without Review”로 차단. 과거에도 같은 이유로 막혀 사용자가 직접 머지한 기록이 있다.
**관련 판단**: 인덱스는 이미 18:20:56 운영 DB에 생성됨(`indisvalid=true`). 배포는 코드 정의만 추가하므로, **배포 전에 후 측정을 하면 같은 JAR에서 인덱스만 다른 깔끔한 비교**가 된다.
**해결안**: 1) (권장) 지금 후 측정, 머지는 사용자가 직접(Terminal/GitHub), 측정 후 Claude가 배포 2) 리뷰(reviewflow) 후 머지 3) `gh pr merge` 권한 규칙 추가.
**사용자 선택**: 1번. 계획 Task 5·6 순서를 바꿔 후 측정(Task 6)을 배포 전에 실행.
**결과**: 사용자가 Claude에게 머지를 직접 요청 → 재시도 통과, PR #308 squash 머지 `b0417049`.

## #6 인덱스 후 측정이 2분 13초에 자원 감시로 중단 — 새 swap-out 20페이지 (2026-11-04 18:24, 운영)

**현상**: 18:22:43~18:24:56 KST, 290건 오류 0(중앙 269ms, p95 354ms, 최대 439ms). 18:24:56 표본에서 swap-out +20페이지(80KiB) →
자원 감시 기준 “새 swap-out”으로 중단(계획 3분 중 2분 13초). 여유 메모리 최소 191MiB(전 측정 261MiB보다 70MiB 적음).

**확인한 것**
- 운영 서버는 원래 스왑을 쓰고 있다: 스왑 325MiB 사용, 부팅 후 누적 swap-out 약 487만 페이지(≈18.6GiB). java RSS 420MiB(재시작 후 2시간 8분).
- 측정 중 Full GC 0회, 앱 내부 장소 목록 평균 167ms(전 165ms), CPU 1.4%. 부하 자체는 가벼웠다.
- 감시 기준 “swap-out이 하나라도 늘면 중단”은 11-02에 정한 임시 기준으로, 이 서버의 평소 상태에서도 걸릴 만큼 엄격하다.

**전 측정 Full GC와의 연결 가설(미확정)**: 메모리가 빠듯해 JVM 힙 일부가 스왑으로 밀려 있으면, Serial Full GC가 힙 전체를 훑을 때
스왑에서 다시 읽어 와야 해 아주 느려질 수 있다(4.97초의 후보 원인). 전 측정 감시 표본은 18:10:01이 마지막이라 GC 순간의 swap-in은 기록되지 않았다.
GC 분석 때 GC 로그 + 같은 시각 swap-in을 함께 기록해 확인한다.

**해결안**
1. (권장) 2분 13초·290건을 인덱스 후 결과로 인정하고 배포 진행. 전/후 모두 조기 중단이라는 점과 사유를 보고서에 명시.
   중앙값·서버 내부 평균이 이미 같아 결론(운영 규모에선 효과 없음)이 바뀌지 않는다.
2. 감시 기준을 “swap-out 속도 ≥ 1MiB/s”처럼 평소 상태를 견디는 값으로 바꾸고(테스트 추가) 전/후를 3분씩 다시 측정. 더 깔끔하지만 운영 부하 2회 추가.
3. 같은 기준으로 후 측정만 한 번 더 시도.

**사용자 선택**: 2번(감시 기준을 고치고 전후 3분씩 재측정).

## #7 다운로드 폴더 접근이 다시 막힘 — #1 재발 (2026-11-04 21:44, 로컬)

**현상**: 재측정용 새 브랜치를 만들려는데 git이 다시 `not a git repository`. `ls ~/Downloads` → EPERM.
이슈 생성도 같은 이유로 실패(만들어지지 않음 확인).

**확인한 것**: 셸의 부모는 16:32:50에 새로 뜬 터미널 호스트(PID 63772) 그대로다. 즉 **같은 호스트에서 16:33엔 되고 21:44엔 안 된다.**
→ “오래 떠 있던 호스트가 권한 변경을 반영 못 함”이라는 #1의 원인 추정은 **틀렸거나 불완전**하다.
시간이 지나면 권한이 풀리는 것으로 보인다. macOS가 이 호스트를 정식 앱(Orca 2.app)과 다른 프로그램으로 보고,
“파일 및 폴더” 접근을 일시적으로만 허용하는 것으로 추정(미확정). 호스트 재시작은 임시 해결일 뿐이다.

**해결안**
1. (권장) 메인 레포를 다운로드 폴더 밖(예: `~/dev/trova-backend`)으로 옮기고 `git worktree repair`.
   macOS가 보호하는 폴더에서 벗어나 권한 문제가 다시 생기지 않는다. 다른 워크트리 경로·Claude 메모리 폴더(현재 레포 경로 기준)도 옮겨야 한다.
2. 시스템 설정 → 개인정보 보호 및 보안 → 파일 및 폴더 / 전체 디스크 접근에서 “Orca Terminal Host” 항목이 따로 있는지 확인하고 허용.
   있으면 근본 해결일 수 있다. 없으면 `~/Library/Application Support/orca/daemon-host/.../Orca Terminal Host.app`을 전체 디스크 접근에 직접 추가.
   단, Orca 업데이트마다 호스트 경로가 바뀌어 다시 추가해야 할 수 있다.
3. 다시 호스트 재시작(임시). 몇 시간 뒤 재발할 수 있다.

**영향**: 커밋·브랜치·이슈 생성이 막힘. 운영 측정·DB 작업(git 불필요)은 진행 가능.
**추가 관찰**: 같은 호스트에서 22:02에는 다시 접근 가능(배포 미리보기에서 git 정상). **됐다 안 됐다 하는 간헐적 차단**이다.
되는 동안 이슈 #329·브랜치 `test/#329-prod-index-rerun`을 만들고 커밋했다. 근본 원인·해결은 미정.
**사용자 선택**: (대기 중 — “남은 일 순서대로”에서 이 항목은 사용자 조치가 필요해 마지막에 다시 묻기로 함)

## #8 재측정용 인덱스 삭제(DROP INDEX)를 Claude가 실행할 수 없음 (2026-11-04 21:50, 운영)

**경과**: #6 선택 2번에 따라 감시 기준 수정(swap-out 속도 1MiB/s, 테스트 통과) 후 재측정.
1회차(인덱스 있음) 21:46:25~21:49:28 KST, **3분 완주**, 395건 오류 0, 중앙 275.6ms, p95 413.1ms, 최대 686.3ms, 최소 여유 메모리 272MiB.
**현상**: 2회차(인덱스 없음)를 위해 운영 DB에서 `DROP INDEX CONCURRENTLY public.idx_saved_places_user_created_id` 실행 →
Claude Code 자동 권한 분류기가 “Production Deploy”로 차단.
**주의**: 지금 운영 앱은 인덱스 정의가 없는 옛 JAR이다. 인덱스를 지운 상태로 새 main을 배포하면 JPA(ddl-auto update)가
시작 중에 일반 `CREATE INDEX`(잠금)를 실행할 수 있으므로, 2회차 후 인덱스를 다시 만든 다음에만 배포한다.
**해결안**: 1) (권장) 사용자가 Claude에게 삭제·재생성을 직접 요청(머지 때처럼 명시 요청 후 재시도) 2) 사용자가 Supabase SQL 편집기에서 직접 실행
3) 2회차 생략 — 인덱스 전 비교는 18:07 측정(조기 중단)으로 대신.
**사용자 선택**: 1번(사용자가 직접 요청) → 재시도 통과.
**결과**: 21:51:17 삭제 → 2회차(인덱스 없음) 21:51:24~21:54:27 3분 완주, 395건 오류 0, 중앙 274.9ms, p95 378.0ms, 최대 460.3ms, 서버 내부 평균 164.2ms →
21:54:39 재생성(`indisvalid=t`). 1회차(있음)와 비교해 중앙값·서버 내부 평균이 같다 → **운영 규모에선 인덱스 효과 없음** 확정. p95는 오히려 없음 쪽이 낮아(378 vs 413) 표본 편차 수준.
**추가 조사(22시대)**: 사용자 확인 — 전체 디스크 접근 목록에 “Orca Terminal Host”가 **없었다**. 실제 파일 접근 주체(Host)가 권한 목록에 없어
Orca 앱 권한이 간헐적으로만 적용된 것으로 추정. 사용자가 Host 앱(`…/terminal-host-3ac457…/app.noindex/Orca Terminal Host.app`)을 직접 추가함.
**사용자 선택**: Host를 전체 디스크 접근에 직접 추가. 재발 시 레포 이동(해결안 1). Orca 업데이트로 Host 경로가 바뀌면 다시 추가 필요.

## #9 운영 GC 로그 켜기(jcmd VM.log)를 Claude가 실행할 수 없음 (2026-11-04 23시대, 운영)

**경과**: Full GC 분석(`2026-11-04-full-gc-analysis.md`) — 긴 멈춤 4회(4.97~9.78초)가 모두 `CodeCache GC Threshold` 원인, steal이 CPU 요구 시간의 약 절반.
사용자 선택 3번(운영에 GC 로그 켜기 + 복제 환경 Serial vs G1 비교 동시 진행).
**현상**: `sudo install -d … /opt/trova-backend/gc-logs` + `sudo -u trova jcmd <pid> VM.log output=…gc.log …`가 Claude Code 자동 권한 분류기에서 차단(설명 없음).
**해결안**: 1) (권장) 사용자가 “운영 GC 로그 켜줘”라고 직접 요청 → 재시도 2) 사용자가 SSH로 직접 실행(명령 제공)
3) 운영 로그 없이 복제 환경 비교만으로 결정.
**사용자 선택**: 1번(“운영 GC 로그 켜줘” 직접 요청) → 재시도 통과. 2026-11-05 00:10 KST(서버 시각 15:10 UTC) `/opt/trova-backend/gc-logs/gc.log` 기록 시작(PID 316620). 재시작·배포 시 자동으로 꺼짐.

### #7 후속 (2026-11-05)
- 11-05 11:2x에 다시 차단(G1 적용 기록 커밋 실패). Host를 전체 디스크 접근에 추가했지만 실행 중인 Host(11-04 16:32 시작)에는 적용되지 않은 것으로 판단.
- 사용자 선택: Host 재시작. 다른 Orca 세션 4개(trova-backend 디자인·sander, safe-korea 2개) 종료를 사용자가 확인 후 11:27 종료.
- **결과**: 새 Host(11-05 11:27:28 시작)에서 `ls ~/Downloads` 정상, `git status` 정상(11:27:57).
  “권한 목록에 Host 추가 + Host 재시작” 조합이 해결책일 가능성이 높다. 몇 시간 뒤에도 유지되는지 계속 확인한다(이전엔 5시간 뒤 재발).
- 부수 피해: 차단 중 실행한 명령 묶음(`git … && cat >> 문서`)에서 git 실패로 기록 쓰기까지 실행되지 않았다 → 재작성 후 커밋(d49ec45).
  앞으로 git과 파일 쓰기를 한 명령에 `&&`로 묶지 않는다.
- 12:14 다시 차단(Host 재시작 11:27 후 약 1시간). “Host 전체 디스크 접근 추가 + 재시작”도 영구 해결이 아님 → 해결안 1(레포 이동)로.

### #7 결과 — 레포 이동 (2026-11-05 12:37, 로컬)
- **사용자 선택**: 해결안 1. `~/trova-repo-move.sh`를 Orca 종료 후 Terminal.app에서 실행 → `~/Downloads/buildlog/trova-backend` → `~/dev/trova-backend`.
  백업 `~/trova-repo-move-backup-20261105-123747`(Orca 설정·devflow/qaflow 설정 사본).
- **확인(12:4x)**: 옛 경로 없음. `git worktree list`의 워크트리 40개가 모두 새 경로 기준으로 연결(`prunable` 0).
  관리자-페이지-터미널의 `.git`이 `~/dev/trova-backend/.git/worktrees/…`를 가리키고 `git status`·빌드·커밋·푸시 정상(602cd22).
  Claude 메모리는 새 폴더(`-Users-gimtaehyeong-dev-trova-backend`)로 복사됨.
- git 차단 재발 여부는 계속 본다(이동 후 아직 차단 없음).

## #10 레포 이동 뒤 Orca에서 기존 trova-backend 작업(세션)이 안 열림 (2026-11-05 12:38~, 로컬)

**현상**: 사용자 — “원래 작업하던 오르카에서 세션을 계속 못 연다”. 새 세션은 `~/orca/trova-backend`(12:40에 새로 생긴 **별도 클론**, 워크트리 없음)에서 열렸다.

**원인(확인)**: Orca 1.4.245는 상태를 `profiles/local-default/profile-state.db`(SQLite)에 두고 `orca-data.json`은 **내보내기 사본**일 뿐이다.
- 이동 스크립트는 `orca-data.json`만 바꿨다(12:37:47).
- 12:38 Orca가 켜지면서 JSON이 바깥에서 바뀐 것을 감지 → `profile-state-corrupt-…/manifest.json`에 `reason: profile-state-adopt-current-sqlite`로 JSON을 격리하고 **SQLite(옛 경로)를 그대로 채택**, `orca-data.json`을 옛 경로로 다시 내보냄.
- 그래서 지금 Orca의 trova-backend 레포 등록은 `"path":"/Users/gimtaehyeong/Downloads/buildlog/trova-backend"`(없는 폴더)이고, 새 경로(`~/dev/trova-backend`)는 0건. 그 레포에 딸린 작업들(관리자-페이지-터미널 등)이 열리지 않는다.
- 12:40에 Orca에 `~/orca/trova-backend`가 따로 등록됐다(같은 GitHub 레포를 새로 클론한 것으로 보임). 이 세션이 그곳에서 열렸다.
- git 자체와 Claude 대화 기록(`~/.claude/projects/-Users-gimtaehyeong-orca-workspaces-trova-backend-------------/55d0a49e….jsonl` 등, 워크트리 경로 기준)은 그대로라 손실은 없다.

**해결안**
1. (권장) Orca 화면에서 옛 trova-backend 레포를 지우지 말고 “경로 다시 지정/폴더 찾기”가 있으면 `~/dev/trova-backend`로 지정. 없으면 `~/dev/trova-backend`를 “기존 폴더 가져오기”로 추가 → 워크트리(`~/orca/workspaces/trova-backend/*`)가 다시 보이는지 확인. 앱이 공식 방법으로 상태를 바꾸므로 가장 안전하다. 단, 새로 추가하면 작업 탭·이름 같은 Orca 메타데이터는 새로 붙을 수 있다.
2. Orca 완전 종료 후 `profile-state.db`(와 `-wal`)를 백업하고 SQLite 안의 옛 경로를 새 경로로 바꾸기. 메타데이터까지 그대로 살지만, Orca 내부 형식을 직접 고치는 것이라 다시 “손상”으로 판단될 위험이 있다.
3. 임시: `ln -s ~/dev/trova-backend ~/Downloads/buildlog/trova-backend`. 바로 열리지만 다운로드 폴더 경로를 다시 쓰게 되어 #7 권한 차단이 돌아올 수 있다.
4. 우회: Orca 대신 터미널에서 워크트리로 가서 `claude --resume <세션ID>`로 대화 이어가기(Orca 고치기와 별개로 지금 바로 가능).

추가로 `~/orca/trova-backend`(새 클론)는 정리 대상인지 사용자 확인 필요(지금은 그대로 둠).
**사용자 선택**: (대기 중)
