"""장소 소개 콘텐츠 판정(#206). 실행: python3 -m unittest test_place_content (pipeline-test 폴더에서)."""
import json
import unittest
from unittest import mock

import extract_places


class ParseCollectTest(unittest.TestCase):
    def test_객체_응답에서_판정과_후보를_읽는다(self):
        text = json.dumps({"isPlaceContent": True, "candidates": ["해운대", "황금집"]}, ensure_ascii=False)
        self.assertEqual(extract_places.parse_collect(text), (True, ["해운대", "황금집"]))

    def test_장소_소개가_아니라고_하면_거짓(self):
        text = json.dumps({"isPlaceContent": False, "candidates": ["스파오"]}, ensure_ascii=False)
        self.assertEqual(extract_places.parse_collect(text), (False, ["스파오"]))

    def test_예전처럼_배열만_오면_장소_소개로_본다(self):
        self.assertEqual(extract_places.parse_collect('["부산"]'), (True, ["부산"]))

    def test_판정_칸이_없거나_이상하면_장소_소개로_본다(self):
        # 모르면 막지 않는다 — 장소 영상을 잘못 거르는 쪽이 더 나쁘다.
        self.assertEqual(extract_places.parse_collect('{"candidates": ["a"]}'), (True, ["a"]))
        self.assertEqual(extract_places.parse_collect('{"isPlaceContent": "no", "candidates": []}'), (True, []))

    def test_형식이_틀리면_다시_묻도록_오류(self):
        with self.assertRaises(ValueError):
            extract_places.parse_collect('"그냥 문장"')


class ExtractStopsEarlyTest(unittest.TestCase):
    def test_장소_소개가_아니면_2차를_부르지_않고_표식으로_멈춘다(self):
        calls = []

        def fake_call(parts, model, api_key, operation, parse):
            calls.append(operation)
            return parse(json.dumps({"isPlaceContent": False, "candidates": ["스파오"]}, ensure_ascii=False))

        with mock.patch.object(extract_places, "load_api_key", return_value="k"), \
                mock.patch.object(extract_places, "call_gemini_with_repair", side_effect=fake_call):
            with self.assertRaises(SystemExit) as ctx:
                extract_places.extract_places(transcript="협찬 옷 소개")
        self.assertTrue(str(ctx.exception).startswith(extract_places.NOT_PLACE_CONTENT_MARKER))
        self.assertEqual(calls, ["extract_places.collect"])


if __name__ == "__main__":
    unittest.main()
