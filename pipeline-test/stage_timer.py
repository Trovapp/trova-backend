"""분석 단계별 걸린 시간(#194). 결과 JSON의 timings(밀리초)로 서버에 넘겨 로그·지표로 남긴다.
단계별 CPU 시간(#311)도 함께 잰다 — 이 프로세스(cpu)와 그 단계에서 끝난 자식 프로세스(yt-dlp·ffmpeg, childCpu)."""
import time
from contextlib import contextmanager

from worker_resources import child_cpu_seconds


class StageTimer:
    def __init__(self, clock=time.perf_counter, cpu_clock=time.process_time, child_cpu_clock=child_cpu_seconds):
        self._clock = clock
        self._cpu_clock = cpu_clock
        self._child_cpu_clock = child_cpu_clock
        self._millis: dict[str, int] = {}
        self._cpu_millis: dict[str, int] = {}

    @contextmanager
    def stage(self, name: str):
        """같은 이름을 다시 재면 더한다. 오류가 나도 그때까지의 시간은 남긴다."""
        start = self._clock()
        cpu_start = self._cpu_clock()
        child_start = self._child_cpu_clock()
        try:
            yield
        finally:
            elapsed = round((self._clock() - start) * 1000)
            self._millis[name] = self._millis.get(name, 0) + elapsed
            self._add(name + "CpuMs", self._cpu_clock() - cpu_start)
            self._add(name + "ChildCpuMs", self._child_cpu_clock() - child_start)

    def _add(self, key: str, seconds: float) -> None:
        self._cpu_millis[key] = self._cpu_millis.get(key, 0) + round(seconds * 1000)

    def as_dict(self) -> dict[str, int]:
        return dict(self._millis)

    def cpu_dict(self) -> dict[str, int]:
        """예: {"geminiCpuMs": 120, "geminiChildCpuMs": 0, "framesCpuMs": 3, "framesChildCpuMs": 850}"""
        return dict(self._cpu_millis)
