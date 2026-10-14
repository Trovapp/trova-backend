"""stage_timer 단위 테스트(#194). 실행: python3 -m unittest test_stage_timer (pipeline-test 폴더에서)."""
import unittest

from stage_timer import StageTimer


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now


class StageTimerTest(unittest.TestCase):
    def test_단계마다_밀리초로_기록한다(self):
        clock = FakeClock()
        timer = StageTimer(clock=clock)
        with timer.stage("download"):
            clock.now += 28.1
        with timer.stage("gemini"):
            clock.now += 10.6
        self.assertEqual(timer.as_dict(), {"download": 28100, "gemini": 10600})

    def test_같은_단계를_다시_재면_더한다(self):
        # 유튜브 링크 직접 분석이 실패해 다운로드로 다시 갈 때 Gemini 시간이 두 번 생긴다.
        clock = FakeClock()
        timer = StageTimer(clock=clock)
        with timer.stage("gemini"):
            clock.now += 2.0
        with timer.stage("gemini"):
            clock.now += 3.5
        self.assertEqual(timer.as_dict(), {"gemini": 5500})

    def test_단계에서_오류가_나도_시간은_남긴다(self):
        clock = FakeClock()
        timer = StageTimer(clock=clock)
        with self.assertRaises(SystemExit):
            with timer.stage("download"):
                clock.now += 1.0
                raise SystemExit("blocked")
        self.assertEqual(timer.as_dict(), {"download": 1000})


if __name__ == "__main__":
    unittest.main()
