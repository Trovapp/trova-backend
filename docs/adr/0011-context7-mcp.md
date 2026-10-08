# 0011 라이브러리 문서는 Context7 MCP로 조회, 코드 탐색 MCP는 보류
- 상태: 확정 · 날짜: 2026-10-08 · 근거: 이슈 #151, SDD 검토(MCP), 사용자 승인("Context7부터 진행해줘")

## 결정
- 레포 `.mcp.json`에 Context7 원격 MCP(`https://mcp.context7.com/mcp/oauth`)를 등록한다. 인증은 OAuth 로그인이라 API 키를 레포나 `.env`에 두지 않는다.
- 버전에 따라 API가 바뀌는 라이브러리(Spring Boot 4.1, springdoc, Expo 57 등)를 쓸 때만 조회한다(CLAUDE.md "AI 도구(MCP)").
- 코드 탐색 MCP(Serena, CodeGraph)는 넣지 않는다.

## 이유
- 이 프로젝트는 AI 학습 시점 이후 버전을 쓴다. #149에서 springdoc이 Boot 4.1에 맞는지 Maven Central을 직접 열어 확인했고, 앱 레포 AGENTS.md도 "코드를 쓰기 전에 Expo v57 문서를 읽을 것"을 요구한다.
- 비용 0원([0002](0002-cost-zero-free-tiers.md)): 무료 플랜 월 1,000회, 넘으면 과금 없이 차단(이후 하루 20회 추가), Pro는 좌석당 월 $10 — context7.com/plans, 2026-10-08 확인. MCP 서버 코드는 MIT.
- 레포 `.mcp.json`에 두면 오르카 워크트리도 같은 설정을 쓴다. OAuth라 시크릿이 커밋될 일이 없다.

## 비교한 대안
1. 사용자 범위(`claude mcp add --scope user`)로만 등록 — 이 레포를 여는 다른 워크트리·사람과 설정이 갈린다.
2. API 키 방식(로컬 npx `--api-key` 또는 헤더) — 키를 `.env`에 두고 워크트리마다 복사해야 한다. OAuth로 충분하다.
3. Serena(MIT, 무료) — 백엔드는 Java 280개 파일·약 2.8만 줄로 grep으로도 충분히 빠르다(2026-10-08 컨트롤러 13개 훑기 3분). Java 언어 서버는 첫 실행이 느리고, 도구 설명이 매 세션 컨텍스트를 차지한다.
4. CodeGraph — 같은 이름의 프로젝트가 여러 개이고 Serena와 역할이 겹친다.

## 다시 볼 조건
- 월 1,000회를 넘겨 차단되는 달이 생기면 쓰는 범위를 줄이거나 다른 방법을 검토한다(유료 전환은 [0002](0002-cost-zero-free-tiers.md)와 부딪히므로 사용자에게 먼저 묻는다).
- 코드가 커져 "누가 이 메서드를 부르나" 같은 탐색이 느려지면 Serena를 넣기 전·후로 시간·토큰을 재서 판단한다.
