package com.trova.backend.geocoding;

public record GeocodingResult(
        Double latitude, Double longitude, String matchedName,
        String phone, String address, String roadAddress,
        String kakaoCategoryName, String kakaoPlaceUrl
) {
    public static GeocodingResult empty() {
        return new GeocodingResult(null, null, null, null, null, null, null, null);
    }

    /**
     * 이름 후보가 전부 매칭 실패해 region만으로 재검색한 결과 — 좌표는 근사치로 쓸 만하지만
     * 상호명/전화번호/주소 등은 실제 이 장소를 가리키는 정보가 아니므로 채우지 않는다.
     */
    public static GeocodingResult coordinatesOnly(Double latitude, Double longitude) {
        return new GeocodingResult(latitude, longitude, null, null, null, null, null, null);
    }

    /**
     * 같은 배치(영상) 안에서 이미 확정된 좌표와의 충돌을 감지하기 위한 키.
     * 좌표(latitude)가 null인 결과에는 호출하지 않는다 — 호출 측(PlaceExtractionService)이
     * 좌표가 있는 결과만 누적 Set에 넣는다.
     */
    public String coordinateKey() {
        return latitude + "," + longitude;
    }
}
