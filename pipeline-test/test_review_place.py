import unittest

from summarize_reviews import split_place


class SplitPlaceTest(unittest.TestCase):
    def test_첫_항목이_장소면_리뷰에서_떼어_낸다(self):
        place, reviews = split_place(["[장소] 롯데백화점 부산본점 (department_store)", "[별점 5] 철판요리가 맛있어요"])
        self.assertEqual(place, "롯데백화점 부산본점 (department_store)")
        self.assertEqual(reviews, ["[별점 5] 철판요리가 맛있어요"])

    def test_장소가_없으면_리뷰를_그대로_둔다(self):
        self.assertEqual(split_place(["[별점 5] 좋아요"]), (None, ["[별점 5] 좋아요"]))
        self.assertEqual(split_place([]), (None, []))


if __name__ == "__main__":
    unittest.main()
