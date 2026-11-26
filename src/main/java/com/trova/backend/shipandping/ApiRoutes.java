package com.trova.backend.shipandping;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 보안 필터에서 먼저 끝난 요청은 기능 지도의 고정 문자열만 반환한다. */
final class ApiRoutes {
    private record Entry(String route, String method, Pattern pattern) {}
    private final List<Entry> routes = new ArrayList<>();

    ApiRoutes() {
        try (InputStream stream = ApiRoutes.class.getResourceAsStream("/shipandping/feature-map.json")) {
            for (JsonNode row : JsonMapper.builder().build().readTree(stream)) {
                String route = row.get("route").asString();
                StringBuilder regex = new StringBuilder();
                for (String segment : route.substring(1).split("/")) {
                    regex.append('/').append(segment.equals(":id") ? "[^/]+" : Pattern.quote(segment));
                }
                routes.add(new Entry(route, row.get("method").asString(), Pattern.compile(regex.toString())));
            }
            // 고정 경로 /places/search가 /places/:id보다 먼저 매칭되어야 한다.
            routes.sort(Comparator.comparingLong(e -> e.route().chars().filter(c -> c == ':').count()));
        } catch (IOException e) {
            throw new IllegalStateException("기능 지도 읽기 실패", e);
        }
    }

    String lookup(String path, String method) {
        for (Entry entry : routes) {
            if (entry.method().equals(method) && entry.pattern().matcher(path).matches()) return entry.route();
        }
        return "/api/:id";
    }
}
