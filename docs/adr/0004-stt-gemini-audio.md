# 0004 STT는 Whisper 셀프호스팅 대신 Gemini 오디오 직접 입력
- 상태: 확정 · 날짜: 2026-08-20 · 근거: CLAUDE.md "STT 방식 결정 배경", 실사용 검증(유튜브 쇼츠 25/25 성공, 무료 티어 429 없음)

## 결정
오디오 파일을 Gemini에 직접 넣어 STT와 장소 추출을 한 번의 호출로 처리한다.

## 이유
- Gemini Flash-Lite 무료 티어(15 RPM / 1,000 RPD / 25만 TPM)는 토큰을 과금하지 않아 0원이다([0002](0002-cost-zero-free-tiers.md)).
- 2026-06 Oracle Always Free Ampere A1이 4 OCPU/24GB → 2 OCPU/12GB로 줄었다. STT까지 셀프호스팅하면 서버 자원을 API·파이프라인과 다툰다.
- 한 번의 호출이라 구조가 단순하다.

## 비교한 대안
- 오픈소스 Whisper 셀프호스팅(원래 계획): 서버 자원 부족.
- OpenAI Whisper API: 유료.

## 다시 볼 조건
- Gemini 무료 티어 조건이 바뀌거나, 한도 때문에 분석 실패가 잦아질 때.
