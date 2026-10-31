"""볼 수 없는 게시물 구분(#257 E). 실행: python3 -m unittest test_unavailable (pipeline-test 폴더에서)."""
import subprocess
import unittest
from pathlib import Path
from unittest import mock

import run_pipeline


def _fail(stderr: str) -> subprocess.CompletedProcess:
    return subprocess.CompletedProcess(args=["yt-dlp"], returncode=1, stdout="", stderr=stderr)


class UnavailableReasonTest(unittest.TestCase):
    def test_비공개_삭제_404는_이유를_돌려준다(self):
        cases = {
            "ERROR: [Instagram] This post is private": "비공개",
            "ERROR: [youtube] x: Private video. Sign in if you've been granted access": "비공개",
            "ERROR: [youtube] x: Video unavailable": "볼 수 없는 영상",
            "ERROR: [youtube] x: This video is no longer available": "삭제됨",
            "ERROR: Unable to download webpage: HTTP Error 404: Not Found": "없는 게시물",
        }
        for stderr, reason in cases.items():
            with self.subTest(stderr=stderr):
                self.assertEqual(run_pipeline.unavailable_reason(stderr), reason)

    def test_다시_하면_될_수_있는_오류는_None이다(self):
        # 형식 선택 실패·속도 제한·도구 오류는 "다시 해도 같음"이 아니다(리뷰에서 잡은 오분류).
        for stderr in (
            "ERROR: [youtube] x: Requested format is not available. Use --list-formats for a list of available formats",
            "HTTP Error 429: Too Many Requests",
            "ffmpeg exited with code 1",
            "WARNING: private key file not found; ERROR: network unreachable",
            "",
        ):
            with self.subTest(stderr=stderr):
                self.assertIsNone(run_pipeline.unavailable_reason(stderr))

    def test_인스타_범용_문구는_어느_쪽인지_몰라_None이다(self):
        self.assertIsNone(run_pipeline.unavailable_reason(
            "ERROR: [Instagram] ABC: Requested content is not available, rate-limit reached or login required"))

    def test_표식은_서버와_같은_문자열이다(self):
        self.assertEqual(run_pipeline.SOURCE_UNAVAILABLE_MARKER, "SOURCE_UNAVAILABLE")
        exit_ = run_pipeline._unavailable_exit("ERROR: This post is private", "볼 수 없는 게시물")
        self.assertTrue(str(exit_).startswith("SOURCE_UNAVAILABLE 볼 수 없는 게시물(비공개)"))


class InstagramFetchMarkerTest(unittest.TestCase):
    """instagram_fetch가 서버에 넘기는 실제 표식 — 429 → 사진 게시물 → 볼 수 없음 → 일반 실패 순서."""

    def _fetch(self, stderr: str):
        with mock.patch.object(run_pipeline.subprocess, "run", return_value=_fail(stderr)):
            with self.assertRaises(SystemExit) as ctx:
                run_pipeline.instagram_fetch("https://www.instagram.com/reel/x/", Path("work/test-insta"))
        return str(ctx.exception)

    def test_429는_속도_제한_표식이다(self):
        self.assertTrue(self._fetch("HTTP Error 429: Too Many Requests").startswith(run_pipeline.SOURCE_RATE_LIMIT_MARKER))

    def test_비공개_게시물은_볼_수_없음_표식이다(self):
        self.assertTrue(self._fetch("ERROR: [Instagram] This post is private").startswith(run_pipeline.SOURCE_UNAVAILABLE_MARKER))

    def test_범용_문구는_표식_없는_일반_실패로_남긴다(self):
        message = self._fetch("ERROR: [Instagram] x: Requested content is not available, rate-limit reached or login required")
        self.assertTrue(message.startswith("yt-dlp 다운로드 실패"))
        self.assertNotIn(run_pipeline.SOURCE_UNAVAILABLE_MARKER, message)
        self.assertNotIn(run_pipeline.SOURCE_RATE_LIMIT_MARKER, message)

    def test_사진_게시물_정보도_비공개면_볼_수_없음_표식이다(self):
        with mock.patch.object(run_pipeline.subprocess, "run", return_value=_fail("ERROR: [Instagram] This post is private")):
            with self.assertRaises(SystemExit) as ctx:
                run_pipeline._photo_post_info("https://www.instagram.com/p/x/")
        self.assertTrue(str(ctx.exception).startswith(run_pipeline.SOURCE_UNAVAILABLE_MARKER))


class YoutubeDownloadMarkerTest(unittest.TestCase):
    """유튜브 대체 경로: download.download의 실패를 볼 수 없는 영상 표식으로 바꾸되, 모르는 오류는 그대로 올린다."""

    def test_비공개_영상은_볼_수_없는_영상_표식으로_바꾼다(self):
        with mock.patch.object(run_pipeline.download, "download",
                               side_effect=SystemExit("yt-dlp 다운로드 실패:\nERROR: [youtube] x: Private video")):
            with self.assertRaises(SystemExit) as ctx:
                run_pipeline.run_download("https://www.youtube.com/shorts/x", Path("work/test-yt"))
        self.assertTrue(str(ctx.exception).startswith("SOURCE_UNAVAILABLE 볼 수 없는 영상(비공개)"))

    def test_모르는_오류는_표식_없이_그대로_올린다(self):
        with mock.patch.object(run_pipeline.download, "download",
                               side_effect=SystemExit("yt-dlp 다운로드 실패:\nERROR: ffmpeg exited with code 1")):
            with self.assertRaises(SystemExit) as ctx:
                run_pipeline.run_download("https://www.youtube.com/shorts/x", Path("work/test-yt"))
        self.assertEqual(str(ctx.exception), "yt-dlp 다운로드 실패:\nERROR: ffmpeg exited with code 1")


if __name__ == "__main__":
    unittest.main()
