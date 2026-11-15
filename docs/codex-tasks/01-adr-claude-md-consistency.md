# 01 ADR ↔ CLAUDE.md 모순 점검 (읽기 전용)

먼저 `docs/codex-tasks/00-common-rules.md`를 읽고 따른다. 이 작업은 **파일을 하나도 고치거나 만들지 않는다.** 결과는 마지막 메시지로만 낸다.

## 목표

`docs/adr/`의 결정 기록(0001~0044)과 루트 `CLAUDE.md`의 규칙 요약이 서로 어긋나는 곳을 찾아 보고한다. 고치는 것은 대장과 사용자가 정한다.

## 읽을 파일

- `CLAUDE.md`
- `docs/adr/README.md`와 `docs/adr/0001-*.md` ~ `0044-*.md` 전부
- 규칙이 실제 코드와 맞는지 볼 때만 `src/main/java`, `src/main/resources/application.yml`, `build.gradle`

## 찾을 것

1. **모순**: CLAUDE.md의 문장과 ADR의 "결정"이 서로 다른 말을 하는 곳(값·이름·범위·순서).
2. **뒤집힌 결정이 남은 곳**: ADR README에서 "대체됨"이거나 다른 ADR이 일부를 바꿨는데(예: 0013 ← 0016·0017·0044, 0037 ← 0040, 0012 ← 0019) CLAUDE.md나 원래 ADR 본문이 옛 내용을 현재처럼 적은 곳.
3. **링크·번호 오류**: CLAUDE.md가 가리키는 `docs/adr/NNNN`이 없는 파일이거나 내용이 그 주제가 아닌 곳, README 목록에 빠졌거나 상태·날짜가 본문과 다른 ADR.
4. **CLAUDE.md에만 있는 규칙**: 결정처럼 보이는데 근거 ADR이 없는 규칙(목록만, 판단은 하지 않는다).
5. **코드와 다른 규칙**: CLAUDE.md가 이름을 적은 클래스·설정·환경변수(`ShareJobQueue`, `DraftJobQueue`, `ReplanJobQueue`, `StuckJobRequeueService`, `PlaceReviewService`, `CurrentUserService.resolveUserId`, `KnownUsers`, `AdminActionService`, `APP_QUEUE_TYPE`, `API_DOCS_ENABLED` 등)이 코드에 없거나 이름이 다른 곳.

## 하지 말 것

- 문체·표현 다듬기 제안은 내지 않는다. 사실이 어긋난 곳만.
- ADR 0007(수익 모델)의 내용 평가나 수치 인용은 하지 않는다. 링크·상태 일치만 본다.

## 완료 기준

- 지적마다 표 한 줄: `종류(1~5) | CLAUDE.md 줄 | ADR 파일:줄 | 어긋난 내용(양쪽 원문 인용) | 확신도(높음/중간/낮음)`.
- 원문을 인용하지 못하는 지적은 내지 않는다.
- 0001~0044를 모두 읽었는지 마지막에 읽은 ADR 번호 목록을 적는다. 못 읽은 것이 있으면 적는다.
- 어긋난 곳이 없으면 "없음"이라고 쓴다. 억지로 채우지 않는다.

## 검증

파일을 고치지 않으므로 `./gradlew build`는 돌리지 않는다. 대장이 지적마다 양쪽 원문 줄을 직접 열어 확인하고, 확인된 것만 `docs/reviews/`에 남긴다.
