# Trova 성능 비교 PDF

[6쪽 보고서](trova-performance-comparison-20261101.pdf)는 기존 REST/async와 Spring AI 2/Kafka의 호출 지연·큐 대기·자원·신뢰성·측정 한계를 비교한다. 새 확대 실험(Java 각20회·운영 각6건)을 중심으로 하고, 과거 기록은 별도로 표시한다. 세 빌드 전체를 동일 조건으로 측정한 결과로 해석하지 않는다.

근거는 PR289를 머지한 소스 `0c811cd`의 고정 스냅샷이다. PDF 생성은 저장된 JSON만 읽고 새 API 호출/운영 전환을 하지 않는다. 원본 및 분석 해시를 확인하며 링크도 해당 스냅샷에 고정했다. [검수 결과](performance-pdf-validation-20261101.json)에 파일 해시·페이지 수·숫자/음수 부호·한글 추출·여백/링크 검사·빌드 결과를 보관했다. 6쪽을 렌더링해 시각 검수했다.

## 재생성

내보내기 전용 로컬 환경을 사용한다. 프로젝트 런타임 의존성은 바꾸지 않는다.

```bash
python3 -m venv /tmp/trova-report-venv
/tmp/trova-report-venv/bin/pip install reportlab==5.1.0 matplotlib==3.11.2
/tmp/trova-report-venv/bin/python docs/bench/scripts/build_performance_pdf.py \
  --font /System/Library/Fonts/Supplemental/AppleGothic.ttf \
  --output docs/bench/reports/trova-performance-comparison-20261101.pdf
```

다른 OS에서는 임베딩 가능한 한글 TTF를 `--font`로 지정한다. 시스템 글꼴 원본은 레포에 포함하지 않았다. 수학용 음수 기호가 일부 한글 글꼴에서 사라지는 문제를 피하도록 본문은 ASCII 하이픈으로 정규화한다. 원본 통계의 부호는 유지된다. 시각 검수에는 별도 로컬 도구 PyMuPDF 2.0를 사용했다. PDF 생성 시각 등 메타데이터 때문에 재생성 파일의 바이트 해시는 달라질 수 있다.


## 쉬운 운영 결과 PDF (2026-11-01, #300)

`trova-plain-performance-20261101.pdf`는 용어 설명과 운영 결과 중심의3쪽 보고서다. 기존6쪽 상세 보고서는 보존한다. 새 운영 전후는11.884→16.745초로 악화했으며 각1회/변경후재시작이라는 조건을 함께 표시한다. 영상분석 Python과 일정생성 Spring AI 경로를 구분하고, 일정 AI의 시간대 기록4.021→5.406초와 변경후시도1/대기0도 명시한다.

같은 내보내기 venv와 한글 TTF로 `python docs/bench/scripts/build_plain_performance_pdf.py`를 실행한다. 저장된 원본만 읽으며 API/서버/DB에 접근하지 않는다. 출력 옆 `.sources.json`에 원본/결과 해시를 기록한다.3쪽 한글/숫자/여백 및 렌더시각검수는 `plain-performance-validation-20261101.json`,전체build는 `plain-report-build-20261101.json` 참고. 운영보충조회도구는 별도 `read_operational_supplement.py`이며 명시적 비공개 env와READ ONLY DB만 사용하고 초안본문을 출력하지 않는다.

## 도입별 전후 비교 PDF (2026-11-11, #388)

이력서·면접용으로 두 도입을 따로 정리한 보고서다. 숫자는 위 보고서와 같은 원자료(`docs/bench/results/`)와 ADR 0020·0021에서 옮겼고 새 측정은 없다.

- `trova-kafka-queue-before-after-20261111.pdf` — Kafka 대기열(ADR 0021): 재시작에 강한 처리, 운영 분석 시작 +약 0.2초, 브로커 장애 시 자동 넘기기, 자동 초안 확장(#390)과 운영 강제 종료 실험(11-12)
- `trova-spring-ai-2-before-after-20261111.pdf` — Spring AI 2(ADR 0020): 속도 차이 입증 못 함, 숨은 재시도(최대 15회) 제거, 운영 HTTP/2 시간 초과 → HTTP/1.1

원본은 `src/*.html`(공용 `src/style.css`). 재생성:
```bash
C="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
"$C" --headless=new --no-pdf-header-footer --user-data-dir=/tmp/trova-pdf --print-to-pdf=out.pdf "file://$PWD/docs/bench/reports/src/kafka.html"
```
