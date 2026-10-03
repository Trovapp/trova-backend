package com.trova.backend.recommendation;

public interface GooglePlacesApiClient {
    GooglePlacesNearbySearchResponse searchNearby(double latitude, double longitude, double radiusMeters);
    GooglePlacesNearbySearchResponse searchNearby(double latitude, double longitude, double radiusMeters, String includedType);
    GooglePlacesNearbySearchResponse searchText(String query);
    GooglePlacesDetailsResponse getDetails(String googlePlaceId);
    // 전화번호만 요청한다(Place Details Enterprise SKU) — 리뷰 요약이 이미 있는 장소에 번호를 채울 때 쓴다(#99).
    GooglePlacesDetailsResponse getPhone(String googlePlaceId);
}
