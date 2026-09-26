package com.trova.backend.service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 같은 영상인지 비교하는 키. 쇼츠/watch/youtu.be처럼 주소 모양이 달라도, 공유용 ?si= 같은 파라미터가
 * 붙어도 영상 ID가 같으면 같은 키가 된다(앱 trova-app src/lib/shareUrl.ts의 sourceVideoKey와 같은 규칙).
 * 알 수 없는 형식은 주소 자체(앞뒤 공백 제거)를 키로 쓴다.
 */
final class VideoKey {

    private record Rule(Pattern pattern, String platform) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("youtube(?:-nocookie)?\\.com/shorts/([\\w-]+)", Pattern.CASE_INSENSITIVE), "yt"),
            new Rule(Pattern.compile("youtube(?:-nocookie)?\\.com/watch\\?(?:.*&)?v=([\\w-]+)", Pattern.CASE_INSENSITIVE), "yt"),
            new Rule(Pattern.compile("youtu\\.be/([\\w-]+)", Pattern.CASE_INSENSITIVE), "yt"),
            new Rule(Pattern.compile("instagram\\.com/(?:reels?|tv)/([\\w-]+)", Pattern.CASE_INSENSITIVE), "ig"),
            new Rule(Pattern.compile("instagram\\.com/p/([\\w-]+)", Pattern.CASE_INSENSITIVE), "ig")
    );

    private VideoKey() {
    }

    static String of(String url) {
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
