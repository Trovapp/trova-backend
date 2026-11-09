"""프레임 뽑기(#323). 실행: python3 -m unittest test_frames (pipeline-test 폴더에서). ffmpeg가 없으면 영상 검사는 건너뛴다."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

import frames

HAS_FFMPEG = shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None


def make_video(path: Path, seconds: float, gop: int) -> None:
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
                    "-i", f"testsrc2=size=360x640:rate=30:duration={seconds}", "-c:v", "libx264", "-g", str(gop),
                    "-keyint_min", str(gop), "-sc_threshold", "0", "-pix_fmt", "yuv420p", str(path)], check=True)


def psnr(a: Path, b: Path) -> float:
    err = subprocess.run(["ffmpeg", "-hide_banner", "-i", str(a), "-i", str(b), "-lavfi", "psnr", "-f", "null", "-"],
                         capture_output=True, text=True).stderr
    value = re.search(r"average:(\S+)", err).group(1)
    return float("inf") if value == "inf" else float(value)


class PickTest(unittest.TestCase):
    def test_구간_가운데_직전의_마지막_장면을_고른다(self):
        times = [i / 10 for i in range(80)]  # 0.0 ~ 7.9초, 10fps
        picks = frames._pick_like_fps_filter(times, 1.0, 8)
        self.assertEqual(picks, [0.4, 1.4, 2.4, 3.4, 4.4, 5.4, 6.4, 7.4])

    def test_영상_끝을_넘는_구간은_뽑지_않는다(self):
        times = [i / 30 for i in range(36)]  # 1.2초 영상, 구간 0.5초 하한
        self.assertEqual(len(frames._pick_like_fps_filter(times, 0.5, 8)), 2)

    def test_키프레임이_드물고_영상이_짧으면_전체_디코딩을_고른다(self):
        times = [i / 30 for i in range(600)]  # 20초
        picks = frames._pick_like_fps_filter(times, 20 / 8, 8)
        self.assertFalse(frames._seek_is_cheaper(times, [0.0, 10.0], picks))  # 10초마다 키프레임
        self.assertTrue(frames._seek_is_cheaper(times, [i / 30 for i in range(0, 600, 15)], picks))  # 0.5초마다
        self.assertFalse(frames._seek_is_cheaper(times, [], picks))


class SeekCommandTest(unittest.TestCase):
    def test_입력마다_스레드_하나로_2곳씩_나눠_번호를_이어_붙인다(self):
        picks = [0.4 + i for i in range(8)]
        commands = frames._seek_commands(Path("v.mp4"), Path("out"), picks, 0.0, 512)
        self.assertEqual(len(commands), 4)
        outputs = []
        for cmd in commands:
            self.assertEqual(cmd.count("-i"), frames.SEEK_INPUTS_PER_CALL)
            for i, arg in enumerate(cmd):
                if arg == "-i":
                    self.assertEqual(cmd[i - 4:i - 2], ["-threads", "1"])
            outputs += [Path(a).name for a in cmd if a.endswith(".jpg")]
        self.assertEqual(outputs, [f"frame_{i:03d}.jpg" for i in range(1, 9)])

    def test_전체_디코딩도_스레드_하나로_연다(self):
        cmd = frames._full_decode_command(Path("v.mp4"), Path("out"), 8, 512, 2.0)
        i = cmd.index("-i")
        self.assertEqual(cmd[i - 2:i], ["-threads", "1"])
        self.assertIn("fps=1/2.000,scale=512:-1", cmd)

    def test_남는_지점이_홀수면_마지막_명령에_하나만_연다(self):
        commands = frames._seek_commands(Path("v.mp4"), Path("out"), [0.5, 1.5, 2.5], 0.0, 512)
        self.assertEqual([c.count("-i") for c in commands], [2, 1])
        self.assertIn("1:v:0", commands[0])
        self.assertNotIn("1:v:0", commands[1])


@unittest.skipUnless(HAS_FFMPEG, "ffmpeg 없음")
class VideoTest(unittest.TestCase):
    def test_키프레임이_5초마다인_영상에서_지점_이동으로_예전과_같은_장면을_뽑는다(self):
        # 운영 인스타 영상과 같은 키프레임 간격(5초)
        with tempfile.TemporaryDirectory() as tmp:
            video = Path(tmp) / "v.mp4"
            make_video(video, 40, 150)
            times, keys = frames._frame_times(video)
            picks = frames._pick_like_fps_filter(times, 40 / 8, 8)
            self.assertTrue(frames._seek_is_cheaper(times, keys, picks))
            old = frames.extract_frames_full_decode(video, Path(tmp) / "old")
            new = frames.extract_frames(video, Path(tmp) / "new")
            self.assertEqual(len(old), 8)
            self.assertEqual(len(new), 8)
            for a, b in zip(old, new):
                self.assertGreater(psnr(a, b), 35, a.name)

    def test_다시_뽑으면_이전_프레임을_지운다(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "out"
            out.mkdir()
            (out / "frame_009.jpg").write_bytes(b"old")
            video = Path(tmp) / "v.mp4"
            make_video(video, 2, 30)
            result = frames.extract_frames(video, out)
            self.assertNotIn(out / "frame_009.jpg", result)
            self.assertFalse((out / "frame_009.jpg").exists())


if __name__ == "__main__":
    unittest.main()
