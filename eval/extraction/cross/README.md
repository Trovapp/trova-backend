# 정답 세트 교차 판정 (#437)

같은 입력(제목·게시물 설명·한국어 자막·프레임 8장)을 Gemini(파이프라인 `extract_places`)와 GPT(Codex 구독)에 따로 넣고,
둘이 같은 장소를 내면 정답 후보(`agreed.csv`), 다르면 사람이 확인할 목록(`mismatches.csv`)으로 나눈다.
Gemini는 오디오도 받지만, GPT(Codex)에는 오디오를 넘길 수 없다. 그래서 자막 없는 영상에서는 GPT가 프레임과 글만 본다.

## 파일

| 파일 | 내용 |
|---|---|
| `candidates.csv` | 새 샘플 200개(c001~c200). yt-dlp 검색(28개 지역×검색어)으로 모으고, Codex가 제목을 보고 장소 소개 영상을 고름. 순서는 시드 437로 섞어서 앞쪽 30개도 지역이 섞이게 함 |
| `../../../pipeline-test/samples_437.txt` | 같은 200개를 `samples.txt` 형식으로(지역별) |
| `human_baseline.csv` | 기존 사람 정답(`answers.csv`)이 있는 영상 24개(v001~v026). GPT 판정자 점검용 |
| `gpt_schema.json` | Codex 출력 형식 |
| `<날짜>/gemini/*.json`, `<날짜>/gpt/*.json` | 샘플별 추출 결과(장소 이름만, 영상·자막은 커밋하지 않음) |
| `<날짜>/compare.csv`·`mismatches.csv`·`agreed.csv`·`summary.json` | `compare` 결과 |
| `<날짜>/human_compare.csv`·`human_summary.json` | GPT와 사람 정답 비교(`score-human`) |

영상·프레임·자막·오디오는 `pipeline-test/work/cross/<샘플>/`(gitignore)에만 둔다.

## 실행 (Windows 노트북 기준)

파이썬은 WSL 우분투에서 돌린다. **Gemini 키는 로그인 셸(`bash -l`)에서만 잡히므로 `gemini`는 반드시 `bash -l`로 실행**한다.

```bash
# WSL — 워크트리 루트에서
python3 eval/extraction/cross_check.py prepare    --ids c031,c032      # yt-dlp만 사용(Gemini 호출 없음)
python3 eval/extraction/cross_check.py gpt-prompt --ids c031,c032
python3 eval/extraction/cross_check.py gemini     --run eval/extraction/cross/20261116 --ids c001,c002   # Gemini 2번/샘플
python3 eval/extraction/cross_check.py compare    --run eval/extraction/cross/20261116
python3 eval/extraction/cross_check.py score-human --run eval/extraction/cross/20261116
```

```powershell
# PowerShell — GPT 쪽(Codex, read-only 샌드박스, 프레임 첨부)
.\eval\extraction\cross_gpt.ps1 -Run eval\extraction\cross\20261116 -Ids c001,c002
```

- `gemini`는 Gemini 429(분당 재시도 포함)나 하루 한도 표식이 stderr에 보이면 그 샘플에서 바로 멈추고 `gemini/STOPPED.txt`를 남긴다.
- 이미 결과 파일이 있는 샘플은 건너뛰므로 같은 명령을 다시 돌려도 이어서 진행한다.
- `prepare`는 파이프라인의 `download.download` 대신 한국어 자막만 받는다. 영어 자동 번역 자막을 연달아 받으면 유튜브가 429를 줘서 영상까지 실패했다(2026-11-16, 5개 중 4개).
