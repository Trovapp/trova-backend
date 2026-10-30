# 0020 Gemini 호출은 Spring AI 2(google-genai)로, 재시도는 우리 코드 한 곳에서만
- 상태: 확정 · 날짜: 2026-10-30 · 근거: 이슈 #243, 사용자 결정("스프링ai도 진행해줘 … 왜 바꿨는지 더 좋아진 게 있는지 정리")
- 더하는 결정: [0002](0002-cost-zero-free-tiers.md)(비용 0원) — 모델은 그대로 Gemini 무료 등급, 바뀌는 건 Java에서 부르는 방법

## 결정
- Java에서 Gemini를 부르는 코드는 Spring AI 2(`spring-ai-starter-model-google-genai`, BOM 2.0.2)의 `ChatModel`을 쓴다. 1단계는 텍스트(`GeminiTextClientImpl`)·JSON(`GeminiJsonClientImpl`)만 옮기고, 인터페이스는 그대로 둔다.
- 자동 설정(`GoogleGenAiChatAutoConfiguration`)은 끄고 `SpringAiConfig`에서 직접 만든다. 키가 비면 자동 설정이 Vertex AI로 가서 서버가 뜨지 않고, 읽기 제한을 줄 수 없기 때문이다.
- **재시도는 `GeminiJsonClientImpl`의 3회만.** 라이브러리 두 겹의 기본 재시도를 모두 끈다:
  - Spring AI `RetryTemplate` 기본 최대 10회 → 0회
  - google-genai SDK `RetryInterceptor` 기본 5회(408·429·5xx·시간 초과) → 1회
- 호출 시간·토큰 지표는 Spring AI 관측(`ObservationRegistry`)으로 Prometheus에 함께 나간다. 프롬프트·응답 본문은 남기지 않는다(기본값).
- Python 파이프라인(yt-dlp·오디오·장소 추출)은 그대로 둔다.

## 이유
- Gemini 클라이언트 4개가 요청·응답 레코드, 타임아웃, 재시도를 각자 만들었다(567줄). 모델·옵션을 바꿀 때 네 곳을 고쳐야 했다.
- Spring AI는 Apache 2.0 라이브러리라 비용이 없다. 비용은 지금처럼 Gemini 호출 수로만 생긴다.
- 기본 재시도를 그대로 두면 Gemini 하루 한도를 몇 배로 쓴다. 한 요청이 최대 3 × 5 = 15번 호출될 수 있었고, 실측에서 20초 제한인데 한 호출이 41초 걸렸다(2026-10-29, 로컬).

## 검토한 다른 방법
1. 지금처럼 직접 만든 REST 클라이언트 유지 — 의존성이 늘지 않지만(JAR +25MB 없음), 대화 도구 호출·구조화 출력·지표를 계속 직접 만들어야 한다.
2. LangChain4j — 일정 그래프에 langgraph4j를 쓰고 있어 결이 맞지만, 레포가 Spring Boot 중심이고 Boot 4 공식 지원·관측 연동은 Spring AI가 더 직접적이다.
3. 자동 설정 그대로 사용 — 설정이 짧지만 키 없는 테스트·로컬에서 서버가 뜨지 않고 재시도가 두 겹으로 남는다.

## 결과
- 로컬 실측(-Xmx384m, 같은 개발 DB, 각 2회): JAR 71.2MB → 95.9MB, 시작 후 RSS 평균 383MB → 400MB(+16MB), 불러온 클래스 +약 920개, 시작 시간 3.6초 → 4.0초. 운영 서버 메모리는 956MB라 감당 가능한 크기로 본다.
- 응답 시간: 첫 측정은 Gemini `503 high demand`로 실패했다. 다시 잰 결과(2026-10-30)는 중앙값 기존 902ms, Spring AI 892ms로 같았다. 속도 이득은 없고, 이득은 코드·설정·지표 쪽이다.
- 다음 단계(대화 도구 호출 → `@Tool`, 임베딩·pgvector)는 1단계를 운영에서 본 뒤 정한다.
