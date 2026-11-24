import io
import unittest
from pathlib import Path
from threading import Event
from unittest.mock import patch

import run_pipeline

from run_pipeline import title_from_html, title_from_stream


class TitleFromHtmlTest(unittest.TestCase):
    def test_og_title을_읽고_HTML_엔티티를_푼다(self):
        page = '<head><meta property="og:title" content="부산여행 1박2일 &amp; 뚜벅이코스!"></head>'
        self.assertEqual(title_from_html(page), "부산여행 1박2일 & 뚜벅이코스!")

    def test_기본_제목이나_없는_경우는_None(self):
        self.assertIsNone(title_from_html('<meta property="og:title" content="YouTube">'))
        self.assertIsNone(title_from_html("<html></html>"))

    def test_제목_태그가_페이지_뒤쪽_800KB_지점에_있어도_찾는다(self):
        page = "x" * 800_000 + '<meta property="og:title" content="부산여행 1박2일 뚜벅이코스!">' + "y" * 1_500_000
        self.assertEqual(title_from_stream(io.BytesIO(page.encode("utf-8"))), "부산여행 1박2일 뚜벅이코스!")

    def test_읽기_한도까지_제목이_없으면_None(self):
        self.assertIsNone(title_from_stream(io.BytesIO(b"x" * 3_500_000)))


class ParallelTitleTest(unittest.TestCase):
    def test_제목과_분석이_겹치고_제목을_먼저_알린다(self):
        analyzing = Event()
        reported = Event()
        events = []

        def title(url):
            self.assertTrue(analyzing.wait(3))
            return "전주"

        def report(**fields):
            events.append(fields)
            if "title" in fields:
                reported.set()

        def places(video_uri):
            analyzing.set()
            self.assertTrue(reported.wait(3))
            return [{"name": "전주난장"}]

        with patch.object(run_pipeline, "youtube_title", title), patch.object(run_pipeline, "extract_places", places), \
                patch.object(run_pipeline, "emit_progress", report):
            result = run_pipeline.run("https://youtu.be/sample", Path("."))
        self.assertEqual(events, [{"title": "전주"}, {"placeNames": ["전주난장"]}])
        self.assertEqual(result["title"], "전주")

    def test_제목이_늦어도_확정된_장소를_먼저_알린다(self):
        names_reported = Event()

        def title(url):
            self.assertTrue(names_reported.wait(3))
            return "전주"

        def report(**fields):
            if fields.get("placeNames") == ["전주난장"]:
                names_reported.set()

        with patch.object(run_pipeline, "youtube_title", title), \
                patch.object(run_pipeline, "extract_places", return_value=[{"name": "전주난장"}]), \
                patch.object(run_pipeline, "emit_progress", report):
            result = run_pipeline.run("https://youtu.be/sample", Path("."))
        self.assertEqual(result["title"], "전주")

    def test_직접_접근_실패시_다운로드에_같은_제목을_넘긴다(self):
        url = "https://youtu.be/sample"
        work_dir = Path(".")
        with patch.object(run_pipeline, "youtube_title", return_value="전주") as lookup, \
                patch.object(run_pipeline, "extract_places", side_effect=SystemExit("Gemini API error 403")), \
                patch.object(run_pipeline, "run_download", return_value={"title": "전주"}) as fallback:
            self.assertEqual(run_pipeline.run(url, work_dir), {"title": "전주"})
        lookup.assert_called_once_with(url)
        self.assertEqual(fallback.call_args.args[:3], (url, work_dir, "전주"))


if __name__ == "__main__":
    unittest.main()
