# Spring AI 2로 Gemini 텍스트·JSON 호출 옮기기 (#243)

## 왜
- Gemini를 부르는 Java 클라이언트 4개(텍스트·JSON·대화·임베딩)가 각자 요청·응답 레코드와 타임아웃·재시도를 직접 만든다(567줄).
  모델을 바꾸거나 응답 형식을 바꿀 때마다 같은 일을 네 번 한다.
- 사용자 요청(2026-10-29): "스프링ai도 진행해줘 … 왜 바꿨는지 더 좋아진 게 있는지 정리".
- Spring AI 2.0은 2026-06 정식 출시, 이 레포의 Spring Boot 4.1을 지원한다. Apache 2.0 라이브러리라 비용은 없고, 비용은 지금처럼 Gemini 무료 등급 호출뿐이다(ADR 0002 유지).

## 이번 범위
- `GeminiTextClientImpl`(추천 이유 같은 짧은 문장), `GeminiJsonClientImpl`(일정 초안·요청 해석, JSON만 받기)을 Spring AI `ChatModel`(google-genai) 위로 옮긴다.
- 바깥 인터페이스(`GeminiTextClient`, `GeminiJsonClient`)는 그대로 — 부르는 쪽 코드는 안 바뀐다.
- 그대로 지킬 것: 20초 읽기 제한, JSON 클라이언트의 재시도 3회(429·5xx, 2초부터 2배, 30초 상한), `api_call_logs` 기록(시간·토큰), 하루 한도.

## 안 하는 것(다음 단계 후보)
- 대화 비서(`GeminiChatClientImpl`, 도구 호출) — 도구 실행을 지금은 직접 돌리고 호출마다 기록한다. Spring AI의 자동 도구 실행으로 바꾸면 흐름이 바뀌어 따로 본다.
- 임베딩(`GeminiEmbeddingClientImpl`) + pgvector — Spring AI `PgVectorStore`는 테이블 구조가 달라 옮기려면 데이터 이전이 필요.
- Python 파이프라인(yt-dlp·오디오·장소 추출) — Spring AI와 관계없음.

## 정한 것
- **자동 설정은 끄고 직접 만든다**: google-genai 자동 설정은 API 키가 비면 Vertex AI로 가서 프로젝트 ID가 없다는 예외로 서버가 안 뜬다(라이브러리 코드 확인).
  그리고 읽기 제한을 속성으로 줄 수 없다. → `SpringAiConfig`에서 `Client`(키, 20초)와 `GoogleGenAiChatModel`을 만든다.
- **Spring AI 재시도는 끈다(0회)**: 기본값이 최대 10회(`spring.ai.retry.max-attempts=10`, 2초부터 5배)라 그대로 두면 한 요청이 Gemini 하루 한도를 10배로 쓸 수 있다.
  재시도는 지금처럼 `GeminiJsonClientImpl`이 3회까지만.
- **google-genai SDK 재시도도 끈다(1회)**: 구현 중 실측에서 찾음 — SDK `RetryInterceptor`가 설정 없이도 408·429·5xx·시간 초과에 최대 5회 다시 보낸다(20초 제한인데 한 호출 41초). 두 겹을 다 끄지 않으면 한 요청이 최대 15번 호출된다.
- **관측은 켠다**: `ObservationRegistry`를 넘기면 Spring AI가 `gen_ai.client.operation`(시간)·`gen_ai.client.token.usage`(토큰) 지표를 Prometheus로 내보낸다 — Grafana에 바로 붙일 수 있다. 프롬프트·응답 본문은 로그에 남기지 않는다(기본값 유지).

## 확인
- 단위 테스트: 429 뒤 재시도·즉시 실패 기록·토큰 기록(ChatModel 가짜로).
- 로컬 실측(개발 DB, 같은 프롬프트, 각 N회): 응답 시간 전후, JAR 크기, 서버 시작 후 메모리(RSS) 전후. Gemini 한도를 아끼려고 N은 작게, 사용량은 먼저 확인.
- 판단 기준: 메모리 증가가 운영 서버(956MB)에 부담이면 다음 단계로 넘어가지 않는다.
