import unittest

from run_pipeline import title_from_html


class TitleFromHtmlTest(unittest.TestCase):
    def test_og_title을_읽고_HTML_엔티티를_푼다(self):
        page = '<head><meta property="og:title" content="부산여행 1박2일 &amp; 뚜벅이코스!"></head>'
        self.assertEqual(title_from_html(page), "부산여행 1박2일 & 뚜벅이코스!")

    def test_기본_제목이나_없는_경우는_None(self):
        self.assertIsNone(title_from_html('<meta property="og:title" content="YouTube">'))
        self.assertIsNone(title_from_html("<html></html>"))


if __name__ == "__main__":
    unittest.main()
