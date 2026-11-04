"""Gemini 생각·캐시·영상 토큰 기록(#317). 실행: python3 -m unittest test_token_detail (pipeline-test 폴더에서)."""
import contextlib
import io
import json
import time
import unittest

import extract_places


def logged_entry(payload: dict) -> dict:
    err = io.StringIO()
    with contextlib.redirect_stderr(err):
        extract_places._log_api_call("extract_places.filter", time.monotonic(), True, payload=payload)
    line = err.getvalue().strip()
    return json.loads(line.removeprefix(extract_places.API_LOG_MARKER))


class TokenDetailTest(unittest.TestCase):
    def test_생각_캐시_영상_토큰을_남긴다(self):
        entry = logged_entry({"usageMetadata": {
            "promptTokenCount": 7300, "candidatesTokenCount": 400, "totalTokenCount": 8512,
            "thoughtsTokenCount": 812, "cachedContentTokenCount": 6400,
            "promptTokensDetails": [{"modality": "TEXT", "tokenCount": 300}, {"modality": "VIDEO", "tokenCount": 6000},
                                    {"modality": "AUDIO", "tokenCount": 1000}],
        }})
        self.assertEqual(entry["thoughtsTokens"], 812)
        self.assertEqual(entry["cachedTokens"], 6400)
        self.assertEqual(entry["videoTokens"], 6000)

    def test_세부_토큰이_없으면_null이다(self):
        entry = logged_entry({"usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 2, "totalTokenCount": 12}})
        self.assertIsNone(entry["thoughtsTokens"])
        self.assertIsNone(entry["cachedTokens"])
        self.assertIsNone(entry["videoTokens"])


if __name__ == "__main__":
    unittest.main()
