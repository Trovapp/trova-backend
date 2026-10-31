"""download.py 재시도 규칙(#257 D). 실행: python3 -m unittest test_download (pipeline-test 폴더에서)."""
import subprocess
import unittest
from pathlib import Path
from unittest import mock

import download


def _fail(stderr: str) -> subprocess.CompletedProcess:
    return subprocess.CompletedProcess(args=["yt-dlp"], returncode=1, stdout="", stderr=stderr)


class DownloadRetryTest(unittest.TestCase):
    def test_쿠키_설정이_없으면_403은_기다리지_않고_한_번에_실패한다(self):
        with mock.patch.object(download, "BROWSER_COOKIES", ""), \
                mock.patch.object(download, "_run_yt_dlp", return_value=_fail("HTTP Error 403: Forbidden")) as run, \
                mock.patch.object(download.time, "sleep") as sleep:
            with self.assertRaises(SystemExit):
                download.download("https://www.youtube.com/shorts/x", Path("work/test-download"))
        self.assertEqual(run.call_count, 1)
        sleep.assert_not_called()

    def test_쿠키_설정이_없어도_429는_쿠키_없이_기다렸다가_다시_시도한다(self):
        with mock.patch.object(download, "BROWSER_COOKIES", ""), \
                mock.patch.object(download, "_run_yt_dlp", return_value=_fail("HTTP Error 429: Too Many Requests")) as run, \
                mock.patch.object(download.time, "sleep") as sleep:
            with self.assertRaises(SystemExit):
                download.download("https://www.youtube.com/shorts/x", Path("work/test-download"))
        self.assertEqual(run.call_count, download.MAX_ATTEMPTS)
        self.assertEqual(sleep.call_count, download.MAX_ATTEMPTS - 1)
        for call in run.call_args_list:
            self.assertNotIn("--cookies-from-browser", call.args[0])

    def test_쿠키를_켜면_두_번째_시도부터_브라우저_쿠키를_붙인다(self):
        with mock.patch.object(download, "BROWSER_COOKIES", "chrome"), \
                mock.patch.object(download, "_run_yt_dlp", return_value=_fail("HTTP Error 403: Forbidden")) as run, \
                mock.patch.object(download.time, "sleep"):
            with self.assertRaises(SystemExit):
                download.download("https://www.youtube.com/shorts/x", Path("work/test-download"))
        self.assertEqual(run.call_count, download.MAX_ATTEMPTS)
        self.assertNotIn("--cookies-from-browser", run.call_args_list[0].args[0])
        self.assertEqual(run.call_args_list[1].args[0][:2], ["--cookies-from-browser", "chrome"])

    def test_제목_조회는_쿠키_설정이_없으면_403에_다시_시도하지_않는다(self):
        with mock.patch.object(download, "BROWSER_COOKIES", ""), \
                mock.patch.object(download, "_run_yt_dlp", return_value=_fail("HTTP Error 403")) as run:
            self.assertIsNone(download.get_title("https://www.youtube.com/shorts/x"))
        self.assertEqual(run.call_count, 1)


if __name__ == "__main__":
    unittest.main()
