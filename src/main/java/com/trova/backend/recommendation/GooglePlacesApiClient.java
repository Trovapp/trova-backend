package com.trova.backend.recommendation;

public interface GooglePlacesApiClient {
    GooglePlacesNearbySearchResponse searchNearby(double latitude, double longitude, double radiusMeters);
    GooglePlacesNearbySearchResponse searchText(String query);
    GooglePlacesDetailsResponse getDetails(String googlePlaceId);
}
