# 0009 코드 스타일은 지금 쓰는 방식 유지, 포맷 전환 보류
- 상태: 확정 · 날짜: 2026-10-08 · 근거: 이슈 #143, SDD 검토, main 코드 집계(2026-10-08)

## 결정
새 코드는 지금 레포가 실제로 쓰는 방식을 따른다.
- 들여쓰기 4칸, 생성자 주입(필드 `@Autowired`는 테스트에서만), Lombok 쓰지 않음, 요청·응답은 `record`.
- 패키지: 기본은 `controller` / `service` / `entity` / `repository`, 기능이 커지면 기능 패키지(`pipeline`, `planner`, `replan`, `conversation`, `recommendation`, `geocoding`, `weather` 등).
- 외부 API 연동은 별도 Service 클래스로 분리한다.
- 테스트 이름은 한글 문장(`void 승인하면_...()`).
- 새 코드에서는 와일드카드 import(`*`)와 `var`를 쓰지 않는다. 기존 코드는 그 파일을 고칠 때만 함께 정리한다.

## 이유
- 집계(main, 2026-10-08): 들여쓰기 4칸, Lombok 0곳, `record` 56개 파일, 와일드카드 import 42줄, `var` 55곳, 한글 테스트 이름 421개.
- Google Style(2칸)·Lombok으로 바꾸면 126개 파일을 다시 포맷하는 큰 변경이 된다. 제품에 이득이 없고 열려 있는 브랜치(#131, #133)와 충돌이 커진다.

## 비교한 대안
- 지금 Google Java Style로 일괄 전환: 위 이유로 보류.

## 다시 볼 조건
- 열린 브랜치가 모두 머지된 뒤, 포매터(google-java-format 등) 도입을 별도 PR 하나로 검토한다.
