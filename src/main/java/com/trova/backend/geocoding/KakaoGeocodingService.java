package com.trova.backend.geocoding;

import com.trova.backend.service.ApiCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class KakaoGeocodingService {

    private static final Logger log = LoggerFactory.getLogger(KakaoGeocodingService.class);

    private final KakaoLocalApiClient kakaoLocalApiClient;
    private final ApiCallLogService apiCallLogService;

    public KakaoGeocodingService(KakaoLocalApiClient kakaoLocalApiClient, ApiCallLogService apiCallLogService) {
        this.kakaoLocalApiClient = kakaoLocalApiClient;
        this.apiCallLogService = apiCallLogService;
    }

    public GeocodingResult geocode(
            List<String> nameCandidates, String region, Set<String> usedCoordinateKeys, Long jobId
    ) {
        boolean hasRegion = region != null && !region.isBlank();

        // STT/화면 텍스트 오인식으로 name이 정확히 매칭 안 될 수 있음 — Gemini가 함께
        // 제안한 대안 철자 후보들을 순서대로 시도해서, 실제 카카오 DB에 존재하는(=검색
        // 결과가 나오는) 첫 후보를 채택한다. 후보를 지어내는 게 아니라 실존 여부를
        // 카카오 검색으로 검증하는 것이므로, 다 실패해도 지금보다 나빠지진 않는다.
        for (String name : nameCandidates) {
            GeocodingResult result = search(hasRegion ? region + " " + name : name, jobId);
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
        GeocodingResult fallback = search(region, jobId);
        if (fallback.latitude() == null) {
            return GeocodingResult.empty();
        }

        // region-only 검색은 "이 지역의 대표 지점 하나"를 반환할 뿐이라, 같은 영상에서
        // 이미 확정된 다른 장소와 우연히 같은 지점으로 귀결될 수 있다(예: "전주"만으로
        // 검색했더니 이 영상에 이미 있는 "전주한옥마을"이 1등으로 나오는 경우). 그 경우
        // 틀린 좌표로 다른 장소를 가리는 것보다 좌표 없이 남기는 게 낫다.
        if (usedCoordinateKeys.contains(fallback.coordinateKey())) {
            log.warn(
                    "region 폴백 결과가 같은 영상의 다른 장소와 좌표가 겹쳐 폐기합니다"
                            + "(candidates={}, region={}, lat={}, lng={})",
                    nameCandidates, region, fallback.latitude(), fallback.longitude());
            return GeocodingResult.empty();
        }

        return GeocodingResult.coordinatesOnly(fallback.latitude(), fallback.longitude());
    }

    // region-only 폴백까지 포함해 "선택 재검토" 대상으로 넘길 대안 후보 개수 상한
    // (1등 제외, 최대 이만큼만) — 토큰 절약을 위해 상위 몇 개만 본다.
    private static final int MAX_ALTERNATIVE_CANDIDATES = 4;

    private GeocodingResult search(String query, Long jobId) {
        long start = System.currentTimeMillis();
        try {
            KakaoKeywordSearchResponse response = kakaoLocalApiClient.searchKeyword(query);
            apiCallLogService.record(
                    "kakao", "keyword_search", jobId, System.currentTimeMillis() - start,
                    true, null, null, null, null);
            if (response == null || response.documents() == null || response.documents().isEmpty()) {
                return GeocodingResult.empty();
            }
            List<KakaoKeywordSearchResponse.Document> documents = response.documents();
            KakaoKeywordSearchResponse.Document first = documents.get(0);
            List<KakaoKeywordSearchResponse.Document> alternatives = documents.size() > 1
                    ? documents.subList(1, Math.min(documents.size(), 1 + MAX_ALTERNATIVE_CANDIDATES))
                    : List.of();
            return new GeocodingResult(
                    Double.parseDouble(first.y()), Double.parseDouble(first.x()), first.placeName(),
                    first.phone(), first.addressName(), first.roadAddressName(),
                    first.categoryName(), first.placeUrl(), alternatives);
        } catch (Exception e) {
            apiCallLogService.record(
                    "kakao", "keyword_search", jobId, System.currentTimeMillis() - start,
                    false, e.getMessage(), null, null, null);
            log.warn("카카오 지오코딩 실패(query={}) — 좌표 없이 저장합니다", query, e);
            return GeocodingResult.empty();
        }
    }
}
