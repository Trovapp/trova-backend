"""분석 단계별 걸린 시간(#194). 결과 JSON의 timings(밀리초)로 서버에 넘겨 로그·지표로 남긴다."""
import time
from contextlib import contextmanager


class StageTimer:
    def __init__(self, clock=time.perf_counter):
        self._clock = clock
        self._millis: dict[str, int] = {}

    @contextmanager
    def stage(self, name: str):
        """같은 이름을 다시 재면 더한다. 오류가 나도 그때까지의 시간은 남긴다."""
        start = self._clock()
        try:
            yield
        finally:
            elapsed = round((self._clock() - start) * 1000)
            self._millis[name] = self._millis.get(name, 0) + elapsed

    def as_dict(self) -> dict[str, int]:
        return dict(self._millis)
