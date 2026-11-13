# 앱 시작 시간·메모리: 기본 JAR · AppCDS · Spring AOT 비교 (#410)

**한 줄 결론.** CPU를 운영처럼 작게(0.25개) 묶었을 때 앱이 UP 되기까지 기본 137.6초 → **AppCDS 79.3초(−42%)**, **AOT+AppCDS 62.5초(−55%)**였다(각 3회 중앙값, 로컬 Docker).
AOT만 쓰면 109.7초(−20%)라 이득의 대부분은 CDS(클래스 읽기 캐시)에서 나온다. 메모리(RSS)는 AOT+CDS가 −42MiB로 가장 작게 줄었다.
운영은 바꾸지 않았다(운영 서버에서 CDS 파일 만들기는 2026-11-13 시험에서 서버가 멈춰 실패, 아래). 운영 실측 시작 시간은 108~117초(2026-11-12 재시작 3회, 로그 "Started … in")다.

배경: 오늘 운영 강제 종료 실험 세 번에서 복구 시간의 대부분이 앱 시작(108~113초)이었다. 언어 교체(Rust)는 백엔드 전체를 다시 써야 해서 제외하고
(작은 부품 비교는 이미 Python 유지 결론, [rust-parts-pilot-local-20261104.md](rust-parts-pilot-local-20261104.md)), 코드를 그대로 두는 JVM 쪽 방법을 쟀다.

## 환경 (로컬 — 운영 아님)
- Mac(arm64) Docker `eclipse-temurin:21-jre`(운영과 같은 Java 21), `--memory 956m`, CPU `--cpus 0.25`(운영 흉내)와 `--cpus 2`.
- JVM 옵션은 운영과 같게: `-Xmx144m -Xss256k -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC`.
- 개발 DB(운영과 다른 Supabase 프로젝트, 접속 계정으로 확인), `APP_QUEUE_TYPE=async`, 다시 보내기 끔. Gemini·카카오 호출 없음.
- 잰 값: 로그 "Started … in N seconds", 컨테이너 시작부터 `/actuator/health` UP까지(배포 스크립트가 기다리는 값), UP 20초 뒤 `VmRSS`.
- 스크립트·원자료: [results/startup-cds-aot-20261112/](results/startup-cds-aot-20261112/)

| 방식 | 무엇을 하나 |
|---|---|
| 기본 | 지금 운영과 같음: `java -jar app.jar`(안에 든 JAR를 그대로 읽음) |
| AppCDS | JAR를 풀어 두고(`-Djarmode=tools extract`), 한 번 띄워 읽은 클래스를 파일로 남긴 뒤(`-XX:ArchiveClassesAtExit`, `-Dspring.context.exit=onRefresh`) 다음부터 그 파일을 씀 |
| AOT | 빌드 때 스프링 빈 구성을 미리 만들어 둠(`processAot`, 실행 `-Dspring.aot.enabled=true`). 측정용으로만 `org.graalvm.buildtools.native` 플러그인을 붙였고 커밋하지 않음 |
| AOT+AppCDS | 둘 다 |

## 결과 (각 3회 중앙값)

| 방식 | CPU 0.25: Started | CPU 0.25: UP | RSS | CPU 2: UP | RSS |
|---|---:|---:|---:|---:|---:|
| 기본 | 120.9초 | 137.6초 | 466MiB | 5.0초 | 471MiB |
| AppCDS | 72.3초 | **79.3초(−42%)** | 460MiB | 3.3초 | 460MiB |
| AOT | 94.0초 | 109.7초(−20%) | 441MiB | 4.3초 | 447MiB |
| AOT+AppCDS | 55.3초 | **62.5초(−55%)** | **424MiB(−42MiB)** | 2.5초 | 425MiB |

- 회차별 값은 흔들림이 작았다(CPU 0.25 기본 UP 134.0~140.8초, AOT+CDS 61.3~63.0초).
- CPU 0.25의 기본 Started 120.9초는 운영 108~117초와 비슷해 운영을 흉내 내는 데 쓸 만했다. 그래도 운영 수치는 운영에서 다시 재야 한다.
- RSS에는 CDS 파일을 메모리에 올린 부분도 들어간다(여러 프로세스가 나눠 쓸 수 있는 메모리지만 이 서버는 java가 하나).

## 운영에 넣을 때 따져야 할 것
- **AppCDS만:** 코드·설정 그대로. 대신 배포 때 JAR를 풀고 CDS 파일을 만들어야 한다. CDS 파일은 같은 Java 빌드·같은 CPU 종류에서 만든 것만 쓰이므로(맥 arm64 ≠ 운영 x86) 운영 서버에서 만든다.
  방법은 둘: 배포 때 학습 실행 한 번(운영 CPU로 약 1~2분 더, DB에 접속은 하지만 컨텍스트만 띄우고 끝냄) 또는 JDK 19+의 `-XX:+AutoCreateSharedArchive`(정상 종료 때 자동으로 만들고 다음 시작부터 씀 — SIGKILL 뒤에는 안 만들어짐).
  파일이 맞지 않으면 JVM은 경고만 내고 CDS 없이 뜬다(지금과 같아짐).
  **단, 운영 서버(956MB)에서 학습 실행을 돌리면 서버가 멈춘다 — 아래 "운영 시험(실패)". 운영에서 만들지 않는다.**
- **AOT:** 설정에 따라 빈을 고르는 조건이 빌드 때 고정된다. 이 레포에는 `app.queue.type`(대기열 11곳), `app.metrics.otlp.enabled`, `app.queue.requeue.enabled`가 있다.
  운영 JAR를 운영 값(kafka·지표 전송 켬)으로 따로 빌드해야 하고, "운영 `.env`에서 `APP_QUEUE_TYPE=async`로 Kafka를 잠시 빼기"(Runbook `kafka-queue.md`) 같은 운영 중 전환을 쓸 수 없게 된다.
  빌드 플러그인도 하나 늘어난다. AOT가 더해 주는 몫은 CDS 위에서 약 17초(79.3 → 62.5초)다.
- 어느 쪽이든 재시작·배포 때 사용자가 앱을 못 쓰는 시간이 줄고, 강제 종료 뒤 Kafka가 작업을 다시 주는 시간도 같이 준다.

## 운영 시험 (2026-11-13, 실패 — 운영 서버)
- 한 것: 진행 중 작업 0건 확인 → 00:56:28 앱 멈춤 → JAR 풀기(9.3초) → 학습 실행 `java -Xmx144m … -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh`(`systemd-run`, `MemorySwapMax=0`).
- 결과: 학습 실행이 끝나지 않았다. 서버가 SSH·HTTPS 모두 응답하지 않았고(스크립트는 15분씩 두 번 시간 초과로 끝나 복구 단계를 못 돌림),
  08:27 커널 OOM이 학습 java를 끝냈다 — `Killed process … (java) anon-rss:734856kB`. 힙은 144MB로 묶었지만 CDS 파일을 쓰는 단계는 힙 밖 메모리를 써서 실제 735MB까지 올라갔다.
  `MemorySwapMax=0`은 그 단위의 스왑만 막았고 다른 프로세스가 스왑으로 밀려 서버 전체가 7시간 반 멈췄다.
- 영향: 앱이 00:56~09:54(약 9시간) 멈춤. 서버 재부팅 없음. 앱은 `systemctl stop` 뒤라 서버가 살아나도 자동으로 뜨지 않았고, 09:51 수동 시작 → 120초 뒤 UP. 설정은 원래대로, 시험 폴더 삭제.
- 결론: 운영 VM에서 CDS 파일을 만들지 않는다. 쓰려면 같은 JDK의 다른 x86 기기에서 만들어 옮기는 방법만 남는다(효과는 운영에서 재지 못함).
  운영에서 앱을 멈추는 측정은 메모리 상한(`MemoryMax`)과 서버 쪽 자동 복구(타이머로 앱 시작)를 같이 둔다.

## 다시 재기
```
DEV_ENV_FILE=<개발 DB 환경 파일> docs/bench/results/startup-cds-aot-20261112/measure.sh 0.25 3
```
(같은 폴더에 `base.jar`·`aot.jar`를 두고 실행. 스크립트가 JAR를 풀고 CDS 파일을 만든다.)
