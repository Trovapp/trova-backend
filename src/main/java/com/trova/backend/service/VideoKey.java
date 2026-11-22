package com.trova.backend.service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 같은 영상인지 비교하는 키. 쇼츠/watch/youtu.be처럼 주소 모양이 달라도, 공유용 ?si= 같은 파라미터가
 * 붙어도 영상 ID가 같으면 같은 키가 된다(앱 trova-app src/lib/shareUrl.ts의 sourceVideoKey와 같은 규칙).
 * 알 수 없는 형식은 주소 자체(앞뒤 공백 제거)를 키로 쓴다.
 */
public final class VideoKey {

    private record Rule(Pattern pattern, String platform) {
    }

    // Java와 이력 SQL에서 같은 패턴의 첫 번째 캡처 그룹을 ID로 쓴다.
    public static final String YOUTUBE = "(?:youtube(?:-nocookie)?\\.com/(?:shorts/|watch\\?(?:[^#]*&)?v=)|youtu\\.be/)([a-zA-Z0-9_-]+)";
    public static final String INSTAGRAM = "instagram\\.com/(?:reels?|tv|p)/([a-zA-Z0-9_-]+)";

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile(YOUTUBE, Pattern.CASE_INSENSITIVE), "yt"),
            new Rule(Pattern.compile(INSTAGRAM, Pattern.CASE_INSENSITIVE), "ig")
    );

    private VideoKey() {
    }

    public static String of(String url) {
        if (url == null) {
            return "";
        }
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(url);
            if (matcher.find()) {
                return rule.platform() + ":" + matcher.group(1);
            }
        }
        return url.trim();
    }
}
