"""분석 작업 프로세스의 CPU·메모리 사용량(#311). 결과 JSON의 resources로 서버에 넘겨 로그·지표로 남긴다.

Rust 같은 다른 구현과 비교하려면 먼저 지금 Python이 실제로 CPU와 메모리를 얼마나 쓰는지 알아야 한다(#309에서 미측정).
- CPU 시간: 서버가 직접 계산한 시간. Gemini 응답을 기다리는 시간은 들어가지 않는다.
- 자식 프로세스(yt-dlp·ffmpeg)는 끝나고 회수된 것만 집계된다(subprocess.run은 끝날 때까지 기다리므로 모두 포함).
- maxRssKib: 이 Python 프로세스의 최대 메모리. childMaxRssKib: 자식 중 **가장 컸던 하나**의 최대 메모리(합계가 아님).

리눅스의 ru_maxrss는 exec 전 부모 값이 남는다(#320). Java가 띄운 Python은 JVM 메모리(약 400MiB)가 찍혔다 —
운영에서 부모 150MiB일 때 자식 ru_maxrss 163,200KiB, 실제 VmHWM 8,908KiB. 그래서 리눅스에서는 /proc/self/status의
VmHWM(이 프로그램 자체의 최대 메모리)을 쓴다. 자식 값(childMaxRssKib)도 같은 이유로 fork 시점 Python 크기가 섞인 상한값이다.
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


def _peak_kib_from_status(path: str = "/proc/self/status") -> int | None:
    try:
        with open(path, encoding="ascii") as status:
            for line in status:
                if line.startswith("VmHWM:"):
                    return int(line.split()[1])
    except (OSError, ValueError, IndexError):
        return None
    return None


def self_peak_kib(status_path: str = "/proc/self/status") -> int:
    peak = _peak_kib_from_status(status_path)
    return peak if peak is not None else _kib(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)


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
        "maxRssKib": self_peak_kib(),
        "childMaxRssKib": _kib(children.ru_maxrss),
    }
