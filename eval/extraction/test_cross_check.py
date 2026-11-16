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


class OneCharTypoTest(unittest.TestCase):
    def test_네_글자_이상에서_한_글자만_다르면_같은_장소다(self):
        self.assertTrue(cross_check.same_place("장안회집", "장안횟집"))
        self.assertTrue(cross_check.same_place("강릉 다강정", "강릉닭강정"))
        self.assertTrue(cross_check.same_place("서구근린공원", "서곶근린공원"))

    def test_세_글자_이하는_한_글자만_달라도_합치지_않는다(self):
        self.assertFalse(cross_check.same_place("새어도", "세어도"))
        self.assertFalse(cross_check.same_place("카페녘", "카페역"))

    def test_마지막_글자가_다르면_다른_장소다(self):
        self.assertFalse(cross_check.same_place("서귀포항", "서귀포시"))
        self.assertFalse(cross_check.same_place("해운대역", "해운대점"))

    def test_숫자가_다르면_다른_지점이다(self):
        self.assertFalse(cross_check.same_place("제주1호점", "제주2호점"))
        self.assertFalse(cross_check.same_place("스타벅스1점", "스타벅스12점"))

    def test_바뀐_글자의_초성이_다르면_다른_장소다(self):
        self.assertFalse(cross_check.same_place("동문시장", "서문시장"))
        self.assertFalse(cross_check.same_place("쌍교숯불갈비", "쌍도숯불갈비"))

    def test_두_글자_이상_다르면_다른_장소다(self):
        self.assertFalse(cross_check.same_place("해동용궁사", "해동용주암"))
        self.assertFalse(cross_check.same_place("광안리해변", "광안대교변"))


class MatchPlacesTest(unittest.TestCase):
    def test_대안_철자로도_짝을_짓고_남는_쪽은_한쪽만으로_둔다(self):
        gemini = [{"name": "이재모 피자", "nameCandidates": ["이재모피자"]}, {"name": "흰여울문화마을"}]
        gpt = [{"name": "이재모피자"}, {"name": "해동용궁사"}]
        pairs = cross_check.match_places(gemini, gpt)
        statuses = sorted("both" if g and o else "gemini_only" if g else "gpt_only" for g, o in pairs)
        self.assertEqual(statuses, ["both", "gemini_only", "gpt_only"])

    def test_대표_이름이_같은_짝을_대안_철자_짝보다_먼저_잡는다(self):
        gemini = [{"name": "하카타 포트 타워", "nameCandidates": ["하카타항", "하카타포트타워"]}]
        gpt = [{"name": "하카타항"}, {"name": "하카타 포트 타워"}]
        pairs = cross_check.match_places(gemini, gpt)
        both = [(g["name"], o["name"]) for g, o in pairs if g and o]
        self.assertEqual(both, [("하카타 포트 타워", "하카타 포트 타워")])
        self.assertEqual([o["name"] for g, o in pairs if not g], ["하카타항"])

    def test_대표_이름_짝이_없으면_대안_철자로_짝짓는다(self):
        gemini = [{"name": "측도", "nameCandidates": ["측도 바닷길"]}, {"name": "선재도"}]
        gpt = [{"name": "선재도"}, {"name": "축도 바닷길"}]
        pairs = cross_check.match_places(gemini, gpt)
        self.assertEqual(sorted((g["name"], o["name"]) for g, o in pairs if g and o),
                         [("선재도", "선재도"), ("측도", "축도 바닷길")])

    def test_이름이_정확히_같은_짝을_포함_규칙보다_먼저_잡는다_c072(self):
        gemini = [{"name": "하조대 전망대"}, {"name": "하조대 등대"}, {"name": "하조대 해수욕장"}]
        gpt = [{"name": "하조대 전망대"}, {"name": "하조대 해수욕장"}, {"name": "하조대"}, {"name": "하조대 등대"}]
        pairs = cross_check.match_places(gemini, gpt)
        self.assertEqual(sorted((g["name"], o["name"]) for g, o in pairs if g and o),
                         [("하조대 등대", "하조대 등대"), ("하조대 전망대", "하조대 전망대"),
                          ("하조대 해수욕장", "하조대 해수욕장")])
        self.assertEqual([o["name"] for g, o in pairs if not g], ["하조대"])

    def test_상위_장소는_하위_장소와_짝이_되지_않고_한쪽만으로_남는다_c071(self):
        gemini = [{"name": "고하도", "nameCandidates": ["고하도"]}, {"name": "커피파나쉐"},
                  {"name": "고하도 전망대", "nameCandidates": ["고하도 전망대"]}]
        gpt = [{"name": "고하도 전망대"}, {"name": "커피파나쉐"}]
        pairs = cross_check.match_places(gemini, gpt)
        self.assertEqual(sorted((g["name"], o["name"]) for g, o in pairs if g and o),
                         [("고하도 전망대", "고하도 전망대"), ("커피파나쉐", "커피파나쉐")])
        self.assertEqual([g["name"] for g, o in pairs if not o], ["고하도"])

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


    def test_둘_다_장소가_없는_샘플은_완전_일치로_세지_않는다(self):
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
                             "c001,youtube_shorts,domestic,부산,https://example.com/a,부산,30\n"
                             "c002,youtube_shorts,domestic,부산,https://example.com/b,부산,30\n", encoding="utf-8")
            (root / "gemini" / "c001.json").write_text(json.dumps({"status": "ok", "places": []}), encoding="utf-8")
            (root / "gpt" / "c001.json").write_text(json.dumps({"is_place_content": False, "places": []}),
                                                    encoding="utf-8")
            (root / "gemini" / "c002.json").write_text(json.dumps({"status": "ok", "places": [{"name": "이재모피자"}]}),
                                                       encoding="utf-8")
            (root / "gpt" / "c002.json").write_text(json.dumps({"places": [{"name": "이재모피자"}]}), encoding="utf-8")
            old = cross_check.CANDIDATES_PATH[0]
            cross_check.CANDIDATES_PATH[0] = cands
            try:
                cross_check.cmd_compare(SimpleNamespace(run=str(root)))
            finally:
                cross_check.CANDIDATES_PATH[0] = old
            summary = json.loads((root / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(summary["samples_fully_agreed"], 1)
        self.assertEqual(summary["samples_no_places"], 1)


class CmdGeminiTest(unittest.TestCase):
    """subprocess.run을 가짜 결과로 바꿔 Gemini를 부르지 않고 결과 저장·멈춤을 본다."""

    def run_gemini(self, outcomes: dict):
        import json
        import subprocess
        import tempfile
        from pathlib import Path
        from types import SimpleNamespace
        from unittest import mock

        calls = []

        def fake_run(cmd, **kwargs):
            sid = cmd[-1]
            calls.append(sid)
            code, stdout, stderr = outcomes[sid]
            return subprocess.CompletedProcess(cmd, code, stdout, stderr)

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            cands = root / "candidates.csv"
            cands.write_text("sample_id,platform,country,region,url,title,duration_sec\n" + "".join(
                f"{sid},youtube_shorts,domestic,부산,https://example.com/{sid},t,30\n" for sid in outcomes),
                encoding="utf-8")
            for sid in outcomes:
                (root / "work" / sid).mkdir(parents=True)
                (root / "work" / sid / "input.json").write_text("{}", encoding="utf-8")
            run = root / "run"
            old = cross_check.CANDIDATES_PATH[0]
            cross_check.CANDIDATES_PATH[0] = cands
            try:
                with mock.patch.object(cross_check, "WORK", root / "work"), \
                        mock.patch.object(cross_check.subprocess, "run", fake_run), \
                        mock.patch.object(cross_check.time, "sleep"):
                    cross_check.cmd_gemini(SimpleNamespace(run=str(run), ids=None, pause=0))
            finally:
                cross_check.CANDIDATES_PATH[0] = old
            saved = {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in (run / "gemini").glob("c*.json")}
            stopped = (run / "gemini" / "STOPPED.txt").exists()
        return calls, saved, stopped

    def test_성공하면_장소를_저장한다(self):
        calls, saved, stopped = self.run_gemini({"c001": (0, '[{"name": "이재모피자"}]\n', "")})
        self.assertEqual(saved["c001"]["status"], "ok")
        self.assertEqual(saved["c001"]["places"], [{"name": "이재모피자"}])
        self.assertFalse(stopped)

    def test_장소_영상이_아니면_빈_목록으로_저장한다(self):
        calls, saved, stopped = self.run_gemini({"c001": (1, "", "NOT_PLACE_CONTENT 장소 영상 아님\n")})
        self.assertEqual(saved["c001"]["status"], "not_place_content")
        self.assertEqual(saved["c001"]["places"], [])

    def test_오류면_파일을_쓰지_않아_다시_돌릴_수_있다(self):
        calls, saved, stopped = self.run_gemini({"c001": (1, "", "Traceback\nValueError: 깨진 응답\n")})
        self.assertEqual(saved, {})
        self.assertFalse(stopped)

    def test_429나_RESOURCE_EXHAUSTED가_보이면_멈추고_다음_샘플을_돌리지_않는다(self):
        for stderr in ("[extract_places] Gemini 429 — 5초 대기 후 재시도 (2/4)\n",
                       "google.genai.errors.ClientError: RESOURCE_EXHAUSTED\n"):
            with self.subTest(stderr=stderr):
                calls, saved, stopped = self.run_gemini({"c001": (1, "", stderr),
                                                         "c002": (0, "[]\n", "")})
                self.assertEqual(calls, ["c001"])
                self.assertTrue(stopped)
                self.assertNotIn("c002", saved)


class ApiCallsTest(unittest.TestCase):
    def test_API_로그_줄만_호출로_센다(self):
        stderr = ("[pipeline] 무관한 줄\n"
                  'TROVA_API_LOG:{"operation": "extract_places.collect", "attempts": 1, "success": true, "totalTokens": 10}\n'
                  "TROVA_API_LOG:깨진 줄\n"
                  'TROVA_API_LOG:{"operation": "extract_places.filter", "attempts": 2, "success": true}\n')
        calls = cross_check.api_calls(stderr)
        self.assertEqual([c["operation"] for c in calls], ["extract_places.collect", "extract_places.filter"])
        self.assertEqual(calls[1]["attempts"], 2)


class StopSignalTest(unittest.TestCase):
    def test_429_재시도_로그나_하루_한도가_보이면_멈춘다(self):
        self.assertTrue(cross_check.should_stop("[extract_places] Gemini 429 — 5초 대기 후 재시도 (2/4)"))
        self.assertTrue(cross_check.should_stop("DAILY_QUOTA_EXHAUSTED Gemini 하루 한도 소진"))
        self.assertFalse(cross_check.should_stop("[extract_places] Gemini 503 — 5초 대기 후 재시도"))

    def test_멈춤_신호_네_가지를_각각_알아본다(self):
        for line in ("Gemini 429 재시도", "DAILY_QUOTA_EXHAUSTED", "Gemini 하루 한도 소진", "status: RESOURCE_EXHAUSTED"):
            with self.subTest(line=line):
                self.assertTrue(cross_check.should_stop(f"[extract_places] {line}\n"))
        self.assertEqual(len(cross_check.STOP_PATTERNS), 4)


if __name__ == "__main__":
    unittest.main()
