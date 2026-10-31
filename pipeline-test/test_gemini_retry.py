"""Gemini 호출 기록의 시도 횟수·대기 시간(#257 A)과 선별 텍스트만 플래그(#257 B). 실행: python3 -m unittest test_gemini_retry."""
import contextlib
import io
import json
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

import extract_places


def _ok_response(text: str = "[]"):
    body = json.dumps({"candidates": [{"content": {"parts": [{"text": text}]}}],
                       "usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 2, "totalTokenCount": 12}}).encode()
    resp = mock.MagicMock()
    resp.read.return_value = body
    resp.__enter__.return_value = resp
    return resp


def _http_error(code: int, headers: dict | None = None):
    return urllib.error.HTTPError("https://x", code, "err", headers or {}, io.BytesIO(b'{"error":{"message":"x"}}'))


def _api_log_lines(stderr: str) -> list[dict]:
    return [json.loads(line[len(extract_places.API_LOG_MARKER):])
            for line in stderr.splitlines() if line.startswith(extract_places.API_LOG_MARKER)]


class RetryLogTest(unittest.TestCase):
    def test_429_뒤_재시도에_성공하면_시도_횟수와_기다린_시간을_기록한다(self):
        err = io.StringIO()
        with mock.patch.object(extract_places.urllib.request, "urlopen",
                               side_effect=[_http_error(429, {"Retry-After": "5"}), _ok_response()]), \
                mock.patch.object(extract_places.time, "sleep") as sleep, contextlib.redirect_stderr(err):
            extract_places.call_gemini([{"text": "x"}], "m", "key", "op")
        sleep.assert_called_once_with(5.0)
        entry = _api_log_lines(err.getvalue())[-1]
        self.assertTrue(entry["success"])
        self.assertEqual(entry["attempts"], 2)
        self.assertEqual(entry["waitedMs"], 5000)

    def test_한_번에_성공하면_시도_1회_대기_0이다(self):
        err = io.StringIO()
        with mock.patch.object(extract_places.urllib.request, "urlopen", return_value=_ok_response()), \
                contextlib.redirect_stderr(err):
            extract_places.call_gemini([{"text": "x"}], "m", "key", "op")
        entry = _api_log_lines(err.getvalue())[-1]
        self.assertEqual((entry["attempts"], entry["waitedMs"]), (1, 0))

    def test_모두_실패하면_attempts는_최대_시도_횟수이고_대기가_합산된다(self):
        err = io.StringIO()
        with mock.patch.object(extract_places.urllib.request, "urlopen",
                               side_effect=[_http_error(503)] * extract_places.MAX_ATTEMPTS), \
                mock.patch.object(extract_places.time, "sleep") as sleep, contextlib.redirect_stderr(err):
            with self.assertRaises(SystemExit):
                extract_places.call_gemini([{"text": "x"}], "m", "key", "op")
        entry = _api_log_lines(err.getvalue())[-1]
        self.assertFalse(entry["success"])
        self.assertEqual(entry["attempts"], extract_places.MAX_ATTEMPTS)
        self.assertEqual(entry["waitedMs"], round(sum(c.args[0] for c in sleep.call_args_list) * 1000))


class FilterTextOnlyTest(unittest.TestCase):
    def _run(self, flag: bool, transcript, frame, photo_post=False):
        calls = {}

        def fake(parts, model, api_key, operation, parse):
            calls[operation] = parts
            return (True, ["후보"]) if operation.endswith("collect") else []

        with mock.patch.object(extract_places, "FILTER_TEXT_ONLY", flag), \
                mock.patch.object(extract_places, "load_api_key", return_value="key"), \
                mock.patch.object(extract_places, "call_gemini_with_repair", side_effect=fake), \
                contextlib.redirect_stderr(io.StringIO()):
            extract_places.extract_places(transcript=transcript, frame_paths=[frame], photo_post=photo_post)
        return calls

    def _has_media(self, parts) -> bool:
        return any("inline_data" in p for p in parts)

    def test_플래그를_켜면_2차_호출에_프레임을_다시_올리지_않는다(self):
        with tempfile.TemporaryDirectory() as d:
            frame = Path(d) / "f.jpg"; frame.write_bytes(b"jpg")
            calls = self._run(True, "자막", frame)
        self.assertTrue(self._has_media(calls["extract_places.collect"]))
        self.assertFalse(self._has_media(calls["extract_places.filter"]))

    def test_자막도_설명도_없으면_플래그가_켜져도_2차에_미디어를_보낸다(self):
        with tempfile.TemporaryDirectory() as d:
            frame = Path(d) / "f.jpg"; frame.write_bytes(b"jpg")
            calls = self._run(True, None, frame)
        self.assertTrue(self._has_media(calls["extract_places.filter"]))

    def test_사진_게시물은_플래그가_켜져도_사진을_다시_보낸다(self):
        with tempfile.TemporaryDirectory() as d:
            frame = Path(d) / "f.jpg"; frame.write_bytes(b"jpg")
            calls = self._run(True, "설명", frame, photo_post=True)
        self.assertTrue(self._has_media(calls["extract_places.filter"]))

    def test_플래그가_꺼져_있으면_평소대로_두_번_다_보낸다(self):
        with tempfile.TemporaryDirectory() as d:
            frame = Path(d) / "f.jpg"; frame.write_bytes(b"jpg")
            calls = self._run(False, "자막", frame)
        self.assertTrue(self._has_media(calls["extract_places.filter"]))


if __name__ == "__main__":
    unittest.main()
