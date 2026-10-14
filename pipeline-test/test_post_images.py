"""사진 게시물에서 사진 주소 고르기(#196). 실행: python3 -m unittest test_post_images (pipeline-test 폴더에서)."""
import unittest

from post_images import post_image_urls


def photo(i):
    # yt-dlp 정보 모양(2026-10-14 실제 게시물에서 줄임): thumbnail은 잘리지 않은 원본, thumbnails 첫째는 가운데를 자른 1080.
    return {
        "formats": [],
        "thumbnail": f"https://cdn/{i}/orig.jpg",
        "thumbnails": [{"url": f"https://cdn/{i}/c0_s1080.jpg"}, {"url": f"https://cdn/{i}/s150.jpg"},
                       {"url": f"https://cdn/{i}/orig.jpg"}],
    }


def video(i):
    return {"formats": [{"vcodec": "avc1", "url": f"https://cdn/{i}/v.mp4"}], "thumbnail": f"https://cdn/{i}/cover.jpg"}


class PostImagesTest(unittest.TestCase):
    def test_여러_장_게시물은_사진마다_잘리지_않은_원본을_고른다(self):
        info = {"_type": "playlist", "entries": [photo(0), photo(1)]}
        self.assertEqual(post_image_urls(info), ["https://cdn/0/orig.jpg", "https://cdn/1/orig.jpg"])

    def test_사진_한_장_게시물도_고른다(self):
        self.assertEqual(post_image_urls(photo(7)), ["https://cdn/7/orig.jpg"])

    def test_영상이_섞이면_사진만_고른다(self):
        info = {"entries": [video(0), photo(1)]}
        self.assertEqual(post_image_urls(info), ["https://cdn/1/orig.jpg"])

    def test_최대_장수를_넘기지_않는다(self):
        info = {"entries": [photo(i) for i in range(20)]}
        self.assertEqual(len(post_image_urls(info, limit=10)), 10)

    def test_thumbnail이_없으면_목록의_마지막을_쓰고_둘_다_없으면_건너뛴다(self):
        no_main = photo(3)
        del no_main["thumbnail"]
        info = {"entries": [no_main, {"formats": []}]}
        self.assertEqual(post_image_urls(info), ["https://cdn/3/orig.jpg"])


if __name__ == "__main__":
    unittest.main()


class PhotoPostRouteTest(unittest.TestCase):
    def test_영상도_사진도_설명도_없으면_받지_못함_표식으로_멈춘다(self):
        import run_pipeline
        from stage_timer import StageTimer
        with self.assertRaises(SystemExit) as ctx:
            run_pipeline.run_photo_post("제목", None, [], StageTimer())
        self.assertTrue(str(ctx.exception).startswith(run_pipeline.NO_MEDIA_MARKER))
