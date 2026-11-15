# 02 인스타그램 차단(429) 자동 재시도 설계 검토 — 이슈 #365 (읽기 전용)

먼저 `docs/codex-tasks/00-common-rules.md`를 읽고 따른다. 이 작업은 **파일을 하나도 고치거나 만들지 않는다.** 결과는 마지막 메시지로만 낸다. 구현은 하지 않는다.

## 목표

이슈 #365의 제안("차단이면 작업을 실패 대신 대기로 두고 30분·2시간 뒤 자동 재시도, 최대 N회")을 지금 코드 위에 올릴 때의 설계안 2~3개와 각 안의 변경 지점·위험을 정리한다. 고르는 것은 사용자다.

## 지금 코드 (2026-11-16 기준, 실행 전에 줄이 맞는지 다시 확인할 것)

- 감지: `pipeline-test/run_pipeline.py:182-184` — yt-dlp가 0이 아닌 코드로 끝나고 stderr에 `429` 또는 `Too Many Requests`가 있으면 `SOURCE_RATE_LIMITED ...`로 종료. 사진 게시물 정보 조회(`_photo_post_info` `:212`, 표식은 `:218-220`)도 같은 표식.
- 전달: `src/main/java/com/trova/backend/pipeline/PipelineRunner.java:194-200` — 종료 코드가 0이 아니면 stderr를 담은 `PipelineException`. 오류 구분은 JSON 필드가 아니라 stderr 문자열이다.
- 기록: `src/main/java/com/trova/backend/service/PlaceExtractionService.java:218-223` → `ProcessingJobLifecycleService.java:252-255` → `ProcessingJob.markAnalysisFailed`(`entity/ProcessingJob.java:132`, 안에서 `markFailed` `:114-118`). `status=FAILED`, 문구 "인스타그램이 잠시 요청을 막았어요", `retryCount += 1`.
- 앱 응답: `entity/ProcessingJob.java:156`, `controller/PlacesController.java:105-119` — 문구가 정확히 같으면 `failureReason=SOURCE_RATE_LIMITED`.
- 이미 있는 다시 보내기: `service/StuckJobRequeueService.java`(PENDING·PROCESSING만, FAILED는 보지 않음, 다시 보낸 기록은 메모리), `service/OrphanedJobRecoveryService.java`(15분 넘게 멈춘 작업을 FAILED로), `admin/action/RetryJobAction.java`(관리자가 새 작업을 만들어 재시도).
- 시작 가드: `ProcessingJobLifecycleService.java:58-62` — DONE·FAILED 작업은 다시 들어와도 건너뛴다.
- 동시 제한: `PipelineRunner.java:95`, `:123-129`, `src/main/resources/application.yml:167`의 `app.pipeline.download-concurrency`(기본 1, 유튜브가 아닌 URL 전체).

## 지켜야 할 결정

- 영상 분석 시작은 `ShareJobQueue.enqueue`로만(ADR 0021). 처리(`processNow`)는 같은 작업을 두 번 받아도 한 번만 돌아야 한다(ADR 0043).
- 분석을 다시 돌리면 Gemini를 또 부른다. 그래서 Kafka 기본 재처리는 꺼 두었고(ADR 0021), 멈춘 작업 다시 보내기도 작업당 한 번이다(ADR 0043). 자동 재시도 안은 "재시도 한 번이 Gemini를 몇 번 부르는가"와 하루 무료 한도에 주는 영향을 반드시 적는다(차단은 내려받기 단계라 Gemini 호출 전에 실패하는지 코드에서 확인할 것).
- 내려받기 분석은 동시에 1개(ADR 0038). 분석 결과 교체와 완료는 한 트랜잭션(ADR 0029).
- 공유 한도는 최근 7일 10개, 실패한 분석은 세지 않는다(`DailyQuotaService.checkShare`). 대기 상태가 한도에 어떻게 세어지는지 반드시 다룬다.
- 유료 서비스·로그인 쿠키·프록시는 안으로 내지 않는다(이슈 #365에서 이미 제외).

## 답할 질문

1. 대기를 어떻게 표현할 것인가: (가) 새 상태값, (나) FAILED 유지 + 재시도 시각 컬럼, (다) 새 작업을 예약해서 만들기. 각 안에서 `StuckJobRequeueService`·`OrphanedJobRecoveryService`·시작 가드·`/api/places/pending` 응답·관리자 재시도·공유 한도가 어떻게 바뀌어야 하는지 `경로:줄번호`로.
2. 재시도 시각·횟수를 어디에 저장하고 누가 깨우는가(스케줄러). 서버가 재시작돼도 이어지는가. `retryCount`를 그대로 써도 되는가(지금은 실패 기록 횟수).
3. 같은 사용자가 대기 중에 같은 링크를 다시 공유하면(`controller/SharesController.java:100` `createOrReuse`) 어떻게 되는가.
4. 한 번 막히면 다른 인스타 작업도 막힐 가능성이 높다. 차단 뒤 일정 시간 인스타 작업 전체를 미루는 장치가 필요한가, 어디에 둘 수 있는가.
5. 사진 파일 요청의 429는 지금 `SOURCE_RATE_LIMITED`로 드러나지 않는다(`pipeline-test/post_images.py:31-43` — 실패한 사진은 건너뜀, 429로 드러나지 않는다는 것은 추정). 범위에 넣을지와 넣는다면 바뀔 곳.
6. 각 안에 필요한 DB 변경(컬럼·인덱스)과 테스트 DB(H2)로 검증할 수 있는 범위, 운영에서만 확인되는 범위.
7. 자동 재시도와 `StuckJobRequeueService`의 한 번 다시 보내기가 겹쳐 한 작업이 여러 번 분석되는 경우가 생기는가.
8. 앱(별도 레포)에 필요한 응답 필드 변화. 기존 앱 버전이 새 상태를 받아도 깨지지 않는가.

## 완료 기준

- 안 2~3개를 표로 비교: 바뀌는 파일 수(대략), DB 변경, 기존 구조와 부딪히는 점, 서버 재시작 때 동작, 위험.
- 추천안 하나와 이유. 단 "추천"이라고 표시하고 결정처럼 쓰지 않는다.
- 추천안의 구현 순서를 PR 단위(각 PR이 `./gradlew build`를 통과할 수 있는 크기)로 나눈 목록.
- 코드에서 확인한 것과 추정을 구분한다.

## 검증

파일을 고치지 않으므로 `./gradlew build`는 돌리지 않는다. 대장이 근거 줄을 확인한 뒤 사용자에게 안을 보고하고, 사용자가 고르면 ADR과 `docs/superpowers/specs` 설계 문서로 옮긴다.
