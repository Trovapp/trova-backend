package com.trova.backend.geocoding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KakaoGeocodingService {

    private static final Logger log = LoggerFactory.getLogger(KakaoGeocodingService.class);

    private final KakaoLocalApiClient kakaoLocalApiClient;

    public KakaoGeocodingService(KakaoLocalApiClient kakaoLocalApiClient) {
        this.kakaoLocalApiClient = kakaoLocalApiClient;
    }

    public GeocodingResult geocode(List<String> nameCandidates, String region) {
        boolean hasRegion = region != null && !region.isBlank();

        // STT/화면 텍스트 오인식으로 name이 정확히 매칭 안 될 수 있음 — Gemini가 함께
        // 제안한 대안 철자 후보들을 순서대로 시도해서, 실제 카카오 DB에 존재하는(=검색
        // 결과가 나오는) 첫 후보를 채택한다. 후보를 지어내는 게 아니라 실존 여부를
        // 카카오 검색으로 검증하는 것이므로, 다 실패해도 지금보다 나빠지진 않는다.
        for (String name : nameCandidates) {
            GeocodingResult result = search(hasRegion ? region + " " + name : name);
            if (result.latitude() != null) {
                return result;
            }
        }

        if (!hasRegion) {
            return GeocodingResult.empty();
        }

        // 후보를 전부 못 찾았을 때만 region만으로 재검색해서 최소한 지역 중심 좌표라도
        // 남긴다(완전 실패보다 나은 근사치). 이 결과의 matchedName은 "region" 자체에 대한
        // 검색 결과(예: "부산광역시")라 실제 장소 이름이 아니므로 절대 채택하지 않는다.
        log.info("후보 이름 전부 매칭 실패, region만으로 재검색합니다(candidates={}, region={})",
                nameCandidates, region);
        GeocodingResult fallback = search(region);
        return GeocodingResult.coordinatesOnly(fallback.latitude(), fallback.longitude());
    }

    private GeocodingResult search(String query) {
        try {
            KakaoKeywordSearchResponse response = kakaoLocalApiClient.searchKeyword(query);
            if (response == null || response.documents() == null || response.documents().isEmpty()) {
                return GeocodingResult.empty();
            }
            KakaoKeywordSearchResponse.Document first = response.documents().get(0);
            return new GeocodingResult(
                    Double.parseDouble(first.y()), Double.parseDouble(first.x()), first.placeName(),
                    first.phone(), first.addressName(), first.roadAddressName(),
                    first.categoryName(), first.placeUrl());
        } catch (Exception e) {
            log.warn("카카오 지오코딩 실패(query={}) — 좌표 없이 저장합니다", query, e);
            return GeocodingResult.empty();
        }
    }
}
