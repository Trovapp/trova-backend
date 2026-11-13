# Spring AI 2 전환 전후 Gemini 클라이언트 재측정 (#422, 2026-11-13)

**한 줄 결론.** 같은 모델·프롬프트·20초 제한으로 REST 직접 호출과 Spring AI 2를 번갈아 각 30회 불렀을 때 응답 시간 차이는 입증되지 않았다(짧은 프롬프트 짝 차이 중앙값 -55ms, 95% 범위 -486~+258ms · 긴 프롬프트 -39ms, -292~+17ms). 호출당 CPU 시간·할당 메모리는 Spring AI 2가 적었고(중앙값 34→14ms, 346→102KiB), 긴 프롬프트에서 Spring AI 2만 20초 시간 초과가 2/30 있었다. 속도는 이번 전환의 성과가 아니고, 성과는 재시도 중첩 제거·운영 HTTP/2 시간 초과 해결·지표 자동 수집이다.

보고서: [trova-springai-rebench-20261113.pdf](reports/trova-springai-rebench-20261113.pdf) (7쪽, Grafana 캡처 포함). 원자료 `results/client-resource-{short,long}-20261113.json`, 캡처 `results/springai-rebench-20261113/`.

## 환경 (로컬 — 운영 아님)
- Mac(arm64) JVM 21, `-Xmx144m -Xss256k -XX:+UseG1GC`(운영 JVM 옵션). 서버·DB·대기열 없이 클라이언트만. 도구 `scripts/ClientResourceBench.java`(#288의 `ClientLatencyBench`에 자원 측정을 더함).
- 모델 `gemini-3.5-flash-lite`, temperature 0.1, JSON 응답. REST = Spring `RestClient`(HttpURLConnection, 연결 3초·읽기 20초). Spring AI 2 = `GoogleGenAiChatModel`(운영 `SpringAiConfig` 그대로: HTTP/1.1 고정, 전체 20초, SDK 재시도 1회, Spring AI 재시도 0).
- 워밍업 1라운드 제외, 라운드마다 먼저 부르는 쪽을 바꿈, 호출 사이 6초(무료 한도 15 RPM). 프롬프트는 짧은 것(30자, 입력 15토큰)과 긴 것(장소 24곳 설명 2,719자, 입력 1,601토큰).
- 자원: 호출 동안의 프로세스 CPU 시간, 호출 스레드 할당 메모리, GC 횟수(JVM MXBean). 한 JVM에서 번갈아 부르므로 RSS는 분리하지 못했다. 처리량은 직렬 호출이라 재지 않았다.
- Gemini 사용량: 시작 전 운영 260회/1,000회(태평양 자정 기준), 측정 124회 + 시험 4회.

## 결과 (성공 건, ms)

| | 짧은 REST | 짧은 Spring AI 2 | 긴 REST | 긴 Spring AI 2 |
|---|---:|---:|---:|---:|
| 성공/호출 | 30/30 | 30/30 | 30/30 | 28/30 |
| p50 | 1,261 | 1,174 | 1,389 | 1,276 |
| p95 | 8,072 | 5,922 | 4,466 | 4,442 |
| p99 | 9,312 | 8,111 | 5,089 | 4,752 |
| 평균 | 2,424 | 1,981 | 1,727 | 1,892 |
| 최대 | 9,710 | 8,166 | 5,319 | 4,827 |
| 호출당 CPU 중앙값 | 34.0 | 13.8 | 33.2 | 17.1 |
| 호출당 할당 메모리 중앙값(KiB) | 346 | 102 | 358 | 124 |
| GC 횟수 합 | 1 | 0 | 1 | 0 |

- 짝지은 차이(Spring AI - REST) 중앙값: 짧은 -55ms(bootstrap 95% -486~+258, 짝 30), 긴 -39ms(-292~+17, 짝 28). 둘 다 0을 포함하므로 속도 우위를 말하지 않는다. 11-01 측정(각 20회, -136ms, -311~+13ms)과 같은 결론이다.
- 실패: 긴 프롬프트 Spring AI 2 라운드 26·29가 20초 시간 초과(RuntimeException, OkHttp callTimeout). 같은 라운드 REST는 4.4초·1.7초에 성공. 긴 프롬프트 워밍업의 REST는 503(집계 제외). Gemini 지연인지 클라이언트 문제인지 이 측정으로는 가릴 수 없다 — 운영 `gen_ai_client_operation_seconds{error=...}`로 계속 본다.
- CPU·할당 메모리가 Spring AI 2에서 적은 것은 REST 쪽이 응답 JSON 전체를 문자열로 받아 Jackson으로 다시 파싱하는 비용 때문으로 보인다(확인한 것은 아님). 호출 1회당 20~30ms 수준이라 서비스 체감과는 거리가 있다.

## 운영 기록 (Oracle E2.1.Micro)
- 재시도(`api_call_logs`, 2026-11-01 17:00 KST #297 이후~11-13): 일정 초안 110건 중 평균 시도 1.17회, 최대 3회, 2회 이상 15건, 3회 초과 0건. 라이브러리 숨은 재시도 없이 우리 코드 상한(3회) 안에서만 다시 보낸다.
- 전후(10-01~10-30 REST vs 10-31~11-07 Spring AI 2): 일정 초안 중앙값 2,103ms(13건) → 3,366ms(18건). 영상·장소 수·프롬프트 길이(1,214 → 852토큰)가 달라 비교 불가이고, Spring AI와 무관한 Python 호출(`extract_places.*`)도 같은 기간 비슷하게 흔들렸다.
- 2026-10-31 배포 직후 HTTP/2 시간 초과 6/6 → HTTP/1.1 고정 뒤 5.1초 성공(#253·#254, `spring-ai-kafka-performance.md`). 그 뒤 11-08~11-13 5일: 성공 약 109회·실패 약 12회(11-12 밤에 몰림, 원인 미확인), 최대 19.1초.
- Grafana 캡처(11-09~11-13 호출 시간·수·토큰, 11-12 15:00~17:30 자원)는 PDF 5절.

## 한계
- 로컬 절대값은 운영(1/8 OCPU)과 다르다. p99는 표본 30에서 최대값에 가깝다. 운영 p50/p95는 분포 지표가 없어 못 구한다. 10-31 Grafana 원본은 삭제됐다.
- 옛 REST 빌드를 운영에 다시 올려 같은 조건으로 재는 것은 사용자 영향 때문에 하지 않았다.

## 다시 재기
```
TROVA_BENCH_KEY=<Gemini 키> ./gradlew -I docs/bench/scripts/client-resource.init.gradle clientResourceBench \
  -PbenchOutput=docs/bench/results/client-resource-short-<날짜>.json -PbenchSamples=30 -PbenchPauseMillis=6000 -PbenchPrompt=short
# PDF: 내보내기 venv(reportlab·matplotlib·pillow·pymupdf)에서
python docs/bench/scripts/build_springai_rebench_pdf.py --font /System/Library/Fonts/Supplemental/AppleGothic.ttf
```
