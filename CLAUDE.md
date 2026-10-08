# Trova Backend

인스타/유튜브 여행 영상 링크를 받아 AI로 장소 정보를 자동 추출하고
지도에 정리해주는 서비스의 백엔드입니다.

## 기술 스택

- Spring Boot (Java)
- DB: Supabase(Postgres), JPA/Hibernate
- 배포 목표: Oracle Cloud Free Tier + k3s (지금은 로컬 개발 단계). 단, Always Free
  Ampere A1 스펙이 2026-06부터 2 OCPU/12GB로 축소됨(기존 4 OCPU/24GB 기준 계획은 재검토 필요)
- LLM(장소 추출): Gemini API 무료 티어 (Gemini 3.5 Flash 또는 Flash-Lite — 2.5 Flash-Lite는 2026-08 기준 신규 사용자에게 제공 종료됨)
- STT: Gemini API에 오디오 파일을 직접 입력 (별도 Whisper 셀프호스팅 안 함 — 2026-08-20 결정, 아래 참고)
- 지오코딩: 카카오 로컬 API (무료 쿼터: 일 10만 / 월 300만 건)
- 영상 다운로드: yt-dlp

STT 방식 결정 배경(Whisper 셀프호스팅 대신 Gemini 오디오 직접 입력)은 docs/adr/0004 참고.

## 비용 원칙 (중요)

이 프로젝트는 **API 비용을 최대한 0원으로 유지**하는 게 목표입니다.
- 유료 API(OpenAI Whisper API, Claude API 등)를 기본값으로 제안하지 말 것
- 새로운 외부 서비스를 추가로 제안할 때는 무료 티어 존재 여부를 먼저 확인하고 알려줄 것
- 무료 티어의 요청 제한(rate limit)을 코드에 반영할 것 (재시도 로직, 백오프 등)

## 개발 순서 (반드시 이 순서를 지킬 것)

1. **파이프라인 검증 먼저**: API 서버 코드를 짜기 전에, 로컬 스크립트로
   URL → 오디오/자막 추출 → STT → 장소 추출 흐름이 실제로 동작하고
   정확도가 쓸만한지 20~30개 샘플로 검증한다.
2. 검증되면 Spring Boot API로 옮긴다.
3. k3s 배포는 API가 로컬에서 안정적으로 동작한 이후에 진행한다.

## API 설계 원칙

- 외부 API 호출(Gemini, 카카오, yt-dlp 등)이 포함된 처리는 반드시 비동기(`@Async`)로 처리
- 엔드포인트 목록은 코드(`controller` 패키지)가 기준이다 — 여기에 따로 적지 않는다.
  시작할 때의 핵심 흐름: `POST /api/shares`(URL 제출, 비동기 시작) → `GET /api/places/pending`(폴링) → `GET /api/places`
- API 문서는 springdoc이 컨트롤러에서 자동으로 만든다. 로컬에서 `API_DOCS_ENABLED=true`로 켜고 `/swagger-ui.html`, 운영 서버는 꺼 둔다(docs/adr/0010)

## Entity

- `User` (provider, providerUserId)
- `SavedPlace` (sourceUrl, sourcePlatform, placeName, region, latitude, longitude, status)
- `ProcessingJob` (savedPlaceId, errorMessage, retryCount)

## 코드 스타일

지금 레포가 실제로 쓰는 방식을 따른다(근거·집계는 docs/adr/0009).
- 들여쓰기 4칸, 생성자 주입(필드 `@Autowired`는 테스트에서만), Lombok 쓰지 않음, 요청·응답은 `record`
- 패키지: 기본 `controller` / `service` / `entity` / `repository`, 기능이 커지면 기능 패키지(`pipeline`, `planner`, `replan` 등)
- 외부 API 연동 로직은 반드시 별도 Service 클래스로 분리 (테스트 용이성)
- 테스트 이름은 한글 문장(`void 승인하면_...()`)
- 새 코드에서는 와일드카드 import(`*`)와 `var`를 쓰지 않는다(기존 코드는 그 파일을 고칠 때만 정리)
- Google Style 일괄 포맷 전환은 열린 브랜치가 머지된 뒤 별도 PR로만 검토
- 커밋 전 실행할 것: `./gradlew build`

## 결정과 기록 (어디에 무엇을 쓰나)

- `docs/adr/` — 무엇을 왜 정했나(결정 기록). 기존 ADR과 부딪히는 변경은 하기 전에 사용자에게 결정을 바꿀지 먼저 묻는다.
  새 방향·정책·구조를 정하면 ADR을 추가하고 `docs/adr/README.md` 목록을 갱신한다.
- `docs/experience-notes.md` — 작업 하나에서 무엇을 했고 무엇을 확인했나(PR이 끝날 때).
- `docs/superpowers/specs`, `plans` — 기능 하나의 설계와 구현 순서(만들기 전).
- 이 파일 — 지켜야 할 규칙 요약만. 결정의 배경은 ADR에 두고 여기서는 링크한다.

## 워크트리에서 작업할 때 (오르카 포함)

작업 하나 = 이슈 하나 = 워크트리 하나. 오르카는 `~/orca/workspaces/<프로젝트>/<이름>`에 워크트리를 만든다.
- 시작할 때: `git branch --show-current`로 브랜치를 보고, 규칙(`타입/#이슈번호-내용`)과 다르면 이슈를 만들고
  `git branch -m`으로 이름을 바꾼 뒤 시작한다. 새 세션이면 메모리의 인계 메모와 `docs/adr/`부터 본다.
- `.env`, `pipeline-test/.env`는 `.worktreeinclude`로 복사된다. 없으면 메인 체크아웃에서 복사한다(외부로 보내지 않음).
- devflow `ship.sh start`·`merge`는 쓰지 않는다(워크트리를 직접 만들고 지워서 오르카 작업이 깨진다).
  PR은 `ship.sh pr` 또는 `gh pr create`, 머지는 `gh pr merge --squash`, 워크트리 정리는 오르카에서 작업을 닫아서 한다.
- 하나뿐인 자원은 한 작업에서만 쓴다: 로컬 서버(8080, `.claude/worktrees/local-server`에서만 실행), QA 시뮬레이터(QA SE),
  개발 DB의 테스트 계정, Gemini 하루 한도. QA·측정·배포는 동시에 돌리지 않는다(qaflow가 테스트 데이터를 지우고 되돌림).
- 메인 체크아웃(`pipeline-test` 작업 중)에서는 직접 작업하지 않는다.

## 하지 말 것

- 유료 API를 기본 옵션으로 코드에 하드코딩하지 말 것
- 아직 검증 안 된 파이프라인 위에 바로 Spring Boot 구조부터 짜지 말 것
- 프론트엔드(웹/모바일) 코드는 이 레포에 포함하지 않음 — 별도 레포

## Git 컨벤션 (예외 없이 항상 적용)

- 브랜치명: `타입/#이슈번호-내용` (예: `feat/#12-map-api`)
- 커밋 메시지: `타입: 작업 내용` 형식만 사용. 타입은 feat/fix/docs/style/refactor/chore/perf/test 중 하나.
  - `git commit -m "타입: 내용"` 형식으로만 작성 — 그 외 어떤 텍스트도 붙이지 않는다.
  - **커밋에 AI 관련 서명/트레일러를 절대 추가하지 않는다**: "Generated with Claude Code", "Co-Authored-By: Claude" 등의 문구나 🤖 이모지 금지. 커밋 작성자는 오직 사용자로만 표시되어야 한다.
- PR 본문에 `Closes #이슈번호` 포함
