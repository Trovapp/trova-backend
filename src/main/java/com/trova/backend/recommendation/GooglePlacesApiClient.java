package com.trova.backend.recommendation;

public interface GooglePlacesApiClient {
    GooglePlacesNearbySearchResponse searchNearby(double latitude, double longitude, double radiusMeters);
    GooglePlacesNearbySearchResponse searchNearby(double latitude, double longitude, double radiusMeters, String includedType);
    GooglePlacesNearbySearchResponse searchText(String query);
    GooglePlacesDetailsResponse getDetails(String googlePlaceId);
}
