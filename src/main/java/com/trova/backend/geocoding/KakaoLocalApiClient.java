package com.trova.backend.geocoding;

public interface KakaoLocalApiClient {
    KakaoKeywordSearchResponse searchKeyword(String query);

    /** 주소 문자열 → 좌표(#61). */
    KakaoAddressSearchResponse searchAddress(String query);

    /** 좌표(x=경도, y=위도) 반경 안에서만 키워드 검색(#61). */
    KakaoKeywordSearchResponse searchKeywordNear(String query, double x, double y, int radiusMeters);
}
