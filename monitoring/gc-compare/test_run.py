import tempfile
import unittest
from pathlib import Path

from run import parse_gc_log, summarize

SERIAL = """[2026-11-04T23:00:00.000+0000][12.0s][info][gc,start    ] GC(5) Pause Full (CodeCache GC Threshold)
[2026-11-04T23:00:05.000+0000][17.0s][info][gc          ] GC(5) Pause Full (CodeCache GC Threshold) 45M->30M(100M) 4970.123ms
[2026-11-04T23:00:05.000+0000][17.0s][info][gc,cpu      ] GC(5) User=0.20s Sys=0.05s Real=4.97s
[2026-11-04T23:00:06.000+0000][18.0s][info][gc          ] GC(6) Pause Young (Allocation Failure) 60M->20M(100M) 8.500ms
[2026-11-04T23:00:06.000+0000][18.0s][info][safepoint   ] Safepoint "Serial", Time since last: 10 ns, Reaching safepoint: 100 ns, Cleanup: 5 ns, At safepoint: 8500000 ns, Total: 8600000 ns
"""
G1 = """[2026-11-04T23:00:00.000+0000][12.0s][info][gc          ] GC(7) Pause Young (Concurrent Start) (CodeCache GC Threshold) 50M->40M(100M) 12.345ms
[2026-11-04T23:00:00.000+0000][12.0s][info][gc          ] GC(7) Pause Remark 41M->41M(100M) 3.200ms
"""


class ParseGcLogTest(unittest.TestCase):
    def parse(self, text):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / 'gc.log'; f.write_text(text)
            return parse_gc_log(f)

    def test_Serial의_CodeCache_Full_GC와_CPU시간을_읽는다(self):
        parsed = self.parse(SERIAL)
        full = parsed['pauses'][0]
        self.assertEqual((full['kind'], full['cause'], full['ms']), ('Pause Full (CodeCache GC Threshold)', 'CodeCache GC Threshold', 4970.123))
        self.assertEqual((full['user'], full['sys'], full['real']), (0.20, 0.05, 4.97))
        s = summarize(parsed)
        self.assertEqual(s['codeCache'], {'count': 1, 'maxMs': 4970.123, 'sumMs': 4970.1})
        self.assertEqual(s['full']['count'], 1)
        self.assertEqual(s['all']['count'], 2)
        self.assertEqual(s['safepointMaxMs'], 8.6)

    def test_G1의_CodeCache는_Full이_아닌_Concurrent_Start로_센다(self):
        s = summarize(self.parse(G1))
        self.assertEqual(s['codeCache'], {'count': 1, 'maxMs': 12.345, 'sumMs': 12.3})
        self.assertEqual(s['full']['count'], 0)
        self.assertEqual(s['all']['count'], 2)


if __name__ == '__main__':
    unittest.main()
