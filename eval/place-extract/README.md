# 장소 추출 회귀 평가 (evalflow)

- 무엇: `eval/extraction`의 정답 영상(사람이 영상을 보고 적은 `answers.csv`)을 **서버 없이 파이프라인만** 돌려 장소 이름의 재현율·정밀도를 잰다.
  프롬프트·파이프라인 플래그(예: `PIPELINE_FILTER_TEXT_ONLY=1`)를 바꾸기 전후 비교용. 좌표까지 보는 정식 측정은 `eval/README.md` Part A(ADR 0022).
- 정답: 여기에 적지 않는다. `python3 eval/place-extract/build_config.py`가 `answers.csv`에서 `promptfooconfig.yaml`의 tests를 만든다
  (기본 유튜브 쇼츠 10개, `--platform all --limit 24`로 인스타 릴스까지). 정답을 고쳤으면 다시 만든다.
- 실행 전: ADR 0002 — Gemini 키를 운영과 나눠 쓰므로 **오늘 사용량부터 확인**(qaflow `qa-env quota`). 영상당 Gemini 2회, 10개면 20회.
  설정 `~/.config/evalflow/trova-backend.json`의 `maxCalls`(30)·호출 간격 6초 안에서만, 운영이 한가한 시간에 돌린다.
- 실행: evalflow `eval` 스킬 → `eval.sh run place-extract --label before|after`, `eval.sh diff place-extract before after`.
- 결과: `docs/eval/eval-place-extract.jsonl`·`.md`·`-cases/`에 남고 PR에 함께 커밋한다. 케이스 10개 미만이면 통과율 변화를 일반화하지 않는다.
