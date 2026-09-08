package com.trova.backend.congestion;

import java.util.Set;

/**
 * 서울시 "실시간 도시데이터"가 커버하는 장소명 목록의 일부(자주 여행지로 쓰이는
 * 30곳만 우선 등록). 전체 116~121곳 목록은 서울 열린데이터광장 공식 문서에
 * 있고, 필요해지면 이 Set에 추가하면 된다 — 코드 구조 변경 없음.
 */
public final class SeoulCongestionAreaCache {

    private static final Set<String> KNOWN_AREAS = Set.of(
            "광화문·덕수궁", "명동 관광특구", "이태원 관광특구", "동대문 관광특구",
            "잠실 관광특구", "강남역", "홍대 관광특구", "경복궁", "북촌한옥마을",
            "인사동", "여의도한강공원", "반포한강공원", "뚝섬한강공원", "잠실한강공원",
            "노량진", "남산공원", "서울숲공원", "청계천", "종로·청계 관광특구",
            "가로수길", "압구정로데오거리", "성수카페거리", "익선동", "서촌", "삼청동",
            "롯데월드타워 및 롯데월드몰", "DDP(동대문디자인플라자)", "여의도", "신촌·이대", "건대입구"
    );

    private SeoulCongestionAreaCache() {
    }

    public static boolean isKnownArea(String placeName) {
        return placeName != null && KNOWN_AREAS.contains(placeName.trim());
    }
}
