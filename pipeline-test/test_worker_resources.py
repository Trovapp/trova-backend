"""worker_resources·단계별 CPU·요청 준비 시간(#311). 실행: python3 -m unittest test_worker_resources (pipeline-test 폴더에서)."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import extract_places
import run_pipeline
import worker_resources
from stage_timer import StageTimer


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now


class WorkerResourcesTest(unittest.TestCase):
    def test_프로세스_전체_CPU와_최대_메모리를_정수로_남긴다(self):
        report = worker_resources.snapshot()
        for key in ("workerWallMs", "cpuUserMs", "cpuSystemMs", "childCpuUserMs", "childCpuSystemMs",
                    "maxRssKib", "childMaxRssKib"):
            self.assertIsInstance(report[key], int, key)
            self.assertGreaterEqual(report[key], 0, key)
        # 파이썬 인터프리터만으로도 수 MB는 쓴다 — 단위를 바이트로 잘못 두면 수천 MB로 보인다.
        self.assertGreater(report["maxRssKib"], 1024)
        self.assertLess(report["maxRssKib"], 4 * 1024 * 1024)

    def test_끝난_자식_프로세스의_CPU가_집계된다(self):
        before = worker_resources.child_cpu_seconds()
        subprocess.run([sys.executable, "-c", "sum(i * i for i in range(3_000_000))"], check=True)
        self.assertGreater(worker_resources.child_cpu_seconds(), before)


class PeakMemoryTest(unittest.TestCase):
    def test_리눅스에서는_부모_값이_남는_ru_maxrss_대신_VmHWM을_쓴다(self):
        # #320: Java가 띄운 Python의 ru_maxrss가 JVM 크기(약 400MiB)로 찍혔다.
        with tempfile.TemporaryDirectory() as tmp:
            status = Path(tmp) / "status"
            status.write_text("Name:\tpython3\nVmPeak:\t  30000 kB\nVmHWM:\t    8908 kB\nVmRSS:\t 8800 kB\n")
            self.assertEqual(worker_resources.self_peak_kib(str(status)), 8908)

    def test_VmHWM이_없으면_ru_maxrss를_쓴다(self):
        # macOS에는 /proc가 없다.
        self.assertGreater(worker_resources.self_peak_kib("/nonexistent/status"), 1024)

    def test_부모가_메모리를_많이_써도_자식_Python의_최대_메모리는_자기_값이다(self):
        code = ("import sys; sys.path.insert(0, %r); import worker_resources; print(worker_resources.snapshot()['maxRssKib'])"
                % str(Path(__file__).parent))
        blob = bytearray(120 * 1024 * 1024)
        for i in range(0, len(blob), 4096):
            blob[i] = 1
        child_kib = int(subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, check=True).stdout)
        del blob
        if sys.platform.startswith("linux"):
            self.assertLess(child_kib, 100 * 1024)


class StageCpuTest(unittest.TestCase):
    def test_단계마다_자기_CPU와_자식_CPU를_더한다(self):
        wall, cpu, child = FakeClock(), FakeClock(), FakeClock()
        timer = StageTimer(clock=wall, cpu_clock=cpu, child_cpu_clock=child)
        with timer.stage("frames"):
            wall.now += 2.0
            cpu.now += 0.05
            child.now += 1.2
        with timer.stage("frames"):
            cpu.now += 0.01
        self.assertEqual(timer.as_dict(), {"frames": 2000})
        self.assertEqual(timer.cpu_dict(), {"framesCpuMs": 60, "framesChildCpuMs": 1200})


class RequestPrepStatsTest(unittest.TestCase):
    def setUp(self):
        for key in extract_places.REQUEST_PREP_STATS:
            extract_places.REQUEST_PREP_STATS[key] = 0

    def test_build_parts_결과는_그대로이고_호출_수와_시간만_쌓인다(self):
        with tempfile.TemporaryDirectory() as tmp:
            audio = Path(tmp) / "a.mp3"
            audio.write_bytes(b"\x00" * 200_000)
            expected = extract_places._build_parts("자막", audio, None, "프롬프트", candidates=["해운대"])
            actual = extract_places.build_parts("자막", audio, None, "프롬프트", candidates=["해운대"])
        self.assertEqual(actual, expected)
        stats = extract_places.REQUEST_PREP_STATS
        self.assertEqual(stats["buildPartsCalls"], 1)
        self.assertGreater(stats["buildPartsUs"], 0)

    def test_요청_JSON을_만든_시간과_크기가_쌓인다(self):
        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def read(self):
                return b'{"candidates": [{"content": {"parts": [{"text": "[]"}]}}]}'

        original = extract_places.urllib.request.urlopen
        extract_places.urllib.request.urlopen = lambda request, timeout: Response()
        try:
            extract_places.call_gemini([{"text": "x" * 50_000}], "test-model", "test-key", "test.prep")
        finally:
            extract_places.urllib.request.urlopen = original
        stats = extract_places.REQUEST_PREP_STATS
        self.assertGreater(stats["requestBytes"], 50_000)
        self.assertGreaterEqual(stats["requestJsonUs"], 0)

    def test_결과_JSON의_resources는_세_묶음을_합친다(self):
        timer = StageTimer()
        with timer.stage("gemini"):
            pass
        report = run_pipeline.resource_report(timer)
        self.assertIn("maxRssKib", report)
        self.assertIn("geminiCpuMs", report)
        self.assertIn("geminiChildCpuMs", report)
        self.assertIn("buildPartsUs", report)
        self.assertTrue(all(isinstance(v, int) for v in report.values()))


if __name__ == "__main__":
    unittest.main()
