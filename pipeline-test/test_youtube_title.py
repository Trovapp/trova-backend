import io
import unittest

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


if __name__ == "__main__":
    unittest.main()
