package com.trova.backend.controller;

import com.trova.backend.entity.SourcePlatform;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 제출된 링크에서 영상 ID만 뽑아 정식 주소로 다시 만든다(#49).
 * 호스트만 검사하면 youtube.com/redirect?q=<임의 주소> 같은 링크가 통과해 yt-dlp가 리다이렉트를 따라
 * 서버 내부 주소(클라우드 메타데이터, 내부 포트 등)로 요청하게 만들 수 있었다. 사용자 입력은 yt-dlp까지
 * 그대로 가지 않고, 허용된 문자로만 된 영상 ID를 끼워 넣은 정식 주소만 넘어간다.
 */
public record ShareUrl(SourcePlatform platform, String canonicalUrl) {

    private static final Set<String> YOUTUBE_HOSTS =
            Set.of("youtube.com", "www.youtube.com", "m.youtube.com",
                    "youtube-nocookie.com", "www.youtube-nocookie.com");
    private static final String YOUTU_BE = "youtu.be";
    private static final Set<String> INSTAGRAM_HOSTS =
            Set.of("instagram.com", "www.instagram.com", "m.instagram.com");

    // 유튜브 영상 ID·인스타 shortcode 문자만 허용한다 — 정식 주소에 그대로 끼워 넣으므로 경로·쿼리를 바꿀 수 있는
    // 문자(/ ? & % 등)가 들어오면 안 된다.
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public static Optional<ShareUrl> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme();
        if (scheme == null || !Set.of("http", "https").contains(scheme.toLowerCase(Locale.ROOT))
                || uri.getRawUserInfo() != null || uri.getHost() == null) {
            return Optional.empty();
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        List<String> segments = uri.getRawPath() == null ? List.of()
                : Arrays.stream(uri.getRawPath().split("/")).filter(s -> !s.isEmpty()).toList();

        if (host.equals(YOUTU_BE)) {
            return segments.isEmpty() ? Optional.empty() : youtubeWatch(segments.get(0));
        }
        if (YOUTUBE_HOSTS.contains(host)) {
            if (segments.size() == 1 && segments.get(0).equals("watch")) {
                return youtubeWatch(queryParam(uri.getRawQuery(), "v"));
            }
            if (segments.size() >= 2) {
                String id = segments.get(1);
                return switch (segments.get(0)) {
                    case "shorts" -> of(SourcePlatform.YOUTUBE, id, "https://www.youtube.com/shorts/" + id);
                    case "live", "embed" -> youtubeWatch(id);
                    default -> Optional.empty();
                };
            }
            return Optional.empty();
        }
        if (INSTAGRAM_HOSTS.contains(host) && segments.size() >= 2) {
            String id = segments.get(1);
            return switch (segments.get(0)) {
                case "reel", "reels" -> of(SourcePlatform.INSTAGRAM, id, "https://www.instagram.com/reel/" + id + "/");
                case "p" -> of(SourcePlatform.INSTAGRAM, id, "https://www.instagram.com/p/" + id + "/");
                case "tv" -> of(SourcePlatform.INSTAGRAM, id, "https://www.instagram.com/tv/" + id + "/");
                default -> Optional.empty();
            };
        }
        return Optional.empty();
    }

    private static Optional<ShareUrl> youtubeWatch(String id) {
        return of(SourcePlatform.YOUTUBE, id, "https://www.youtube.com/watch?v=" + id);
    }

    private static Optional<ShareUrl> of(SourcePlatform platform, String id, String canonicalUrl) {
        if (id == null || !VIDEO_ID.matcher(id).matches()) {
            return Optional.empty();
        }
        return Optional.of(new ShareUrl(platform, canonicalUrl));
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.startsWith(name + "=")) {
                return pair.substring(name.length() + 1);
            }
        }
        return null;
    }
}
