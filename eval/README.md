# eval — Trova 성능 측정

기능을 고치지 않고 지금 Trova의 장소 추출 정확도(Part A)와 일정 생성 품질(Part B)을 숫자로 잰다.
기록과 결과 해석은 Claude Docs 문서 "Trova 성능 측정 기록"에 남긴다.

## 준비
1. 로컬 서버를 운영과 같은 main 코드로 띄운다(개발 DB). 측정 스크립트는 localhost가 아니면 멈춘다.
2. 서버를 띄운 것과 같은 `.env`를 불러온다: `set -a && source <로컬 서버>/.env && set +a`
   (JWT_SECRET, SPRING_DATASOURCE_*, KAKAO_REST_API_KEY, GOOGLE_PLACES_API_KEY)
   `EVAL_PROD_DB_HOST`·`EVAL_PROD_DB_USER`에 운영 DB 호스트·사용자 이름을 넣으면 실수로 운영 DB를 가리킬 때 멈춘다.
   Supabase pooler는 운영·개발이 같은 호스트라 사용자 이름까지 넣어야 구분된다(2026-10-03 실제로 걸림).
3. `pip install -r eval/requirements.txt`
4. 운영 서버의 `pipeline-test/*.py` 해시를 결과의 `meta.json`(`pipeline_sha256_16`)과 비교해 같은 설정인지 확인한다.
   - 해시는 `EVAL_PIPELINE_DIR`(기본값: 이 eval 폴더의 `pipeline-test`)에서 읽는다. 서버는 **자기 실행 폴더**의 스크립트를 쓰므로,
     로컬 서버를 다른 폴더(예: `.claude/worktrees/local-server`)에서 띄웠다면 `EVAL_PIPELINE_DIR=<그 폴더>/pipeline-test`를 줘야 한다.
     2026-10-03~04 Part A 결과 4개는 기본값으로 돌려 해시가 모두 `338fb364`(#105 이전 파일)로 적혔지만, 실제 서버는
     10/3 17:03은 178a68e(#105 이전), 10/4 00:21·00:45·16:14는 eeb790b·ef3c0ab·f7bf194(#105 이후, `89c88736`)였다(로컬 서버 폴더 git reflog로 확인).

## Part A — 장소 추출
1. `extraction/videos.csv`에서 쓸 영상의 `selected`에 `Y`.
2. `extraction/answers.csv`에 정답을 적는다(영상을 보고 직접, AI 결과를 보지 않고).
3. `python eval/extraction/run.py --repeat 2` → `extraction/results/<날짜>_<커밋>/`
4. `python eval/extraction/score.py <결과 폴더> [--usd-krw <환율> --usd-krw-source "<출처, 날짜>"]`
5. `review_needed.csv`의 "확인 필요"를 보고 `manual_matches.csv`에 판정(match / false_positive / skip)을 적은 뒤 다시 채점한다.

## Part B — 일정 생성
1. Part A를 `--keep --repeat 1`로 돌려 영상별 작업을 남긴다(`kept_jobs.json`).
2. `python eval/itinerary/run.py --jobs <Part A 결과>/kept_jobs.json --max-google-calls 300`
   — 영업시간은 Google Text Search(regularOpeningHours, Enterprise SKU 월 1,000건 무료)로 장소당 한 번 받아 `hours_cache.json`에 저장한다.
3. `python eval/itinerary/score.py <결과 폴더>`
4. 일정 에이전트(#106)를 같은 요청으로 잰다: 에이전트가 든 서버를 다른 포트(예: 8090)에 띄우고
   `EVAL_BASE_URL=http://localhost:8090 python eval/itinerary/run_agent.py --jobs <kept_jobs.json> --server-commit <커밋>`
   → `python eval/itinerary/score.py <결과>_agent` → `python eval/itinerary/compare.py <지금 기능 결과> <에이전트 결과>`(compare.md 표).
   시작 전 질문이 오면 "지역별로 나누기"로 답하고, 초안은 승인하지 않고 끝에 지운다.
5. 측정이 끝나면 남긴 작업을 지운다(개발 DB, 테스트 계정).

## 규칙과 상수 (2026-10-03 승인)
- 좌표 맞음: 정답 좌표에서 200m 안 / 다른 지역 오탐: 정답 좌표에서 50km 밖
- 하루 2~7곳, 같은 날 연속 이동 직선 30km 이하, 지역: 요청 영상 중심에서 50km 안
- Gemini `gemini-3.5-flash-lite` 유료 환산 단가: 입력 $0.30 / 출력 $2.50 (100만 토큰당, 2026-10-01 가격표). 실제 청구는 무료 티어라 0원.
