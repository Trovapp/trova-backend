"""볼 수 없는 게시물 구분(#257 E). 실행: python3 -m unittest test_unavailable (pipeline-test 폴더에서)."""
import unittest

import run_pipeline


class UnavailableReasonTest(unittest.TestCase):
    def test_로그인_비공개_삭제_404는_이유를_돌려준다(self):
        cases = {
            "ERROR: [Instagram] ABC: login required": "로그인 필요",
            "ERROR: [Instagram] This post is private": "비공개",
            "ERROR: [youtube] x: Private video. Sign in if you've been granted access": "비공개",
            "ERROR: [youtube] x: Video unavailable": "볼 수 없는 영상",
            "ERROR: Unable to download webpage: HTTP Error 404: Not Found": "없는 게시물",
            "ERROR: [Instagram] Requested content is not available, rate-limit reached or login required": "로그인 필요",
        }
        for stderr, reason in cases.items():
            with self.subTest(stderr=stderr):
                self.assertEqual(run_pipeline.unavailable_reason(stderr), reason)

    def test_속도_제한이나_모르는_오류는_None이다(self):
        self.assertIsNone(run_pipeline.unavailable_reason("HTTP Error 429: Too Many Requests"))
        self.assertIsNone(run_pipeline.unavailable_reason("ffmpeg exited with code 1"))
        self.assertIsNone(run_pipeline.unavailable_reason(""))

    def test_표식은_서버와_같은_문자열이다(self):
        self.assertEqual(run_pipeline.SOURCE_UNAVAILABLE_MARKER, "SOURCE_UNAVAILABLE")
        exit_ = run_pipeline._unavailable_exit("ERROR: login required", "볼 수 없는 게시물")
        self.assertTrue(str(exit_).startswith("SOURCE_UNAVAILABLE 볼 수 없는 게시물(로그인 필요)"))


if __name__ == "__main__":
    unittest.main()
