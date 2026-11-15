# 99 Google Style 포맷 전환 (마지막 — 조건이 맞기 전에는 실행하지 않는다)

먼저 `docs/codex-tasks/00-common-rules.md`를 읽고 따른다. 단, 이 작업에서는 공통 규칙의 "파일 전체를 다시 포맷하지 않는다"와 "`build.gradle` 수정 금지"가 아래 범위 안에서만 풀린다.

## 시작 조건 (대장이 확인, 하나라도 아니면 실행하지 않는다)

1. 열린 브랜치·PR이 모두 머지되었거나 닫혔다(`gh pr list --state open`이 dependabot 외 0건). 근거: CLAUDE.md 코드 스타일, `docs/adr/0009-code-style-as-is.md`.
2. 01~06이 모두 머지되었다.
3. ADR 0009는 "포맷 전환 보류"이고 "별도 PR 하나로 **검토**"라고만 정했다. 전환 자체는 기존 결정을 바꾸는 일이므로 **사용자가 전환을 승인하고 새 ADR(0009를 대체)이 먼저 머지되어 있어야 한다.** 들여쓰기(지금 4칸, Google Style은 2칸)를 어떻게 할지도 그 ADR에서 정한다.

## 목표

새 ADR이 정한 포매터와 설정으로 Java 코드 전체를 한 번에 포맷하고, 이후 빌드가 포맷을 검사하게 한다. 동작은 한 줄도 바뀌지 않아야 한다.

## 할 일

1. 새 ADR에 적힌 포매터·버전·설정만 `build.gradle`에 넣는다. ADR에 없는 선택은 하지 않고 보고한다. 플러그인이 무료·오픈소스인지 확인해 보고에 적는다.
2. 포맷을 `src/main/java`, `src/test/java` 전체에 적용한다.
3. 포맷 검사를 `./gradlew build`에 연결한다(ADR이 그렇게 정했을 때만).
4. 포맷 적용 커밋을 `git blame`에서 건너뛸 수 있도록 `.git-blame-ignore-revs`에 넣을 커밋을 대장에게 알린다(커밋은 대장이 한다).

## 건드릴 파일

- `build.gradle`(포매터 설정만), `src/main/java/**`, `src/test/java/**`(포맷만)

## 건드리지 말 파일

- `pipeline-test/`, `src/main/resources/`, `src/test/resources/`, `docs/`, `monitoring/`, `deploy/`, `tools/`
- 포맷 외의 어떤 코드 변경도 하지 않는다(import 정리로 동작이 바뀌는 경우 포함 — 의심되면 그 파일은 빼고 보고).

## 완료 기준

- `./gradlew build` 통과, 테스트 수가 포맷 전과 같다.
- 공백을 무시한 diff(`git diff -w --stat`)에서 줄 바꿈·import 순서 외 변경이 없다는 점을 보고에 적는다.
- 포맷을 한 번 더 돌려도 변경이 0이다.

## 검증 명령

```bash
./gradlew build
git diff -w --stat
```
