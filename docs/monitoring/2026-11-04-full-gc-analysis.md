# 운영 Full GC 긴 멈춤 분석 (2026-11-04~, 운영)

계기: 2026-11-04 18:10 운영 약한 부하 측정 중 Full GC 4.97초 → 요청 1건 3초 시간 초과(`2026-11-04-prod-index.md`).

## 1. 지금까지 확인한 사실 (운영, 읽기만, 2026-11-04 23시)

### Full GC 이력 (로컬 Prometheus, 10-20 이후 보존, 수집 공백 있음)
`jvm_gc_pause_seconds_max{action="end of major GC"}`의 값 변화로 본 Full GC:

| 원인(cause) | 발생 시각(KST)과 멈춤 시간 |
|---|---|
| **CodeCache GC Threshold** | 10-30 18:26 **5.99초**, 10-31 07:11 **5.23초**, 11-01 23:41 **9.78초**, 11-04 18:11 **4.97초** |
| Allocation Failure | 10-31 01:56 0.96초, 10-31 14:56 0.34, 15:11 0.47, 17:26 0.43, 11-01 02:11 0.48, 02:56 0.49, 11-02 11:41 0.86, 11-04 22:11 0.35 |

→ **긴 멈춤(5~10초)은 모두 “CodeCache GC Threshold”가 일으킨 Full GC**다. 힙이 차서 생긴 Full GC(Allocation Failure)는 1초 미만이었다.
(수집이 끊긴 시간대의 GC는 빠져 있을 수 있다. 같은 시간대 안에 여러 번이면 최대값만 보인다.)

### JVM 설정 (`jcmd VM.flags`)
- OpenJDK 21.0.12 (Temurin), `-XX:+UseSerialGC`(직접 지정 안 함 — 메모리가 작은 서버라 JVM이 자동 선택),
  힙 최대 384MiB, `ReservedCodeCacheSize` 240MiB, `SegmentedCodeCache`.
- 코드 캐시 사용: non-profiled 10.6MiB, profiled 27.4MiB (재시작 2시간 후).

### 서버 CPU·메모리
- `/proc/stat` 누적(가동 35일): user 1,314,096 / system 569,378 / **steal 2,132,288** jiffies.
  steal은 하이퍼바이저가 이 VM에 CPU를 주지 않은 시간이다. **CPU를 쓰려던 시간의 약 절반이 steal** → E2.1.Micro(1/8 OCPU 버스트형)의 CPU 제한.
- 스왑 약 330MiB 사용 중, 여유 메모리 약 93MiB(free), java RSS 약 420MiB.

## 2. 용어
- **CodeCache**: JVM이 자주 쓰는 Java 코드를 기계어로 번역(JIT)해 저장하는 공간.
- **CodeCache GC Threshold**: 코드 캐시가 일정량 늘면 안 쓰는 번역본을 치우려고 JVM이 GC를 일으키는 것.
  Serial GC에서는 이것이 **힙 전체 대청소(Full GC, 전체 정지)**가 된다. G1 GC에서는 대부분 앱과 동시에 진행하는 청소로 처리된다.
- **steal**: 가상 서버가 CPU를 원했지만 클라우드가 다른 손님에게 줘서 기다린 시간.

## 3. 가설 (미확정)
1. **CPU 제한**: Full GC 작업량은 작은데(힙 100MiB 이하) CPU를 제대로 못 받아(steal) 수 초가 걸린다.
2. **스왑**: 오래 안 쓴 힙 페이지가 스왑으로 밀려 있다가 Full GC가 훑을 때 디스크에서 다시 읽어 느려진다.
3. Serial GC라서 CodeCache 정리가 전체 정지 Full GC로 처리된다(이건 JVM 동작상 사실, 영향 크기는 측정 필요).

구분 방법: GC 로그의 `User/Sys/Real` 시간. Real ≫ User+Sys면 CPU 대기(steal)나 스왑 대기.

## 4. 진행 방식 (사용자 선택 3번: 운영 GC 로그 + 복제 환경 비교 동시)

### 4-1. 운영 GC 로그 (재시작 없이 `jcmd VM.log`)
`/opt/trova-backend/gc-logs/gc.log`(10MB×5 순환)에 `gc*`, `gc+cpu`, `safepoint`. 다음 CodeCache Full GC의 User/Sys/Real로
CPU 대기(steal)인지 스왑인지 확정한다. 자동 권한 분류기가 차단해 사용자 요청 대기(issue-log #9).

### 4-2. 복제 환경 Serial vs G1 (`monitoring/gc-compare/`)
- 앱: 같은 JAR, `eclipse-temurin:21-jre`, 힙 384MiB, `-Xss256k`, 컨테이너 메모리 768MiB,
  CPU 제한 `--cpus 0.25`(및 0.125), `-XX:ActiveProcessorCount=2`(운영 JVM처럼 CPU 2개로 인식).
- 차이는 GC 옵션 하나: `-XX:+UseSerialGC` vs `-XX:+UseG1GC`.
- CodeCache GC를 자주 일으키려고 두 쪽 모두 `-XX:SweeperThreshold=1`(JDK 기본 15% → 1%). 운영에서는 15%(약 36MiB 증가마다).
- 데이터: 사용자 20명 × 장소 40개(운영 규모와 비슷). 부하: 5 VU, 요청 뒤 2초, 앱 조회 API 11종을 섞어 JIT 코드를 다양하게.
- 순서: Serial → G1 → G1 → Serial, 각 6분, 매번 새 JVM. GC 로그에서 멈춤 원인·시간·User/Sys/Real, safepoint 합계, 컨테이너 메모리 최대치.
- 한계: DB 왕복 지연(운영 31.5ms)은 넣지 않았다(GC 비교가 목적). 클라우드 steal은 cgroup CPU 제한으로 근사.

시험 실행(1분, cpus 0.25, 로컬): Serial CodeCache Full GC 17회 최대 713ms 합 6.2초 / G1 CodeCache 14회(Concurrent Start, Full 0) 최대 104ms 합 0.6초,
메모리 최대 485 → 529MiB. 본 측정 결과는 아래에 추가.

## 5. 복제 환경 결과 — CPU 0.25, SweeperThreshold 1%, 각 6분 (로컬 Docker, 2026-11-05 00:11~00:42)

| 실행 | CodeCache GC | 그중 Full GC | 가장 긴 멈춤 | 멈춤 합계 | k6 중앙/p95/p99 | 컨테이너 메모리 최대 |
|---|---|---|---|---|---|---|
| 1 Serial | 23회, 최대 709ms | 23 | 709ms (Full) | 11.3초 | 8.0 / 24.8 / 520ms | 502MiB |
| 2 G1 | 17회, 최대 103ms | 0 | 480ms (Remark) | 6.1초 | 8.8 / 53.8 / 343ms | 543MiB |
| 3 G1 | 18회, 최대 186ms | 0 | 284ms (Remark) | 5.7초 | 8.6 / 48.1 / 408ms | 544MiB |
| 4 Serial | 13회, 최대 708ms | 13 | 708ms (Full) | 7.9초 | 9.2 / 19.2 / 191ms | 512MiB |

요청 각 약 890건, 오류 0. k6 최대값(1.7~2.6초)은 두 방식 모두 시작 직후 JIT 예열 구간이라 비교에서 뺀다.

해석
- **G1은 CodeCache 정리를 Full GC 없이 처리**했다(Serial 36회 전부 Full → G1 35회 전부 짧은 Young 멈춤). 가장 긴 멈춤 709ms → 284~480ms, 멈춤 합계 약 40% 감소.
- 대신 G1은 **p95가 약 2배**(19~25ms → 48~54ms)다. 쓰기 장벽·동시 작업 스레드가 0.25 CPU를 나눠 쓰는 비용으로 추정.
- **메모리 +35~40MiB**. 운영 서버는 여유 약 93MiB·스왑 330MiB 사용 중이라 부담이 될 수 있다.
- Serial Full GC의 `User 0.18s / Real 0.71s` ≈ CPU 0.25개 제한에 맞는 4배. → 운영의 5~10초도 **CPU를 못 받아 늘어난 것**이라는 가설과 맞는다(운영 GC 로그로 확인 예정).

## 6. 복제 환경 결과 — 운영 근접 조건: CPU 0.125, 메모리 520MiB + 스왑 허용 (로컬 Docker, 2026-11-05 10:18~11:01)

| 실행 | CodeCache GC | 그중 Full | 가장 긴 멈춤 | 멈춤 합계(6분) | k6 중앙/p95/p99 | 메모리 최대 / anon 끝 / 스왑 최대 |
|---|---|---|---|---|---|---|
| 1 Serial | 22회 | 22 | **2,499ms** (Full, User 0.31s / Real 2.5s) | 32.0초 | 10.2 / 644.5 / 2,699.9ms | 509 / 434 / 0MiB |
| 2 G1 | 21회, 최대 207ms | 0 | 901ms (Remark) | 16.2초 | 10.2 / 403.1 / 1,338.3ms | 520(한도) / 470 / 30MiB |
| 3 G1 | 20회, 최대 200ms | 0 | 700ms (Remark) | 15.1초 | 9.8 / 456.5 / 1,169.5ms | 520(한도) / 488 / 0MiB |
| 4 Serial | 20회 | 21 | **1,908ms** (Full, User 0.23s / Real 1.91s) | 30.5초 | 10.5 / 498.9 / 2,159.8ms | 512 / 442 / 0MiB |

요청 각 약 840건, 오류 0, OOM 0. k6 최대값(5.6~10.3초)은 시작 직후 예열 구간이 포함된 값이다.

해석
- CPU가 더 부족하면 **Serial Full GC가 2~2.5초**로 늘었다(CPU 시간 0.23~0.31초 → 실제 8배). 0.25 CPU의 4배와 같은 비례.
- 이 조건에서는 **G1이 모든 지연 지표에서 낫다**: 가장 긴 멈춤 1.9~2.5초 → 0.7~0.9초, 멈춤 합계 절반, p95 499~645 → 403~457ms, p99 2.2~2.7초 → 1.2~1.3초.
  (CPU 0.25에서 보였던 G1의 p95 손해는 CPU가 더 부족해 Serial 멈춤이 커지자 역전됐다.)
- G1에서 CodeCache 정리 비용은 Remark 멈춤(최대 0.7~0.9초)으로 옮겨 간다. 사라지는 것이 아니라 짧아진다.
- G1은 **메모리 한도(520MiB)까지 차고 한 번은 스왑 30MiB**를 썼다. OOM은 없었다. 운영 메모리 여유가 작다는 위험은 그대로다.

## 7. 운영 GC 로그로 확인한 실제 멈춤 (운영, 2026-11-05 08:16)

```
GC(285) Pause Full (CodeCache GC Threshold) 64M->50M(130M) 8751.062ms
GC(285) User=0.16s Sys=0.97s Real=8.75s
```
(원본 발췌 `docs/monitoring/2026-11-05-gc-compare/prod-gc-excerpt-20261105-0816.log`)

- 힙 64MiB를 치우는 데 **8.75초**. CPU를 실제 쓴 시간은 1.13초(User 0.16 + Sys 0.97)뿐 → **7.6초는 기다린 시간**.
- **Sys 0.97초**가 User보다 6배 크다. 커널 작업(스왑에서 메모리 페이지를 다시 읽는 일 등)이 많았다는 신호 → 가설 1(CPU steal)과 2(스왑)가 **둘 다** 작용한 것으로 본다.
  (같은 시각 스왑 입출력 수치는 수집하지 않아 비율은 미확정.)
- 운영 재시작(11-04 22:04) 후 약 10시간 만에 발생. 이 시각 사용자 요청은 거의 없었다.

## 8. 운영 적용 — G1 GC (운영, 2026-11-05 11:22, 사용자 선택 1번)

- 사전 확인: 진행 중 분석 작업 0건. Serial 시절 GC 로그는 서버 `/opt/trova-backend/gc-logs/serial-20261105/`에 보관.
- 변경: systemd drop-in `/etc/systemd/system/trova-backend.service.d/gc.conf`(원래 서비스 파일은 그대로)
  `ExecStart=/usr/bin/java -Xmx384m -Xss256k -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC -Xlog:gc*=info,gc+cpu=info,safepoint=info:file=/opt/trova-backend/gc-logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10m -jar /opt/trova-backend/app.jar`
  → 재시작·배포 후에도 GC 로그가 계속 남는다(최대 50MB). 배포 스크립트는 app.jar만 바꾸므로 drop-in은 유지된다.
- 결과: 재시작 11:22:44 → UP 110초(11:24:50). `jcmd VM.flags`에 `-XX:+UseG1GC`, 로그 첫 줄 `Using G1`. 힙 최대 384MiB 그대로.
  서비스 로그 오류 0, 인증 없는 요청 401 정상.
- 되돌리기: `sudo rm /etc/systemd/system/trova-backend.service.d/gc.conf && sudo systemctl daemon-reload && sudo systemctl restart trova-backend`

### 관찰 기록
| 시각(KST) | 경과 | GC 멈춤 수 / 최대 | Full GC | CodeCache GC | java RSS | free | 스왑 사용 |
|---|---|---|---|---|---|---|---|
| (Serial, 11-04 23시) | 재시작 후 약 2시간 | — | 긴 멈춤 이력 4.97~9.78초 | Full | 420MiB | 93MiB | 330MiB |
| 11-05 11:25 | 2분 | — | 0 | 0 | 410MiB | 77MiB | 241MiB |
| 11-05 11:28 | 5분 | 76회 / 188ms(Young) | 0 | 0 | 408MiB | 79MiB | 245MiB |
| 11-05 12:42 | 1시간 20분 | 87회 / 395ms(Remark) | 0 | 0 | 439MiB | — | 프로세스 20MiB, si/so 0 |
| 11-05 20:24 | 9시간 | 134회 / **4,139ms(Remark)** | 0 | 0 | 375MiB | 70MiB | 시스템 543MiB, 프로세스 87MiB, si/so 0(측정 순간) |
| 11-06 13:59 | 26시간 37분 | 253회 / **5,511ms(Remark, 01:37)** | 0 | 0 | 약 405MiB | 가용 216MiB | 프로세스 55MiB |

CodeCache 정리는 재시작 후 몇 시간~하루 뒤에 일어났다(Serial 이력). 그때 Full GC 없이 처리되는지, 멈춤 최대가 얼마인지가 핵심 확인 항목이다.

## 9. 관찰 중 발견: G1에서도 1초 넘는 멈춤 2회 (운영, 2026-11-05 20:24 확인)

G1 최종 확인 기준(Full GC 0, **최대 멈춤 1초 미만**, RSS 계속 증가 없음, 스왑 입출력 지속 없음) 중 **최대 멈춤 기준을 이미 넘었다.**

| 시각(KST) | 멈춤 | 힙 | User / Sys / Real |
|---|---|---|---|
| 11:54 | Remark 395ms | 61M | — |
| 13:16 | Remark 495ms | 58M | — |
| 16:01 | **Young(Prepare Mixed) 1,647ms** | 125M→56M | 0.00s / 0.28s / 1.65s |
| 16:18 | Young(Mixed) 657ms, Remark 588ms | 125M→55M | — |
| 17:26 | **Remark 4,139ms** | 56M | 0.00s / 0.63s / 4.14s |

확인한 사실
- Full GC 0회, CodeCache GC Threshold 0회 → Serial 때의 원인(CodeCache Full GC)은 사라졌다.
- 긴 멈춤 두 번 모두 **User 0초, Real의 대부분이 대기**다. Serial 8.75초(User 0.16 / Sys 0.97)와 같은 모양이다.
- 그 시각 운영 분석 작업은 없었다(운영 DB 읽기 전용 조회, 11-05 작업 1건뿐). 사용자 요청이 거의 없는 시간대의 멈춤이다.
- 서버 전체 스왑 사용 543MiB(11-04 330MiB), java 프로세스 스왑 20MiB(12:42) → 87MiB(20:24). 스왑 성향 `vm.swappiness=60`(기본값).
- 스왑을 많이 쓰는 다른 프로세스: `fwupd`(펌웨어 업데이트 데몬) 130MiB. 메모리 상위: java 374, systemd-journal 58, multipathd 27, snapd 25MiB.
- CPU steal: 11-04 23시 → 11-05 20시 사이 steal 증가 70,572 jiffies ≈ 앱이 실제 쓴 CPU(user+system 70,158)와 같다. CPU를 원한 시간의 절반은 여전히 못 받는다.

원인(추정)
- 한가한 동안 java 힙 페이지가 스왑으로 밀려나고, GC가 그 페이지를 다시 만질 때 디스크에서 읽느라(Sys 시간·대기) 수 초가 걸린다. 여기에 CPU steal이 겹친다.
- 즉 **긴 멈춤의 근본 원인은 GC 방식이 아니라 메모리 부족(스왑)과 CPU 제한**이다. G1은 CodeCache Full GC를 없앴지만, 스왑된 힙을 만지는 멈춤은 G1에서도 생긴다.
- 확정하려면 멈춤 시각의 스왑 입력(si) 수치가 필요하다. 지금은 측정 순간 값만 있다.

해결안 (사용자 선택 대기)
1. 스왑으로 밀리는 것 줄이기: `vm.swappiness`를 낮추고(예: 10) 안 쓰는 데몬(`fwupd`, 필요 없으면 `snapd`·`multipathd`)을 끈다. 되돌리기 쉽고 java가 RAM에 남을 가능성이 커진다. 대신 여유 메모리가 작아 다른 프로세스가 압박받을 수 있다.
2. 힙 줄이기: `-Xmx384m` → 256m 등. GC가 만지는 범위와 RSS가 줄어 스왑 압박이 낮아진다. 분석 작업 때 힙 부족(OOM) 위험을 같은 부하로 먼저 재야 한다.
3. 원인 확정부터: `vmstat`·`/proc/vmstat`의 스왑 입력을 1분 간격으로 하루 기록해 다음 긴 멈춤 시각과 맞춰 본다. 바꾸는 것은 그 뒤에.
4. 그대로 두기: 멈춤은 한가한 시간대에 드물게 생겨 사용자 영향이 작다. G1 유지(Serial보다 CodeCache Full GC가 없어 나음)하고 #333 배포 진행.

## 10. 스왑 기록으로 확인한 원인 (운영, 2026-11-05 22:32 ~ 11-06 13:59)

9절 해결안 3(사용자 선택)으로 운영 서버에 1분마다 `/proc/vmstat`(스왑 입출력·주요 페이지 폴트), `/proc/stat`(CPU·steal),
가용 메모리, java RSS·스왑 사용을 한 줄씩 남겼다(`monitoring/gc-compare/swapwatch.sh`, cron, 사용자 명시 요청 후 설치).
원본: `docs/monitoring/2026-11-06-g1-swap/swapwatch.log`, 같은 기간 GC 로그 `prod-g1-gc.log`.

### G1 최종 확인 기준 판정 (전환 뒤 26시간 37분)
| 기준 | 결과 | 판정 |
|---|---|---|
| Full GC 0 | 0회(CodeCache GC Threshold 0회) | 통과 |
| 최대 멈춤 1초 미만 | 1초 넘는 멈춤 **4회**: 1.65초·4.14초·**5.51초**·3.59초 | **실패** |
| RSS 계속 증가 없음 | 225 → 405MiB. 스왑에서 돌아온 만큼 늘어난 것(아래) | 판단 보류 |
| 스왑 입출력 지속 없음 | 평소 1분 스왑 입력 중앙값 1페이지. 대신 몰아서 생김 | 조건부 |

### 긴 멈춤 = 스왑에서 힙을 다시 읽는 시간
| 멈춤 | 그 1분 동안 스왑 입력 | java 스왑 사용 | 주요 페이지 폴트 |
|---|---|---|---|
| 11-05 22:59 Remark 0.77초 | 22:58~23:00 51,524페이지(약 201MiB) | 258 → 198MiB | 20,002 |
| 11-06 01:37 Remark **5.51초** | 32,705페이지(약 128MiB) | **180 → 60MiB** | 15,043 |
| 11-06 07:00 Remark **3.59초** | 20,927페이지(약 82MiB) | **124 → 49MiB** | 11,646 |

- 기록 기간 전체에서 1분 스왑 입력 중앙값은 1페이지, 상위 1%가 1,900페이지다. 긴 멈춤이 있던 분에만 2만~3만 페이지가 한꺼번에 들어왔다.
- 멈춤 직전 몇 분은 스왑 입력이 0이었다. 멈춤과 함께 java 스왑 사용이 100MiB 안팎 줄고 RSS가 그만큼 늘었다.
  → **G1이 힙 전체를 훑는 단계(Remark)에서 스왑으로 밀려난 힙 페이지를 디스크에서 다시 읽느라 멈췄다.** GC 로그의 User 0초 / Real 수 초와 맞는다.
- 사이 시간에는 스왑 출력(최대 1분 9,017페이지)으로 java 페이지가 다시 밀려났다(java 스왑 60 → 121MiB, 01:38 → 02:50). 밀려나고 → 멈추며 다시 읽기가 반복된다.
- CPU steal은 1분 단위 중앙값 기준 CPU를 원한 시간의 37%. 멈춤이 있던 분에는 steal도 커졌지만(413 jiffies), 스왑 입력만큼 뚜렷한 차이는 아니다. 주원인은 스왑, steal은 늘리는 요인으로 본다.
- 07:01 이후 13:59까지는 0.5초 넘는 멈춤이 없었다(java 스왑 42~55MiB로 낮게 유지).

### 결론
- Serial의 CodeCache Full GC(5~10초)와 G1의 Remark(3.6~5.5초)는 **같은 원인**이다. 서버 메모리가 모자라 한가한 동안 java 힙이 스왑으로 밀려나고, GC가 그 힙을 만질 때 다시 읽는다.
- G1은 Full GC와 CodeCache 문제를 없앴지만 이 원인은 그대로다. **고칠 곳은 GC가 아니라 메모리 압박**이다.

### 해결안 (사용자 선택 대기)
1. **(권장) 커널이 java를 덜 밀어내게 하기**: `vm.swappiness` 60 → 10, 안 쓰는 데몬(`fwupd` 스왑 130MiB 등) 끄기. 재시작 없이 바로 적용되고 되돌리기 쉽다. 기록기를 계속 돌려 전후를 비교한다. 운영 서버 설정 변경이라 명시적 요청 필요.
2. java 쪽 메모리 줄이기: 힙은 실제 134MiB만 잡혀 있고 RSS 400MiB 대부분이 힙 밖(코드 캐시·메타스페이스·스레드)이다. 힙 최대를 줄여도 효과가 작다. 힙 밖 메모리를 줄이려면 측정이 더 필요하다.
3. 서버 키우기: 메모리가 큰 무료 서버(Oracle A1)는 아직 확보하지 못했다.
4. 그대로 두기: 새벽·한가한 시간대에 하루 몇 번. 사용자 요청이 거의 없는 시간이라 영향이 작다.

**사용자 선택**: 1번(“1”).

## 11. 운영 적용 — 스왑 성향 낮추기와 fwupd 끄기 (운영, 2026-11-06 14:21)

- 변경 전: `vm.swappiness=60`, `fwupd`·`fwupd-refresh.timer` 실행 중. 스왑 사용 543MiB(11-05 20:24 기준), 가용 216MiB.
- 변경
  - `/etc/sysctl.d/90-trova-swappiness.conf`에 `vm.swappiness = 10` → `sysctl -p`로 즉시 적용(재부팅 뒤에도 유지).
  - `systemctl stop` + `mask`: `fwupd`, `fwupd-refresh.timer`(펌웨어 업데이트 데몬, 클라우드 VM에서 쓰지 않음, 스왑 130MiB 사용).
- 직후: swappiness 10, fwupd inactive·masked, 시스템 스왑 사용 **418MiB**, 가용 230MiB. 앱은 재시작하지 않았다(trova-backend active).
- 되돌리기: `sudo rm /etc/sysctl.d/90-trova-swappiness.conf && sudo sysctl vm.swappiness=60`, `sudo systemctl unmask fwupd fwupd-refresh.timer && sudo systemctl start fwupd-refresh.timer`
- 확인할 것: 같은 기록기(`swapwatch.log`)로 적용 뒤 java 스왑 사용이 다시 늘어나는지, 1초 넘는 GC 멈춤과 몰아서 생기는 스왑 입력이 사라지는지.
  swappiness를 낮춰도 메모리가 정말 모자라면 커널은 여전히 스왑을 쓴다. 그때는 2번(java 힙 밖 메모리)이나 3번(서버)으로 간다.

## 12. 적용 27분 뒤 다시 2.78초 멈춤 — 스왑 성향만으로는 부족 (운영, 2026-11-06 14:48)

```
GC(216) Pause Remark 53M->53M(134M) 2778.553ms
GC(216) User=0.10s Sys=0.39s Real=2.78s
```

| 분(KST) | 스왑 입력 | 스왑 출력 | java 스왑 | java RSS |
|---|---|---|---|---|
| 14:41 | 79 | 577 | 55MiB | 407MiB |
| 14:42 | 2,676 | 6,622 | 82MiB | 377MiB |
| 14:47 | 589 | 7,533 | 129MiB | 333MiB |
| 14:48 | 1,467 | 3,425 | 144MiB | 319MiB |
| 14:49 | **22,108** | 1,227 | **63MiB** | 399MiB |

- `swappiness=10`인데도 14:41~14:48 사이 java가 55 → 144MiB 밀려났고, 14:48 Remark가 그것을 다시 읽으며(약 86MiB) 2.78초 멈췄다. 9·10절과 같은 모양이다.
- 그 사이 트로바 분석 작업은 0건(운영 DB 읽기 전용 확인). 시스템 로그에는 cron(이 기록기, 다른 프로젝트 수집기 `safekor-alert-collector` 2분마다)과 원인 조사용 SSH 세션만 있다.
  스왑 출력이 큰 분(14:39·14:42·14:47)이 그 수집기가 도는 짝수 분 직후와 겹치지만, 수집기는 하루 종일 2분마다 돌고 큰 스왑 출력은 가끔이라 단정할 수 없다.
- trova-backend 서비스 cgroup: 메모리 430MiB, 스왑 321MiB(파이썬 등 같은 서비스의 다른 프로세스 포함). cgroup v2라 서비스 단위 스왑 제한(`memory.swap.max`)을 쓸 수 있다.

결론: 스왑 성향을 낮춰도 메모리가 모자라는 순간 커널은 java를 밀어낸다. **java가 스왑되지 않게 막거나, 메모리를 비워 주거나, 스왑을 빠르게 하는 것**이 필요하다.

해결안 (사용자 선택 대기)
1. java 서비스 스왑 금지: systemd drop-in `MemorySwapMax=0`. java 페이지는 스왑되지 않아 이 멈춤이 구조적으로 사라진다. 대신 다른 프로세스가 더 스왑되고, 정말 모자라면 OOM으로 프로세스가 죽을 수 있다. 서비스 재시작이 필요해 #333·#335 배포와 함께 하면 재시작 1번.
2. 메모리 비우기: 같은 서버의 다른 프로젝트 수집기(`safekor-alert-collector`, 2분마다 python)를 다른 곳으로 옮기거나 주기를 늘린다. 그 수집기의 최대 메모리를 먼저 잰다.
3. 압축 메모리 스왑(zram): 디스크 대신 RAM 안에 압축해 두는 스왑을 우선 쓰게 한다. 스왑에서 읽는 시간이 디스크보다 훨씬 짧다. 패키지 설치가 필요하고 압축에 CPU를 쓴다(1/8 OCPU).
4. 스왑 성향 원래대로(60) 되돌리고 그대로 두기.

