package com.trova.backend.geocoding;

public interface KakaoLocalApiClient {
    KakaoKeywordSearchResponse searchKeyword(String query);
}
