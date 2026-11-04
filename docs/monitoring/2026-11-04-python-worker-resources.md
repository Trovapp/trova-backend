# Python 분석 작업의 CPU·메모리 계측 (#311)

**목적**: Rust 같은 다른 구현과 비교할 기준값을 운영에서 모은다. #309 분석에서는 Python 작업 프로세스의 CPU와 메모리 기록이 없어서 Rust로 줄일 수 있는 몫을 알 수 없었다.

**상태**: 계측 코드만 추가했다. **실제 영상으로 잰 값은 아직 없다**(로컬·운영 모두). Gemini 하루 한도를 로컬과 운영이 같이 쓰므로, 영상 분석을 돌려 값을 모으는 일은 사용자가 선택한 뒤에 한다.

## 쉬운 용어

- **CPU 시간**: 서버가 직접 계산한 시간. Gemini 응답을 기다리는 동안에는 늘지 않는다. 그래서 "분석 12초 중 CPU 0.3초"처럼 벽시계 시간보다 훨씬 작을 수 있다.
- **자식 프로세스**: Python이 실행시키는 yt-dlp(영상 받기)·ffmpeg(오디오·프레임 뽑기). 별도 프로그램이라 CPU를 따로 센다.
- **최대 RSS**: 그 프로세스가 실제 메모리에 가장 많이 올렸을 때의 크기.

## 결과 JSON의 `resources` (run_pipeline.py → Java)

| 키 | 뜻 |
|---|---|
| `workerWallMs` | `run_pipeline.py`가 모듈을 처음 읽은 때부터 결과를 만들 때까지 |
| `cpuUserMs`, `cpuSystemMs` | Python 프로세스 자신의 CPU 시간 |
| `childCpuUserMs`, `childCpuSystemMs` | 끝난 자식 프로세스들(yt-dlp·ffmpeg)의 CPU 시간 합 |
| `maxRssKib` | Python 프로세스의 최대 메모리(KiB). 리눅스는 `VmHWM`, macOS는 `ru_maxrss`(#320: 리눅스 `ru_maxrss`는 부모 JVM 값이 남음) |
| `childMaxRssKib` | 자식 중 **가장 컸던 하나**의 최대 메모리. 합계가 아니며, 부모와 동시에 쓴 양도 아니다 |
| `<단계>CpuMs`, `<단계>ChildCpuMs` | download·audio·frames·gemini 단계별 CPU. gemini 단계의 CPU에는 요청 준비·응답 해석이 들어간다 |
| `buildPartsCalls`, `buildPartsUs`, `buildPartsCpuUs` | Rust 비교 대상인 `build_parts`(파일 읽기·base64·parts 만들기) 호출 수와 시간 합(마이크로초) |
| `requestJsonUs`, `requestJsonCpuUs`, `requestBytes` | Gemini 요청 본문 JSON 만들기 시간과 보낸 크기 합(재시도는 같은 본문을 다시 쓰므로 한 번만 셈) |
| `processWallMs` | Java가 잰 프로세스 시작~종료 시간. `processWallMs - workerWallMs`가 인터프리터 시작·모듈 읽기·종료 비용 |

실패로 끝난 작업(예외 종료)은 결과 JSON이 없어서 기록되지 않는다.

## 로그와 지표

- 로그: `ProcessingJob {id} Python 자원: Python CPU 350ms · 자식 CPU 1000ms · Python 최대 메모리 40.0MiB · ... · 요청 2.0KiB`
- 지표(태그는 고정값만):
  - `trova_pipeline_worker_cpu_milliseconds{process="worker"|"children"}`
  - `trova_pipeline_worker_max_rss_bytes{process="worker"|"largest_child"}`
  - `trova_pipeline_worker_overhead_seconds` (시작·종료 비용)
  - `trova_pipeline_request_prep_seconds{part="build_parts"|"request_json"}`

## 운영 반영 때 주의

- 새 파일 `pipeline-test/worker_resources.py`가 생겼다. JAR만 바꾸면 안 되고, `run_pipeline.py`·`stage_timer.py`·`extract_places.py`와 함께 **운영 Python 폴더에 같이 복사**해야 한다(없으면 `import worker_resources`에서 모든 분석이 실패한다).
- 예전 JAR + 새 Python, 새 JAR + 예전 Python 조합 모두 동작한다(모르는 JSON 키는 무시하고, 없으면 빈 값).

## 다음에 할 일 (아직 하지 않음)

1. 배포 뒤 실제 분석 몇 건(유튜브 직접 / 인스타 릴스 다운로드 / 사진 게시물)의 `Python 자원` 로그를 모아 기준표를 만든다.
2. 그 값에서 `build_parts`·요청 JSON이 전체 분석 시간의 몇 %인지 보고, Rust 비교(#312) 결과와 합쳐 통합할 가치가 있는지 판단한다.
