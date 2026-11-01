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
