"""분석 작업 프로세스의 CPU·메모리 사용량(#311). 결과 JSON의 resources로 서버에 넘겨 로그·지표로 남긴다.

Rust 같은 다른 구현과 비교하려면 먼저 지금 Python이 실제로 CPU와 메모리를 얼마나 쓰는지 알아야 한다(#309에서 미측정).
- CPU 시간: 서버가 직접 계산한 시간. Gemini 응답을 기다리는 시간은 들어가지 않는다.
- 자식 프로세스(yt-dlp·ffmpeg)는 끝나고 회수된 것만 집계된다(subprocess.run은 끝날 때까지 기다리므로 모두 포함).
- maxRssKib: 이 Python 프로세스의 최대 메모리. childMaxRssKib: 자식 중 **가장 컸던 하나**의 최대 메모리(합계가 아님).
"""
from __future__ import annotations

import resource
import sys
import time

# 모듈을 처음 읽은 시각. 인터프리터 시작 전 시간은 빠지므로 Java가 잰 프로세스 전체 시간과의 차이가 시작·종료 비용이다.
WORKER_STARTED_AT = time.perf_counter()


def _ms(seconds: float) -> int:
    return round(seconds * 1000)


def _kib(max_rss: int) -> int:
    # ru_maxrss 단위가 리눅스는 KiB, macOS는 바이트다.
    return max_rss // 1024 if sys.platform == "darwin" else max_rss


def child_cpu_seconds() -> float:
    usage = resource.getrusage(resource.RUSAGE_CHILDREN)
    return usage.ru_utime + usage.ru_stime


def snapshot(clock=time.perf_counter, started_at: float | None = None) -> dict[str, int]:
    own = resource.getrusage(resource.RUSAGE_SELF)
    children = resource.getrusage(resource.RUSAGE_CHILDREN)
    return {
        "workerWallMs": _ms(clock() - (WORKER_STARTED_AT if started_at is None else started_at)),
        "cpuUserMs": _ms(own.ru_utime),
        "cpuSystemMs": _ms(own.ru_stime),
        "childCpuUserMs": _ms(children.ru_utime),
        "childCpuSystemMs": _ms(children.ru_stime),
        "maxRssKib": _kib(own.ru_maxrss),
        "childMaxRssKib": _kib(children.ru_maxrss),
    }
