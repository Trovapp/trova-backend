"""교차 판정 이름 맞추기(#437). 실행: python3 -m unittest -q test_cross_check (eval/extraction 폴더에서)."""
import unittest

import cross_check


class SamePlaceTest(unittest.TestCase):
    def test_띄어쓰기와_기호만_다르면_같은_장소다(self):
        self.assertTrue(cross_check.same_place("해운대 암소갈비집", "해운대암소갈비집"))
        self.assertTrue(cross_check.same_place("카페·드 파리", "카페드파리"))

    def test_본점과_괄호_설명은_무시한다(self):
        self.assertTrue(cross_check.same_place("명동교자 본점", "명동교자"))
        self.assertTrue(cross_check.same_place("성산일출봉(유네스코)", "성산일출봉"))

    def test_세_글자_이상이_다른_이름에_들어가면_같은_장소다(self):
        self.assertTrue(cross_check.same_place("감천문화마을", "부산 감천문화마을"))

    def test_두_글자_포함은_우연일_수_있어_같다고_보지_않는다(self):
        self.assertFalse(cross_check.same_place("해변", "광안리해변카페"))

    def test_다른_가게는_다르다(self):
        self.assertFalse(cross_check.same_place("이재모피자", "해운대 블루라인파크"))
        self.assertFalse(cross_check.same_place("", "이재모피자"))


class MatchPlacesTest(unittest.TestCase):
    def test_대안_철자로도_짝을_짓고_남는_쪽은_한쪽만으로_둔다(self):
        gemini = [{"name": "이재모 피자", "nameCandidates": ["이재모피자"]}, {"name": "흰여울문화마을"}]
        gpt = [{"name": "이재모피자"}, {"name": "해동용궁사"}]
        pairs = cross_check.match_places(gemini, gpt)
        statuses = sorted("both" if g and o else "gemini_only" if g else "gpt_only" for g, o in pairs)
        self.assertEqual(statuses, ["both", "gemini_only", "gpt_only"])

    def test_한_장소는_한_번만_짝이_된다(self):
        gemini = [{"name": "명동교자"}, {"name": "명동교자 본점"}]
        gpt = [{"name": "명동교자"}]
        pairs = cross_check.match_places(gemini, gpt)
        self.assertEqual(sum(1 for g, o in pairs if g and o), 1)


class CompareTest(unittest.TestCase):
    def test_일치는_agreed에_불일치는_사람_확인_목록에_쓴다(self):
        import csv
        import json
        import tempfile
        from pathlib import Path
        from types import SimpleNamespace

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "gemini").mkdir()
            (root / "gpt").mkdir()
            cands = root / "candidates.csv"
            cands.write_text("sample_id,platform,country,region,url,title,duration_sec\n"
                             "c001,youtube_shorts,domestic,부산,https://example.com/a,부산 맛집,30\n", encoding="utf-8")
            (root / "gemini" / "c001.json").write_text(json.dumps(
                {"status": "ok", "places": [{"name": "이재모피자", "region": "부산"}, {"name": "흰여울문화마을"}]}),
                encoding="utf-8")
            (root / "gpt" / "c001.json").write_text(json.dumps(
                {"is_place_content": True, "places": [{"name": "이재모 피자"}, {"name": "해동용궁사"}]}), encoding="utf-8")
            old = cross_check.CANDIDATES_PATH[0]
            cross_check.CANDIDATES_PATH[0] = cands
            try:
                cross_check.cmd_compare(SimpleNamespace(run=str(root)))
            finally:
                cross_check.CANDIDATES_PATH[0] = old
            agreed = list(csv.DictReader(open(root / "agreed.csv", encoding="utf-8")))
            mismatches = list(csv.DictReader(open(root / "mismatches.csv", encoding="utf-8")))
            summary = json.loads((root / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual([a["place_name"] for a in agreed], ["이재모피자"])
        self.assertEqual(sorted(m["status"] for m in mismatches), ["gemini_only", "gpt_only"])
        self.assertEqual(summary["place_agreement"], round(1 / 3, 3))


class StopSignalTest(unittest.TestCase):
    def test_429_재시도_로그나_하루_한도가_보이면_멈춘다(self):
        self.assertTrue(cross_check.should_stop("[extract_places] Gemini 429 — 5초 대기 후 재시도 (2/4)"))
        self.assertTrue(cross_check.should_stop("DAILY_QUOTA_EXHAUSTED Gemini 하루 한도 소진"))
        self.assertFalse(cross_check.should_stop("[extract_places] Gemini 503 — 5초 대기 후 재시도"))


if __name__ == "__main__":
    unittest.main()
