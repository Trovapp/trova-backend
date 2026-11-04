# 1단계: 장소 목록 인덱스 운영 반영과 전후 확인 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PR #308 인덱스를 운영에 반영하고, 같은 조건의 약한 통제 부하로 운영 전/후를 Prometheus·Grafana·k6로 비교해 기록한다.

**Architecture:** 기존 운영 관측 도구(`monitoring/run_observed_smoke.py` + `resource_guard.py`, SSH 터널 Prometheus)에 “약한 고정 부하” 모드를 하나 추가하고, 인덱스 전 측정 → DB에 CONCURRENTLY 생성 → PR 머지·배포 → 후 측정 순서로 진행한다. 2단계 보정용으로 운영 앱↔Supabase 왕복 지연도 같이 잰다.

**Tech Stack:** k6, Python 3(표준 라이브러리), Prometheus/Grafana(로컬, 터널), psql, devflow deploy

**Spec:** `docs/superpowers/specs/2026-11-04-capacity-limit-and-prod-index-design.md` (4절, 8~9절)

## Global Constraints

- 운영 DB에 합성 데이터를 넣지 않는다. 기존 테스트 계정(장소 42개, 2026-11-02 기록)만 읽는다.
- 통제 부하: 5 VU, 3분, 요청 후 2초 대기(≈150 req/min, 운영 IP 제한 300/min 아래), GET `/api/places`만.
- 중단 조건: p95 ≥ 1초 또는 단일 응답 ≥ 2초 또는 오류율 ≥ 1% 또는 `resource_guard` 경고 → 즉시 abort.
- 측정 시간대: 사용자가 적은 시간(실행 직전 사용자에게 시각 확인).
- `CREATE INDEX CONCURRENTLY`는 트랜잭션 밖(psql autocommit), **실행 직전 사용자 재확인**.
- 운영 설정·토큰·자격증명은 출력·커밋 금지. 토큰은 비공개 임시 파일로만, 작업 후 삭제.
- 모든 수치에 “운영/로컬”, 조건(VU·대기·시간)을 함께 적는다. 격리 98%를 운영 결과로 쓰지 않는다.
- 문제가 생기면 고치기 전에 원인·해결안 2~4개를 `docs/monitoring/2026-11-04-capacity-issue-log.md`에 기록하고 사용자에게 선택받는다.
- 커밋 메시지 `타입: 내용`만, AI 서명 금지. 커밋 전 `./gradlew build`.

## Review Focus

1. 터널이 중간에 끊겨 Prometheus 수집이 비는 경우 → 측정 무효로 표시하고 재측정(빈 구간으로 결론 내지 않음).
2. 테스트 토큰이 측정 중 만료(401) → 오류로 집계되어 abort, “서버 오류”와 구분해 기록.
3. 전/후 측정 사이 다른 배포(#324 등)로 JAR이 달라짐 → 전/후 JAR SHA를 기록하고 인덱스 외 변경 목록을 보고서에 명시.
4. 인덱스 생성이 실패해 invalid로 남음 → `indisvalid=false`면 배포 중단, DROP CONCURRENTLY 후 재시도 여부를 사용자에게 묻기.
5. 실사용자 요청이 측정 구간에 섞임 → Prometheus 요청 수에서 k6 요청 수를 빼 실사용 요청 수를 같이 기록.

---

### Task 0: 작업 환경 정리

**Files:**
- Restore: `docs/monitoring/2026-11-02-index-abba/prometheus-abba.png` (HEAD 버전)
- Modify: `docs/experience-notes.md` (main 병합 충돌 해결)
- Create: `docs/monitoring/2026-11-04-capacity-issue-log.md`

- [ ] **Step 1:** `git status`가 동작하는지 확인. 실패하면 issue-log #1(다운로드 폴더 권한) 상태를 보고 사용자 선택을 기다린다.
- [ ] **Step 2:** `git restore docs/monitoring/2026-11-02-index-abba/prometheus-abba.png` 후 `shasum -a 256`이 `53e4e937…5f`인지 확인.
- [ ] **Step 3:** `git fetch origin && git merge origin/main`, `docs/experience-notes.md`는 양쪽 기록을 시간 순으로 모두 남겨 해결.
- [ ] **Step 4:** `./gradlew build` → BUILD SUCCESSFUL.
- [ ] **Step 5:** 설계·계획·issue-log 포함 커밋 `docs: 운영 인덱스 반영과 한계 찾기 설계·계획 추가`, 병합 커밋, 푸시. `gh pr view 308 --json mergeable` → MERGEABLE.

### Task 1: 운영 약한 고정 부하 모드

**Files:**
- Create: `monitoring/places-load.js`
- Modify: `monitoring/run_observed_smoke.py` (mode `places-load` 추가)
- Test: `monitoring/test_health_smoke.py` (기존 fixture 방식으로 테스트 추가)

**Interfaces:**
- Produces: `python3 monitoring/run_observed_smoke.py --run-production --mode places-load --host <host> --ssh-key <key> --token-file <tmp> --output <json>`
  출력 JSON 키: `k6.p95_ms`, `k6.avg_ms`, `k6.requests`, `k6.error_rate`, `window.start_utc`, `window.end_utc`, `guard.samples`, `aborted`(bool), `abort_reason`.

- [ ] **Step 1:** 실패 테스트 작성 — `test_places_load_옵션은_5VU_3분_2초대기로_고정된다`(스크립트 options 파싱: vus 5, duration '3m', sleep 2, thresholds p95<1000), `test_places_load는_401이면_서버오류와_구분해_중단사유를_남긴다`(fixture 401 → `abort_reason == 'auth'`).
- [ ] **Step 2:** `python3 -m unittest monitoring/test_health_smoke.py -v` → 새 테스트 FAIL.
- [ ] **Step 3:** `places-load.js` 작성(`health-smoke.js`의 안전장치 구조 재사용: 단일 응답 ≥2초 abort, 401 별도 카운터), 러너에 모드 추가. VU/시간/대기를 환경변수로 바꿀 수 없게 고정.
- [ ] **Step 4:** 같은 명령 → 전체 PASS. 로컬 fixture 서버로 1회 실행해 JSON 키 확인.
- [ ] **Step 5:** `./gradlew build` 후 커밋 `test: 운영 장소 목록 약한 고정 부하 측정 모드 추가`.

### Task 2: 관측 준비와 운영 사전 확인

**Files:**
- Create: `docs/monitoring/2026-11-04-prod-index/preflight.json`

- [ ] **Step 1:** 별도 터미널에서 `monitoring/tunnel.sh` 실행 → Prometheus `up{job="trova-prod"}` = 1.
- [ ] **Step 2:** 운영 DB 읽기 전용 조회(운영 환경 파일 명시적으로 읽음): `saved_places` 행 수·`pg_total_relation_size`, 기존 인덱스 목록, `idx_saved_places_user_created_id` 미존재 확인.
- [ ] **Step 3:** 운영 앱 JAR SHA와 소스 커밋 기록(#324 반영 상태 포함).
- [ ] **Step 4:** 운영 서버에서 Supabase 왕복 지연: `psql`로 `SELECT 1` 30회 `\timing`, 중앙값·p95 기록(2단계 지연 주입값). SSH 실행 전 사용자 확인.
- [ ] **Step 5:** 테스트 계정 EXPLAIN (ANALYZE, BUFFERS) — 인덱스 전 계획 저장. 결과를 `preflight.json`에 기록.

### Task 3: 인덱스 전 측정

**Files:**
- Create: `docs/monitoring/2026-11-04-prod-index/before-k6.json`, `before-prom-*.json`, `before-grafana.png`, `before-prometheus.png`

- [ ] **Step 1:** 사용자에게 측정 시각 확인 → Task 1 명령 실행.
- [ ] **Step 2:** 측정 창의 Prometheus 원본(평균 지연, RPS, `hikaricp_connections_pending`, JVM CPU·heap) 2초 간격 저장.
- [ ] **Step 3:** Grafana·Prometheus 캡처(값이 보이게, KST/UTC 표기). 캡처가 기대 화면인지 열어서 확인(로그인 화면 등 덮어쓰기 방지).
- [ ] **Step 4:** Supabase 대시보드 DB CPU 캡처(가능한 경우). `aborted=false` 확인. abort면 issue-log 기록 후 사용자 선택.

### Task 4: 운영 DB 인덱스 생성

- [ ] **Step 1:** 사용자 재확인 후 `psql`(autocommit)로 `docs/bench/sql/place-list-user-order-index.sql` 실행, 출력 저장(`create-index.txt`, 자격증명 제외).
- [ ] **Step 2:** `indisvalid = true`, `indisready = true` 확인. 아니면 중단(Review Focus 4).
- [ ] **Step 3:** 같은 EXPLAIN 재실행 → `after-explain.json`. 계획이 인덱스를 쓰는지 기록(작은 테이블이면 Seq Scan 유지 가능 — 그대로 기록).

### Task 5: PR #308 머지와 배포

- [ ] **Step 1:** `gh pr merge 308 --squash`.
- [ ] **Step 2:** devflow deploy로 main 배포 → UP, 기존 테스트 계정 장소 목록 200·42개·응답 SHA 배포 전과 동일.
- [ ] **Step 3:** 배포 JAR SHA·소스 커밋 기록, 인덱스 외 변경 목록(전 측정 JAR 대비) 기록.

### Task 6: 인덱스 후 측정과 비교

**Files:**
- Create: `docs/monitoring/2026-11-04-prod-index/after-*.json|png`, `summary.json`

- [ ] **Step 1:** 배포 후 JVM 예열 10분 대기, Task 3과 같은 시간대 조건으로 같은 명령 실행.
- [ ] **Step 2:** Task 3 Step 2~4 반복.
- [ ] **Step 3:** `summary.json`: 전/후 p95·평균·RPS·DB 대기·JVM CPU, 실사용 요청 수(Review Focus 5), 차이율. 차이가 작으면 그대로.

### Task 7: 보고서·PDF·면접 노트·기록

**Files:**
- Create: `docs/monitoring/2026-11-04-prod-index.md`, `docs/interview/capacity-qa.md`
- Modify: `docs/experience-notes.md`
- Output: `~/Downloads/Trova_운영인덱스_반영검증_2026-11-04/*.pdf` (지난 `보고서_생성.py` 형식)

- [ ] **Step 1:** 보고서 md: 조건, 전/후 표, 캡처, 운영 테이블 크기와 해석(격리 결과와 구분), 한계.
- [ ] **Step 2:** PDF: 쉬운 용어 풀이, Grafana/Prometheus 캡처(전/후 나란히), 이력서 문장. 모든 페이지 렌더링 확인.
- [ ] **Step 3:** 면접 노트 Q&A 시작(“왜 운영에서 극한 테스트를 안 했나”, “운영 차이가 작은 이유” 등).
- [ ] **Step 4:** `./gradlew build`, 커밋 `docs: 운영 장소 목록 인덱스 반영 전후 측정 기록`, 푸시. 2단계 계획 작성으로 넘어간다(Task 2 Step 4 지연값 사용).
