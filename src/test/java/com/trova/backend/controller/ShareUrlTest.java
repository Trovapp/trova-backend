package com.trova.backend.controller;

import com.trova.backend.entity.SourcePlatform;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 제출된 링크에서 영상 ID만 뽑아 정식 주소로 다시 만든다(#49). 호스트만 보던 검사는
 * youtube.com/redirect?q=<임의 주소> 같은 링크를 통과시켜, yt-dlp가 리다이렉트를 따라 서버 내부 주소로
 * 요청하게 만들 수 있었다(로컬 재현: 127.0.0.1 리스너에 요청 4회 도착).
 */
class ShareUrlTest {

    @ParameterizedTest
    @CsvSource({
            "https://www.youtube.com/shorts/abc_-9, YOUTUBE, https://www.youtube.com/shorts/abc_-9",
            "https://youtube.com/shorts/abc?feature=share, YOUTUBE, https://www.youtube.com/shorts/abc",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=10s, YOUTUBE, https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/watch?feature=x&v=dQw4w9WgXcQ, YOUTUBE, https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?si=abc, YOUTUBE, https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/live/dQw4w9WgXcQ, YOUTUBE, https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube-nocookie.com/embed/abc, YOUTUBE, https://www.youtube.com/watch?v=abc",
            "https://www.youtube.com./shorts/abc, YOUTUBE, https://www.youtube.com/shorts/abc",
            "https://www.instagram.com/reel/C1a2B3c4D5e/?igsh=xyz, INSTAGRAM, https://www.instagram.com/reel/C1a2B3c4D5e/",
            "https://m.instagram.com/reels/abc, INSTAGRAM, https://www.instagram.com/reel/abc/",
            "https://instagram.com/p/abc/, INSTAGRAM, https://www.instagram.com/p/abc/",
            "https://www.instagram.com/tv/abc, INSTAGRAM, https://www.instagram.com/tv/abc/",
            "  https://www.youtube.com/shorts/abc  , YOUTUBE, https://www.youtube.com/shorts/abc"
    })
    void 영상_링크는_정식_주소로_바뀐다(String input, SourcePlatform platform, String canonical) {
        ShareUrl parsed = ShareUrl.parse(input).orElseThrow();

        assertThat(parsed.platform()).isEqualTo(platform);
        assertThat(parsed.canonicalUrl()).isEqualTo(canonical);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.youtube.com/redirect?q=http%3A%2F%2F169.254.169.254%2F&event=video_description",
            "https://www.youtube.com/attribution_link?u=http%3A%2F%2F127.0.0.1%2F",
            "https://www.youtube.com/watch?v=",
            "https://www.youtube.com/watch?v=abc%2F..%2Fx",
            "https://www.youtube.com/channel/UCabc",
            "https://www.youtube.com/",
            "https://youtu.be/",
            "https://l.instagram.com/?u=http%3A%2F%2F127.0.0.1%2F",
            "https://www.instagram.com/accounts/login/?next=/reel/abc",
            "https://www.instagram.com/someuser/",
            "https://evil.example.com/shorts/abc",
            "https://www.youtube.com@evil.example.com/shorts/abc",
            "ftp://www.youtube.com/shorts/abc",
            "not a url",
            ""
    })
    void 영상_형식이_아닌_링크는_거절한다(String input) {
        assertThat(ShareUrl.parse(input)).isEmpty();
    }
}
