# Rust 부분 비교 — 로컬 호환성·측정 결과 (#312)

> **이 문서의 숫자는 모두 로컬 macOS 개발 맥(Apple M5, 10코어, 32GB)에서 잰 값이다. 운영 서버(Oracle E2.1.Micro, Linux) 수치가 아니다.**
> 운영 서버 실행·Gemini/카카오 호출·8080 서버·배포는 하지 않았다. [구현 순서](../superpowers/plans/2026-11-04-rust-pipeline-pilot.md)의 1~5단계만 했다.

## 한 줄 결론

- **같은 결과를 만든다**: 고정 입력 24개(요청 48개) 모두 Python 출력과 Rust 출력이 **바이트 단위로 같았다**. 실패 입력 27개도 오류 종류·종료 코드가 같았다.
- **프로그램끼리(CLI 대 CLI) 비교하면 Rust가 빨랐다**: 짝 72개 모두 Rust가 빨랐다. 하지만 차이의 대부분은 Python 프로그램을 새로 띄우는 비용과 출력 JSON을 만드는 비용이다(아래 "해석" 참고).
- **지금 파이프라인에 붙이는 방식으로 재면 Rust가 오히려 느렸다**: Python이 Rust 보조 프로그램을 불러 결과를 다시 읽는 경로는, 지금처럼 Python 안에서 `build_parts`를 바로 부르는 것보다 중앙값 기준 약 3.6배(지속 보조 프로세스) ~ 12배(매번 새로 띄움) 느렸고, Python 쪽 최대 메모리도 약 79MiB 늘었다(1회성 측정).
- 설계에서 제안한 검토 기준(건당 0.5초 수준 절감 또는 16MiB 수준 메모리 절감)에 못 미친다. 통합 경로는 오히려 나빠졌다. **로컬 근거로는 Python 유지를 추천한다.** 운영 실험(6단계 이후)은 사용자가 고를 때만 한다.

## 쉬운 용어 풀이

- **parts**: Gemini에 보낼 요청 본문 안의 조각 목록. 글(text), 유튜브 링크(file_data), 오디오/사진(base64로 바꾼 inline_data)이 순서대로 들어간다.
- **base64**: 오디오·사진 같은 이진 파일을 JSON에 넣을 수 있는 글자로 바꾸는 방식. 크기가 약 4/3배가 된다. **Python의 base64는 내부가 C로 짜여 있어 원래 빠르다.** 그래서 Rust가 빠를 거라고 미리 가정하지 않았다.
- **새 프로세스 모드(fresh)**: 요청(관측)마다 프로그램을 새로 띄운다. 프로그램 시작·모듈 불러오기 시간이 매번 들어간다.
- **지속 프로세스 모드(persistent)**: 프로그램을 한 번 띄워 두고 줄 단위로 요청을 계속 보낸다. 시작 시간은 빠진다.
- **관측 1개**: 같은 입력의 collect(1차 후보 모으기) 준비 + filter(선별) 준비, 즉 요청 2개를 합친 것.
- **벽시계 시간(wall)**: 측정기가 실제로 기다린 시간. 새 프로세스 모드는 프로그램 시작~종료까지, 지속 모드는 첫 요청 보내기~두 번째 응답 받기까지.
- **준비 시간(prep)**: 프로그램 안에서 잰 시간. 요청 JSON 해석 → 파일 읽기·base64 → 출력 JSON 만들기. 입출력 전달은 뺐다.
- **최대 RSS**: 그 프로세스가 실제 메모리에 가장 많이 올렸던 크기. `os.wait4`로 받은 값이며 `/usr/bin/time -l`이 보여주는 값과 같은 출처다(교차 확인: aud-04 입력에서 Python 146.6MB, Rust 44.3MB로 본 측정과 같은 수준).
- **짝 차이**: 같은 입력·같은 반복 번호에서 (Python − Rust). 양수면 Rust가 빠르다.
- **A/A 측정**: Python을 Python과 비교한 것. 측정기 자체의 흔들림을 보려고 먼저 했다.
- **중앙값**: 값을 줄 세웠을 때 가운데 값. 큰 입력 몇 개에 덜 끌려간다.

## 만든 것

| 파일 | 하는 일 |
|---|---|
| `tools/rust-parts-pilot/python/parts_control.py` | Python 제어 CLI. 기존 `pipeline-test/extract_places.py`의 `build_parts`를 **그대로 import해서 호출**한다(제품 코드는 바꾸지 않음). |
| `tools/rust-parts-pilot/src/lib.rs`, `src/main.rs` | Rust 비교 CLI. 외부 크레이트는 `serde_json`, `base64`만(무료, `Cargo.lock` 고정). 비동기 런타임 없음. |
| `tools/rust-parts-pilot/src/mime_defaults.rs` | Python `mimetypes` 기본 표를 옮긴 자동 생성 파일(`scripts/gen_mime_defaults.py`, Python 3.14.7 기준). |
| `tools/rust-parts-pilot/scripts/make_fixtures.py` | 고정 입력 24개 + 실패 입력 27개를 결정적으로 생성. 해시는 `fixtures/fixtures.lock.json`. |
| `tools/rust-parts-pilot/scripts/check_compat.py` | 호환성 검사 → `results/rust-parts-pilot-compat-20261104.json` |
| `tools/rust-parts-pilot/scripts/bench.py` | 시간·메모리 측정 → `results/rust-parts-pilot-local-20261104.json`, A/A는 `results/rust-parts-pilot-aa-20261104.json` |
| `tools/rust-parts-pilot/scripts/integration_cost.py` | 통합 경로 1회성 측정 → `results/rust-parts-pilot-integration-20261104.json` |
| `docs/bench/results/rust-pilot-comparison-local-20261104.json` | [비교 틀](results/rust-pilot-comparison-template.json)에서 로컬로 채울 수 있는 칸만 채운 사본. 원본 틀과 운영 칸은 `null` 그대로. |

두 CLI의 약속(입력 계약): JSON 객체 하나에 `prompt`(필수), `transcript`, `audio_path`, `frame_paths`, `candidates`, `video_uri`, `post_description`, `photo_post`. 모르는 키나 틀린 형식은 `invalid_manifest`. 출력은 parts 배열을 UTF-8·공백 없음·객체 키 정렬 JSON 한 줄로 낸다(배열 순서와 문자열은 그대로). 실패하면 한 번 실행 모드는 stdout을 비우고 종료 코드 1, 지속 모드는 `{"error":{"kind":...}}` 한 줄을 내고 다음 요청을 계속 받는다. 이 CLI 출력 형식은 실험용이며 실제 Gemini 요청 형식을 바꾸지 않는다.

입력 계약 검사(형식 확인, 실험 한도 16MiB 초과 거절, serde_json이 파싱 단계에서 거절하는 값—NaN·짝 없는 대리 문자·128단계 이상 중첩·범위 넘는 숫자—을 Python도 같은 종류로 거절)는 두 CLI 모두에 있다. 그 뒤 Python은 기존 `build_parts`를 부르고, Rust는 같은 동작을 옮긴 코드를 실행한다.

## 고정 입력 24개

개인 자료·실제 키·URL 다운로드 없이 SHAKE-256 바이트 흐름으로 만든 **합성 파일**이다(JPEG/MP3 등 머리말만 흉내 냄 — parts 준비는 파일을 해석하지 않고 바이트만 읽으므로 충분하다). 다시 만들어도 해시가 같다(`make_fixtures.py --verify`). 생성물 약 40MB는 git에서 뺐고 해시만 커밋했다.

| 종류 | 입력 | 확인한 것 |
|---|---|---|
| 유튜브 참조 4 | yt-01~04 | 링크만, 설명 있음, Unicode URL·이모지·따옴표, 빈 설명·빈 자막(건너뜀). 미디어 파일을 읽지 않음 |
| 텍스트 4 | txt-01~04 | 한글, 영문, 제어 문자·빈 선택 필드, 긴 자막(약 260KB) |
| 오디오 4 | aud-01~04 | 8KiB mp3, 480KiB m4a(한글 파일명), 4MiB mp3, 8MiB 확장자 없음(대체값 `audio/mp3`) |
| 프레임 4 | frm-01~04 | 1장, 4장, 8장(한글 폴더·파일명), 8장 확장자 섞임·역순 배열·`./`·`//` 경로 |
| 오디오+프레임 4 | mix-01~04 | 자막·설명·후보, 4MiB 오디오+8장+특수문자 후보, wav+한글 폴더+빈 후보 목록 |
| 사진 게시물 4 | pho-01~04 | 1장, 10장(올린 순서), 사진 0장(문구 없음), 한글 경로·특수문자 후보 |

입력당 미디어 합계는 모두 16MiB 이하(가장 큰 것: aud-04 8MiB, mix-03 약 4.6MiB).

## 호환성 결과 (실측)

| 항목 | 결과 |
|---|---|
| 고정 입력 | **24/24 일치** (요청 48개, 한 번 실행 모드·지속 모드 둘 다 바이트 일치) |
| 내용 확인 | 출력의 base64를 풀어 원본 파일 해시와 비교, 미디어 순서가 입력 순서와 같음 — 48개 모두 문제 없음 |
| 실패·경계 입력 | **27/27 일치** — 없는 파일(오디오/2번째 프레임), 폴더, 빈 경로(`""` → Python에선 `.` 폴더), 파일 뒤 `/`(Python pathlib이 지워서 성공), 권한 없음, 경로 안 NUL, 16MiB 초과, `:`·`#`가 든 파일 이름(URL처럼 해석), `data:` 이름, `.mp3` 숨김 파일, `.mp3.gz`, 형식 오류·모르는 키·prompt 없음·배열 최상위·깨진 JSON·NaN·짝 없는 대리 문자·너무 깊은 중첩·잘못된 UTF-8·BOM·빈 줄·중복 키(마지막 값)·숫자 범위 초과 |
| 섞인 지속 세션 | 성공 요청 48개 + 실패 요청 27개를 한 프로세스에 섞어 보내도 75줄 출력이 바이트 단위로 같음 |

MIME(파일 종류 표시)는 Python 결과를 정답으로 삼았다. Python은 기본 표에 더해 **시스템 파일**(`/etc/mime.types` 등)을 읽는다. 이 맥에는 `/etc/apache2/mime.types`가 있어서 `.m4a`는 `audio/mp4a-latm`, `.wav`는 `audio/x-wav`가 나왔다(기본 표라면 `audio/mp4`, `audio/vnd.wave`). Rust도 같은 순서로 같은 파일을 읽게 해서 일치시켰다. **Linux 운영 서버에서는 시스템 파일과 Python 버전이 달라 MIME 결과가 다를 수 있고, 거기서의 일치는 확인하지 않았다(미검증).** 실제 파이프라인이 쓰는 이름(`audio.mp3`, `frame_*.jpg`, `photo-*.jpg`)은 두 표 모두 `audio/mpeg`, `image/jpeg`다.

## 로컬 측정 결과 (실측, macOS)

설정: 입력 24개 × 측정 3쌍 × 구현 2개 × 모드 2개 = **측정 관측 288개**, 워밍업 96개는 따로 보존. 입력 순서 seed 309, (입력 위치+반복 번호) 홀짝으로 먼저 실행할 구현 교대, 동시성 1, 파일 캐시는 비우지 않음. 새 프로세스 192개, 지속 프로세스 48개. 출력 불일치 0, 실패 종료 0. 실행 시각 2026-11-04 10:43:33~10:43:40(+09:00).

### 모드별 요약 (관측 1개 = 요청 2개)

| 모드 | 구현 | 표본 | 벽시계 중앙값 | 벽시계 평균 | 준비 중앙값 | 준비 평균 | 최대 RSS 중앙값 | 최대 RSS 최대 |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 새 프로세스 | Python | 72 | 34.984ms | 42.356ms | 2.571ms | 9.317ms | 27.4MiB | 139.8MiB |
| 새 프로세스 | Rust | 72 | 3.019ms | 4.641ms | 0.817ms | 1.900ms | 3.0MiB | 42.3MiB |
| 지속 프로세스 | Python | 72 | 2.430ms | 9.591ms | 2.104ms | 8.501ms | 27.5MiB* | 396.8MiB* |
| 지속 프로세스 | Rust | 72 | 0.798ms | 2.661ms | 0.491ms | 1.506ms | 3.1MiB* | 106.5MiB* |

\* 지속 모드의 최대 RSS는 입력 하나를 맡은 프로세스 묶음(워밍업 1회+측정 3회) 전체의 값이다. 요청 하나의 최대가 아니다.

짝 차이(Python − Rust, 양수 = Rust가 빠름):

| 모드 | 벽시계 중앙값 | 벽시계 범위 | 준비 중앙값 | 준비 범위 | Rust가 빠른 짝 |
|---|---:|---:|---:|---:|---:|
| 새 프로세스 | 31.951ms | 29.102 ~ 86.158ms | 1.908ms | −0.256 ~ 54.637ms | 72/72 (벽시계) |
| 지속 프로세스 | 1.637ms | 0.022 ~ 50.491ms | 1.582ms | 0.022 ~ 51.064ms | 72/72 (벽시계) |

A/A(Python 대 Python, 같은 규칙): 짝 차이 중앙값 새 프로세스 −0.021ms(범위 −10.584 ~ 4.086ms), 지속 −0.001ms(범위 −3.837 ~ 5.136ms). 측정기의 순서 쏠림은 중앙값으로는 거의 없지만, 개별 짝은 수 ms 흔들릴 수 있다.

### 입력별 중앙값 (일부)

| 입력 | 새 프로세스 벽시계 Py / Rust | 새 프로세스 준비 Py / Rust | 지속 준비 Py / Rust |
|---|---:|---:|---:|
| txt-01 한글 자막 | 31.96 / 2.47ms | **0.10 / 0.32ms** | 0.06 / 0.03ms |
| txt-04 긴 자막 | 35.31 / 3.64ms | 2.52 / 1.04ms | 2.35 / 0.68ms |
| aud-01 8KiB | 32.54 / 2.43ms | 0.86 / 0.36ms | 0.16 / 0.06ms |
| aud-03 4MiB | 69.46 / 10.54ms | 33.00 / 5.85ms | 30.53 / 5.04ms |
| aud-04 8MiB | 101.27 / 19.06ms | 62.59 / 12.21ms | 60.33 / 11.38ms |
| mix-03 4MiB+8장 | 78.43 / 11.99ms | 42.42 / 6.80ms | 38.59 / 6.02ms |
| pho-02 사진 10장 | 49.02 / 6.40ms | 14.94 / 3.11ms | 14.19 / 2.61ms |

**Python이 더 빠른 곳**: 새 프로세스 모드에서 미디어가 없는 입력 8개(yt-01~04, txt-01~03, pho-03)는 **준비 시간만 보면 Python이 더 짧았다**(중앙값 약 0.09~0.10ms 대 0.32~0.33ms). 같은 입력이 지속 모드에서는 Rust가 짧았다. 새로 뜬 Rust 프로세스의 첫 요청 비용 때문으로 보이지만 원인은 따로 확인하지 않았다(추정).

## 통합 경로 비용 (실측, 1회성)

실제로 쓰려면 Python 작업 프로세스가 Rust 보조 프로그램에 요청을 보내고 결과 JSON을 다시 파이썬 리스트로 읽어야 한다. 같은 끝점(메모리에 parts 리스트가 준비됨)까지 세 방식을 쟀다. 방식마다 Python 프로세스 1개로 한 번 실행했다(입력 24개 × 측정 3회 = 72관측, 워밍업 1회 별도, 순서 고정 direct → spawn → persistent).

| 방식 | 중앙값 | 평균 | aud-04 중앙값 | mix-03 중앙값 | Python 최대 RSS | 자식 최대 RSS |
|---|---:|---:|---:|---:|---:|---:|
| 지금 방식: Python 안에서 `build_parts` 직접 호출 | **0.477ms** | **2.234ms** | **17.07ms** | **9.18ms** | **248.5MiB** | — |
| 요청마다 Rust 보조 프로세스 새로 띄움 | 5.819ms | 10.785ms | 52.61ms | 33.37ms | 330.3MiB | 31.6MiB |
| Rust 보조 프로세스를 띄워 두고 줄 단위 요청 | 1.711ms | 6.008ms | 45.22ms | 24.36ms | 327.7MiB | 50.6MiB |

- 지금 방식이 가장 빨랐다. Rust 보조 지속 프로세스는 중앙값 약 1.2ms 느렸고(−258%), 새로 띄우는 방식은 약 5.3ms 느렸다.
- Python 프로세스 최대 RSS는 direct보다 약 79MiB 컸다(큰 parts JSON을 다시 읽어 파이썬 문자열로 만드는 비용). 자식(Rust) RSS는 따로 적었고, 부모·자식 최대값을 더해 "동시 최대"라고 하지 않았다.
- 세 방식의 Python RSS에는 결과 확인용으로 direct 결과를 한 벌 더 만든 메모리가 똑같이 들어 있다. 프로세스 트리 주기 샘플은 하지 않았다.
- 결과 리스트는 세 방식 모두 direct와 같았다(불일치 0).

## 해석 — 실측과 추정 구분

- **실측**: 위 표의 모든 숫자, 24/24·27/27 일치, 통합 경로가 더 느리다는 것.
- **추정**: 새 프로세스 모드의 약 32ms 차이는 대부분 Python 인터프리터 시작과 `extract_places` 불러오기 비용으로 보인다(텍스트 전용 입력의 Python 준비 시간은 0.1ms인데 벽시계는 약 32ms). 실제 파이프라인은 Python 프로세스를 어차피 띄우므로 이 차이는 Rust로 옮겨도 없어지지 않는다.
- **추정**: Python CLI 준비 시간의 큰 부분은 base64가 아니라 출력 JSON(`ensure_ascii=False`)을 만드는 비용으로 보인다. aud-04에서 `build_parts`만 부르면 약 17ms(통합 측정의 direct)인데 CLI 준비 시간은 약 60ms였다. 두 값은 다른 측정에서 나왔으므로 정확한 분해는 아니다. 실제 파이프라인은 다른 방식(`json.dumps` 기본값)으로 요청 본문을 만들므로 이 CLI 출력 비용을 그대로 파이프라인 비용으로 보면 안 된다.
- 운영 기록에서 분석 1건은 약 12초였다([배경](rust-pipeline-feasibility-20261104.md)). 로컬에서 Python 준비가 가장 오래 걸린 aud-04도 수십 ms라, Rust가 이 부분을 전부 없애도 전체 분석 시간에는 거의 영향이 없다(운영 E2.1.Micro는 더 느릴 수 있어 그대로 옮길 수는 없음 — 미측정).

## 한계

- macOS 로컬 값이다. CPU 구조(arm64 대 운영 x86_64), OS, 파일 시스템, 메모리가 운영과 다르다. 운영 칸은 모두 `null`이다.
- 파일 캐시를 비우지 않았다(같은 파일을 번갈아 읽음). 다른 앱이 돌던 일반 개발 맥이다.
- MIME 기본 표는 Python 3.14.7에서 생성했다. 운영 Python 버전·시스템 파일에서의 일치는 미검증.
- 통합 경로 측정은 방식별 1회 실행, 순서 고정이라 순서 효과를 통제하지 않았다.
- 계획 6단계 이후(운영 서버 288관측, 운영 메모리 트리 기록, 채택 판단)는 하지 않았다.

## 다시 재기

```bash
cd tools/rust-parts-pilot
~/.cargo/bin/cargo test --locked
~/.cargo/bin/cargo build --release --locked
python3 scripts/make_fixtures.py            # 고정 입력 만들기(--verify 로 해시 확인)
python3 scripts/check_compat.py             # 호환성 검사
python3 scripts/bench.py --aa               # A/A
python3 scripts/bench.py                    # 본 측정(seed 309, 측정 3쌍, 워밍업 1)
python3 scripts/integration_cost.py         # 통합 경로 1회성
cd ../.. && python3 -m unittest discover -s tools/rust-parts-pilot/tests
```

환경 기록(각 결과 JSON의 `environment`): Python 3.14.7, rustc/cargo 1.99.1, `extract_places.py` SHA-256 `12fd5d7e…dae9f`, `build_parts` 소스 SHA-256 `449524a8…6ae44e`, `Cargo.lock` SHA-256 `8fd3f173…c02a00`.
