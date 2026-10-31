# 장소 추출 평가 세트 (evalflow)

- 무엇: `pipeline-test/samples.txt`의 유튜브 쇼츠 10개(지역별 2개)를 실제 파이프라인으로 돌려 장소 이름을 정답과 비교한다(재현율·정밀도).
- 실행: evalflow `eval` 스킬 → `eval.sh run place-extract --label before|after`. 영상당 Gemini 2회(후보 수집·선별), 10개면 20회.
  운영과 같은 키를 쓰므로 운영이 한가한 시간에, 설정의 `maxCalls` 안에서만 돌린다.
- 정답: `promptfooconfig.yaml`의 `expected`. **"(초안)"이 붙은 케이스는 2026-10-31 파이프라인 출력에서 옮긴 것**이라 사람이 영상을 보고
  확인하기 전까지는 "같은 입력에서 결과가 바뀌었는가(회귀)"만 잰다. 확인한 케이스는 "(초안)"을 떼고 날짜를 적는다.
- 결과: `docs/eval/eval-place-extract.jsonl`·`.md`·`-cases/`에 남고 PR에 함께 커밋한다.
