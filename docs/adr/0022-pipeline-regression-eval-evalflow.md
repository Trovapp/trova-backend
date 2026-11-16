# 0022. 장소 추출 회귀 측정은 evalflow 세트로, 정답은 eval/extraction/answers.csv 한 곳

- 상태: 일부 대체됨 → 0045 (AI 출력을 정답으로 넣지 않는다는 부분만)
- 날짜: 2026-10-31
- 관련: 0002(무료 한도 공유), 0003(파이프라인 검증 먼저), #257

## 배경
장소 추출 정확도 측정은 `eval/README.md` Part A(서버를 띄우고 영상별 작업을 만들어 좌표까지 채점, 비용 환산)가 있다.
파이프라인 플래그·프롬프트를 바꾸기 전후를 빠르게 비교하려면 서버 없이 파이프라인만 돌리고 환경 변수를 바꿔 가며
돌릴 수 있는 쪽이 필요했다(#257 B: 2차 호출에 미디어를 다시 올리지 않는 실험). Part A는 서버 프로세스의 환경 변수를
바꾸려면 서버를 다시 띄워야 하고, 로컬 서버 폴더는 Orca 터미널에서 접근이 막혀 있다.

## 결정
- 회귀 비교는 evalflow 세트 `eval/place-extract/`(promptfoo, 제공자가 `pipeline-test/run_pipeline.py`를 직접 부름)로 한다.
- **정답은 `eval/extraction/answers.csv` 한 곳에만 둔다.** evalflow 세트의 기대값은 `build_config.py`가 그 표에서 생성하고,
  손으로 옮겨 적거나 AI 출력을 정답으로 넣지 않는다(Part A 규칙 "영상을 보고 직접, AI 결과를 보지 않고"와 같음).
  → 2026-11-16 [0045](0045-answers-from-cross-check.md)에서 Gemini·GPT 교차 일치분과 판정분(Y)을 정답에 넣는 것으로 바뀜.
- 이름 비교는 공백 제거 뒤 포함 관계로 느슨하게 본다. 좌표·경유지·비용 판정이 필요하면 Part A를 돌린다.
- Gemini를 부르므로 실행 전 오늘 사용량을 확인하고(0002) 설정의 `maxCalls` 안에서만 돌린다.

## 결과
- 플래그 실험은 `eval.sh run place-extract --label before` → 플래그 켜고 `--label after` → `diff`로 새로 틀린 케이스를 본다.
- 정답을 고치면 `build_config.py`를 다시 돌려 두 체계가 같은 정답을 쓴다.
