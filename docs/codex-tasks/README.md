# Codex에 맡길 코드 작업 목록 (#438)

대장(Claude 세션)이 지시문을 쓰고 결과를 검증하며, 실행은 `codex exec`가 한다.
이 폴더는 목록과 지시문까지만 담는다. 실제 실행은 #436(홍보 패키지)·#437(정답 세트)이 끝난 뒤 아래 순서로 한다.

- 조사 기준: `origin/main` 52307bc (2026-11-16, 로컬 맥에서 코드만 읽음 — 서버·DB·Gemini 사용 없음)
- 후보의 근거 줄은 Codex가 읽기 전용으로 찾았고, 대장이 해당 줄을 직접 열어 확인했다. SQL 문장 수 증가는 실측이 아니라 코드에서 읽은 추정이다.

## 작업 목록

| 순서 | 지시문 | 종류 | 먼저 끝나야 하는 것 | 겹치는 파일(충돌 범위) | 검증 |
|---|---|---|---|---|---|
| 1 | [01 ADR ↔ CLAUDE.md 모순 점검](01-adr-claude-md-consistency.md) | 읽기 전용 보고서 | 없음 | 없음(파일을 고치지 않음) | 대장이 지적마다 원문 줄 확인 |
| 2 | [02 인스타 429 자동 재시도 설계 검토(#365)](02-instagram-429-retry-design-review.md) | 읽기 전용 보고서 | 없음 | 없음 | 대장이 근거 줄 확인, 결정은 사용자 |
| 3 | [03 여행 상세·삭제 영향·재구성의 일차별 장소 조회 한 번으로](03-trip-day-places-single-query.md) | 코드 + 테스트 | 없음 | `TripController.java`는 열린 PR #131과 겹침 | `./gradlew build`, reviewflow |
| 4 | [04 외부 API 클라이언트 4종 테스트](04-external-api-client-tests.md) | 테스트 + 테스트용 생성자 | 없음 | 열린 PR과 겹침 없음 | `./gradlew build`, reviewflow |
| 5 | [05 일정 생성·소셜 로그인 연결 흐름 테스트](05-service-flow-tests.md) | 테스트만 | 없음 | 없음(새 테스트 파일만) | `./gradlew build`, reviewflow |
| 6 | [06 같은 영상 여행 찾기·관리자 사용자 상세의 항목별 조회 줄이기](06-video-trip-and-admin-detail-queries.md) | 코드 + 테스트 | 03 머지(같은 조회 수 테스트 방식을 따라 씀) | `SavedPlaceRepository.java`는 열린 PR #133과 겹침 | `./gradlew build`, reviewflow |
| 마지막 | [99 Google Style 포맷 전환](99-google-style-format.md) | 전체 포맷 | **열린 브랜치가 모두 머지된 뒤에만**(CLAUDE.md, ADR 0009) + 사용자 승인 | 모든 Java 파일 | `./gradlew build`, 포맷 외 변경 0 확인 |

- 01·02는 파일을 고치지 않으므로 서로, 그리고 다른 작업과 동시에 돌려도 된다.
- 03·04·05는 건드리는 파일이 서로 달라 워크트리를 나누면 동시에 돌릴 수 있다. 06은 03 뒤에 한다.
- 겹치는 열린 PR(#131, #133)이 먼저 머지되면 그 뒤에 시작하고, 아니면 해당 PR 쪽에서 충돌을 풀어야 한다는 점을 PR 본문에 적는다.

## 목록에 넣지 않은 후보

| 후보 | 넣지 않은 이유 |
|---|---|
| 찜·폴더·알림 목록 N+1 (#335) | 이미 PR #338로 고쳤고 `ListQueryCountTest`가 지킨다 |
| 여행 목록 `GET /api/trips` | 지역을 한 번에 조회하고 `TripListQueryCountTest`가 있다 |
| 대안 추천·빈 시간 추천의 후보별 개인화 조회(`PersonalizationService.findSimilarSignals`) | pgvector 네이티브 쿼리라 테스트 DB(H2)에서는 실패 후 0점으로 넘어간다. 조회 수 검증에 Postgres가 필요해 개발 DB(하나뿐인 자원)를 써야 한다. 사용자가 측정을 정한 뒤 별도 이슈로 |
| 일정 초안 생성의 영상별 조회(`TripPlannerService`) | 입력 영상이 최대 5개로 묶여 있어 늘어날 폭이 작다 |

## 실행 절차 (작업 하나 = 이슈 하나 = 워크트리 하나)

1. 대장이 이슈를 만들고 워크트리·브랜치(`타입/#이슈번호-내용`)를 준비한다. 기준은 최신 `origin/main`.
   - 새 워크트리에는 `.worktreeinclude`에 따라 `pipeline-test/.env`(API 키)와 `.claude/settings.local.json`이 복사된다. Codex 샌드박스가 읽기를 막아 준다고 믿지 않는다. **Codex를 돌리기 전에 `pipeline-test/.env`를 워크트리 밖으로 옮기고**(`ls -a pipeline-test | grep '^\.env$'`가 비어야 한다), 작업이 끝난 뒤에도 되돌려 놓지 않는다(이 목록의 작업은 키가 필요 없다).
   - `git status --ignored --porcelain`으로 무시 파일 목록을, `shasum .claude/settings.local.json`으로 해시를 적어 둔다(4단계에서 비교).
2. 지시문에 적힌 근거 줄이 지금 코드와 맞는지 대장이 먼저 확인한다(목록을 만든 뒤 코드가 바뀌었을 수 있다). 다르면 지시문을 고친 뒤 실행한다.
3. Codex 실행. 이 맥에는 `timeout`·`gtimeout`이 없어서 감시 프로세스로 20분 제한을 건다. 표준 입력을 닫지 않으면 Codex가 입력을 기다리며 멈춘다.

   ```bash
   W="$PWD"                       # 작업 워크트리
   OUT="<레포 밖 임시 폴더>/codex-03.md"
   codex exec -C "$W" -s workspace-write -o "$OUT" \
     "$(cat docs/codex-tasks/03-trip-day-places-single-query.md)" < /dev/null > "$OUT.log" 2>&1 &
   P=$!
   ( sleep 1200 & S=$!; trap 'kill $S 2>/dev/null' TERM; wait $S && kill $P 2>/dev/null ) &
   WD=$!
   wait $P; kill $WD 2>/dev/null
   ```

   - 감시 프로세스는 Codex의 프로세스 번호(`$P`)만 종료한다. 이름으로 찾아 죽이지 않는다(로그를 보던 `tail`이나 실행 셸이 같이 죽을 수 있다). 같은 형태를 `sleep`으로 바꿔 로컬 맥에서 시험했다: 일찍 끝나면 남는 `sleep`이 없고, 제한을 넘기면 대상이 종료된다(종료 코드 143).

   - 읽기 전용 작업(01·02)은 `-s read-only`로 실행한다.
   - `--dangerously-bypass-*` 옵션은 쓰지 않는다.
   - 20분 안에 끝나지 않으면 종료하고, 지시문을 더 작게 나눠 다시 맡기거나 대장이 직접 한다.
4. 대장 검증(Codex 결과는 그대로 믿지 않는다).
   - `git status`·`git diff --stat`로 지시문의 "건드릴 파일" 밖 변경이 없는지 본다. 있으면 되돌리고 이유를 기록한다. `git status --ignored --porcelain`과 `.claude/settings.local.json` 해시가 1단계와 같은지도 본다(무시 파일은 `git status`에 나오지 않는다).
   - **빌드 전에 `git diff`를 끝까지 읽는다.** 대장의 빌드는 샌드박스 밖에서 돌아 네트워크와 홈 폴더에 닿는다. `build.gradle` 변경, 실제 외부 주소를 부르는 테스트, 프로세스를 띄우는 코드가 있으면 빌드하지 않고 되돌린다.
   - `./gradlew build`를 대장이 직접 돌린다. Codex 샌드박스에서는 네트워크와 워크트리 밖 쓰기가 막혀 빌드가 안 될 수 있다(이 목록을 만들 때는 확인하지 않았다).
   - 조회 수를 줄이는 작업(03·06)은 새 조회 수 테스트가 바꾸기 전 코드에서 실패하는지 대장이 직접 확인한다(`git stash` 대신 main 쪽 변경만 임시로 되돌려 테스트 하나를 돌린다).
   - `/reviewflow:review`로 정확성·테스트·보안·규칙(ADR·CLAUDE.md) 네 관점 리뷰를 돌리고, 확인된 지적은 Codex에 다시 맡기거나 대장이 고친 뒤 빌드를 다시 돌린다.
   - 읽기 전용 보고서는 지적마다 원문 줄을 열어 확인하고, 확인된 것만 `docs/reviews/`에 남긴다.
5. `docs/experience-notes.md`에 한 것·확인한 것(로컬/운영 구분)을 적고, 커밋(`타입: 내용`) → PR(`Closes #이슈번호`) → `gh pr merge --squash`.
6. 이 표의 해당 줄에 PR 번호를 적는다.

## 쓰지 않는 자원

Codex 작업은 Gemini 하루 한도, 로컬 서버(8080), QA 시뮬레이터, 개발 DB 테스트 계정, 운영 서버를 쓰지 않는다. 필요한 작업이 생기면 목록에 넣지 않고 사용자에게 먼저 묻는다.
Codex에 넘기는 입력에는 `.env`, 운영 로그, 사용자 데이터, 시크릿을 넣지 않는다. 워크트리 안에 놓인 키 파일도 입력으로 본다(실행 절차 1단계). 공통 규칙은 [00-common-rules.md](00-common-rules.md).

## 비용

Codex는 사용자가 이미 쓰고 있는 구독 범위 안에서 개발 도구로만 쓴다. API 과금 호출을 쓰지 않고, 운영 코드에는 넣지 않는다(비용 0원 원칙, ADR 0002). 이 방식을 결정 기록(ADR)으로 남길지는 사용자가 정한다 — 지금은 ADR이 없다.
