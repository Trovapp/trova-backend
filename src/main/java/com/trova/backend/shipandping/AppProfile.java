package com.trova.backend.shipandping;

import java.util.List;
import java.util.Map;

final class AppProfile {
    private AppProfile() {}

    // Only reviewed display strings belong here. Do not infer infrastructure from this JVM.
    static Map<String, Object> current() {
        return Map.of("language", "Java", "framework", "Spring Boot", "database", "postgres",
                "deploy", "JAR 교체 후 서비스 재시작", "rollback", "직전 백엔드 JAR 복원", "metrics", "Grafana에서 서버 지표 확인",
                "services", List.of(Map.of("feature", "장소 검색", "kind", "map", "name", "Google Places"),
                        Map.of("feature", "대화 메시지 보내기", "kind", "ai", "name", "Gemini"),
                        Map.of("feature", "공유 영상 분석 요청", "kind", "map", "name", "Kakao Local"),
                        Map.of("feature", "테스트 푸시 보내기", "kind", "other", "name", "Expo Push")));
    }
}
