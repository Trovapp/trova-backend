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
