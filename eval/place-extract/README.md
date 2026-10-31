# 장소 추출 회귀 평가 (evalflow)

- 무엇: `eval/extraction`의 정답 영상(사람이 영상을 보고 적은 `answers.csv`)을 **서버 없이 파이프라인만** 돌려 장소 이름의 재현율·정밀도를 잰다.
  프롬프트·파이프라인 플래그(예: `PIPELINE_FILTER_TEXT_ONLY=1`)를 바꾸기 전후 비교용. 좌표까지 보는 정식 측정은 `eval/README.md` Part A(ADR 0022).
- 정답: 여기에 적지 않는다. `python3 eval/place-extract/build_config.py`가 `answers.csv`에서 `promptfooconfig.yaml`의 tests를 만든다
  (기본 유튜브 쇼츠 10개, `--platform all --limit 24`로 인스타 릴스까지). 정답을 고쳤으면 다시 만든다.
- 실행 전: ADR 0002 — Gemini 키를 운영과 나눠 쓰므로 **오늘 사용량부터 확인**(qaflow `qa-env quota`). 기본 영상당 Gemini 2회, 10개면 약 20회이며 재시도/repair나 다운로드 폴백이 있으면 늘 수 있다.
  설정 `~/.config/evalflow/trova-backend.json`의 `maxCalls`(30)·호출 간격 6초 안에서만, 운영이 한가한 시간에 돌린다.
- 실행: evalflow `eval` 스킬 → `eval.sh run place-extract --label before|after`, `eval.sh diff place-extract before after`.
- 결과: `docs/eval/eval-place-extract.jsonl`·`.md`·`-cases/`에 남고 PR에 함께 커밋한다. 케이스 10개 미만이면 통과율 변화를 일반화하지 않는다.

## 채점 기준과 진단 (#260)

- 기존 `recall`/`precision`은 ADR 0022의 포함 문자열 채점을 유지한다. 추가한 `strict_recall`/`strict_precision`은 NFKC·공백 정규화 후 엄격한 이름 일치이며 각 예측/정답은 1번만 대응한다. 기본 채점 정책의 교체는 별도 결정 대상이다. 버전이 다른 수치는 개선율로 비교하지 않는다.
- 이름 표기가 다르다고 자동으로 같은 장소로 승인하지 않는다. `node eval/place-extract/rescore.cjs <기존 원본.json>`으로 API 호출 없이 재채점하고 `possibleAliases`를 사람 검토 목록으로 확인한다. 지오코딩 전 원문 이름의 채점이며 서비스 최종 정확도가 아니다.
- 평가 provider는 `PIPELINE_TRACE=1`로 후보 수집/선별 trace를 stderr에 남긴다(`TROVA_EXTRACTION_TRACE:`). promptfoo debug 로그에서 확인할 수 있다. 기본 stdout 이름 배열은 유지한다.
- `unmatchedCandidates`는 원문 후보와 최종 이름의 차이다. 이름 보정도 포함되므로 실제 탈락이나 탈락 이유로 단정하지 않는다.
- `PIPELINE_FILTER_AUDIT=1`을 추가하면 후보별 `reason`·`evidence`를 모델에 요청한다. 모델이 적은 근거는 검증된 사실이 아니다. 각 후보의 action(include/exclude)과 최종 places 번호를 연결한다. 누락·중복·잘못된 연결은 진단만 incomplete로 표시하고 유효한 장소 결과는 유지한다. 진단 오류만으로 재질의하지 않는다. 장소 결과 자체의 형식이 잘못됐을 때는 기존 self-repair와 실패 처리를 유지한다. 운영 기본은 끔.
- 환경 변수 실험은 반드시 `PROMPTFOO_CACHE_ENABLED=false`로 실행한다. 실제 AI 호출 전 사용량을 확인한다. 진단 옵션의 초기 실제 검증과 수정 후 테스트 범위는 아래 기록을 참고한다.

실제 영상 진단 검증 결과: `docs/eval/audit-validation-20261031.md`. 초기 프로토콜은 3개 중 1개가 진단 형식 오류로 실패했고, 보완 후 재측정은 3/3 추출 성공·진단 연결 complete였다. 논리 호출 12회·실제 시도 13회(전송 재시도 1회)였으며 형식 repair는 없었다. 진단 모드를 운영에 활성화하지 않는다. 재현은 `validate_audit.py --env-file <검증된 환경 파일>`이며 약 12회(+재질의 가능) 호출하므로 사용량 확인 후 실행한다.

수정 후에는 후보별 action/placeIndices의 구조적 일관성을 검사하고 진단 상태를 분리한다. 자연어 이유·근거의 진실성을 검증하는 것은 아니다. `validate_audit.py` 결과의 `diagnosticStatus`를 추출 성공과 별개로 확인한다. 실제 재측정 원본은 `docs/eval/audit-validation-20261031-143042.json`이며 표본 3개로 전체 품질을 일반화하지 않는다.

기존 두 assertion만 통과 여부/종합 점수에 참여한다. strict_recall/strict_precision은 그 결과의 namedScores로 추가하므로 기존 판정을 바꾸지 않는다. 별도 weight=0 assertion은 이 promptfoo 버전에서 namedScores도 0으로 만들므로 사용하지 않는다. strict 계산 불가 시 metadata.strictScoringError로 표시하며 정확도 0으로 임의 대체하지 않는다.

`NOT_PLACE_CONTENT` 정상 판정은 빈 예측으로 채점한다. 기대 장소가 있으면 recall 감점이며, 인증/속도 제한/소스/미디어 오류는 빈 예측으로 숨기지 않는다. 오프라인 rescore는 사례별 채점 불가를 분리해 totalCases/cases/unscorableCases와 status를 기록한다. 유효 사례가 0개면 평균은 null, CLI 종료 코드는 2다. partial 평균만 보고 전체 정확도로 해석하지 않는다.

서비스의 evalflow 요약 metrics 설정에 strict가 없으면 기본 요약에는 표시되지 않을 수 있다. 원본 namedScores 또는 rescore 결과에서 확인한다. 외부 evalflow 설정은 이 PR에서 자동 변경하지 않는다.
