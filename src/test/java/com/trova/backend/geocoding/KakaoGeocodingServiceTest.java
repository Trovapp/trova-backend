package com.trova.backend.geocoding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KakaoGeocodingServiceTest {

    @Mock
    private KakaoLocalApiClient kakaoLocalApiClient;

    @InjectMocks
    private KakaoGeocodingService kakaoGeocodingService;

    @Test
    void 검색_결과가_있으면_좌표와_상세정보를_함께_반환한다() {
        when(kakaoLocalApiClient.searchKeyword("부산 해운대")).thenReturn(
                new KakaoKeywordSearchResponse(List.of(
                        new KakaoKeywordSearchResponse.Document(
                                "해운대해수욕장", "129.160384", "35.158698", "051-749-4062",
                                "부산 해운대구 우동", "부산 해운대구 해운대해변로 264", "관광,명소 > 해수욕장",
                                "http://place.map.kakao.com/8achiudz")
                )));

        GeocodingResult result = kakaoGeocodingService.geocode(List.of("해운대"), "부산", Set.of());

        assertThat(result.latitude()).isEqualTo(35.158698);
        assertThat(result.longitude()).isEqualTo(129.160384);
        assertThat(result.matchedName()).isEqualTo("해운대해수욕장");
        assertThat(result.phone()).isEqualTo("051-749-4062");
        assertThat(result.address()).isEqualTo("부산 해운대구 우동");
        assertThat(result.roadAddress()).isEqualTo("부산 해운대구 해운대해변로 264");
        assertThat(result.kakaoCategoryName()).isEqualTo("관광,명소 > 해수욕장");
        assertThat(result.kakaoPlaceUrl()).isEqualTo("http://place.map.kakao.com/8achiudz");
    }

    @Test
    void 정확_매칭과_region_폴백_둘_다_없으면_빈_결과를_반환한다() {
        when(kakaoLocalApiClient.searchKeyword("어딘가 없는곳")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));
        when(kakaoLocalApiClient.searchKeyword("어딘가")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));

        GeocodingResult result = kakaoGeocodingService.geocode(List.of("없는곳"), "어딘가", Set.of());

        assertThat(result.latitude()).isNull();
        assertThat(result.longitude()).isNull();
        assertThat(result.matchedName()).isNull();
    }

    @Test
    void 정확_매칭에_실패하면_region만으로_재검색해서_좌표만_반환하고_상세정보는_채우지_않는다() {
        when(kakaoLocalApiClient.searchKeyword("부산 광알리")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));
        when(kakaoLocalApiClient.searchKeyword("부산")).thenReturn(
                new KakaoKeywordSearchResponse(List.of(
                        new KakaoKeywordSearchResponse.Document(
                                "부산광역시", "129.075642", "35.179554", "051-000-0000",
                                "부산 연제구", "부산 연제구 중앙대로", "지역시설 > 관공서", "http://place.map.kakao.com/x")
                )));

        GeocodingResult result = kakaoGeocodingService.geocode(List.of("광알리"), "부산", Set.of());

        assertThat(result.latitude()).isEqualTo(35.179554);
        assertThat(result.longitude()).isEqualTo(129.075642);
        assertThat(result.matchedName()).isNull();
        assertThat(result.phone()).isNull();
        assertThat(result.address()).isNull();
        assertThat(result.roadAddress()).isNull();
        assertThat(result.kakaoCategoryName()).isNull();
        assertThat(result.kakaoPlaceUrl()).isNull();
    }

    @Test
    void region이_없으면_폴백_없이_한_번만_검색한다() {
        when(kakaoLocalApiClient.searchKeyword("없는곳")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));

        GeocodingResult result = kakaoGeocodingService.geocode(List.of("없는곳"), null, Set.of());

        assertThat(result.latitude()).isNull();
        assertThat(result.longitude()).isNull();
    }

    @Test
    void 클라이언트가_예외를_던지면_region_폴백을_시도한다() {
        when(kakaoLocalApiClient.searchKeyword("장애 지역"))
                .thenThrow(new RuntimeException("카카오 API 오류"));
        when(kakaoLocalApiClient.searchKeyword("장애")).thenReturn(
                new KakaoKeywordSearchResponse(List.of(
                        new KakaoKeywordSearchResponse.Document(
                                "장애", "127.0", "37.0", null, null, null, null, null)
                )));

        GeocodingResult result = kakaoGeocodingService.geocode(List.of("지역"), "장애", Set.of());

        assertThat(result.latitude()).isEqualTo(37.0);
        assertThat(result.longitude()).isEqualTo(127.0);
    }

    @Test
    void 첫_번째_후보가_실패해도_두_번째_후보로_매칭되면_확인된_이름을_반환한다() {
        when(kakaoLocalApiClient.searchKeyword("인천 하늘기")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));
        when(kakaoLocalApiClient.searchKeyword("인천 하늘길")).thenReturn(
                new KakaoKeywordSearchResponse(List.of(
                        new KakaoKeywordSearchResponse.Document(
                                "하늘길", "126.7", "37.4", null, null, null, null, null)
                )));

        GeocodingResult result = kakaoGeocodingService.geocode(List.of("하늘기", "하늘길"), "인천", Set.of());

        assertThat(result.latitude()).isEqualTo(37.4);
        assertThat(result.longitude()).isEqualTo(126.7);
        assertThat(result.matchedName()).isEqualTo("하늘길");
    }

    @Test
    void 모든_후보가_실패하면_region만으로_재검색한다() {
        when(kakaoLocalApiClient.searchKeyword("인천 하늘기")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));
        when(kakaoLocalApiClient.searchKeyword("인천 하늘길")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));
        when(kakaoLocalApiClient.searchKeyword("인천")).thenReturn(
                new KakaoKeywordSearchResponse(List.of(
                        new KakaoKeywordSearchResponse.Document(
                                "인천광역시", "126.7", "37.45", null, null, null, null, null)
                )));

        GeocodingResult result = kakaoGeocodingService.geocode(List.of("하늘기", "하늘길"), "인천", Set.of());

        assertThat(result.latitude()).isEqualTo(37.45);
        assertThat(result.longitude()).isEqualTo(126.7);
        assertThat(result.matchedName()).isNull();
    }

    @Test
    void region_폴백_결과가_이미_사용된_좌표와_겹치면_좌표_없이_반환한다() {
        // "청연로"는 전주에 없는 도로명이라 후보 검색이 전부 실패하고, region("전주") 폴백
        // 검색의 1등 결과가 하필 이 영상에서 이미 확정된 다른 장소("전주한옥마을")와 좌표가
        // 완전히 겹치는 실제 사례를 재현한다 — 이 경우 좌표를 채택하지 말고 비워야 한다.
        when(kakaoLocalApiClient.searchKeyword("전주 청연로")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));
        when(kakaoLocalApiClient.searchKeyword("전주")).thenReturn(
                new KakaoKeywordSearchResponse(List.of(
                        new KakaoKeywordSearchResponse.Document(
                                "전주한옥마을", "127.152557001422", "35.814777443298",
                                null, null, null, null, null)
                )));

        GeocodingResult result = kakaoGeocodingService.geocode(
                List.of("청연로"), "전주", Set.of("35.814777443298,127.152557001422"));

        assertThat(result.latitude()).isNull();
        assertThat(result.longitude()).isNull();
        assertThat(result.matchedName()).isNull();
    }

    @Test
    void region_폴백_결과가_겹치지_않으면_평소처럼_좌표만_반환한다() {
        when(kakaoLocalApiClient.searchKeyword("부산 광알리")).thenReturn(
                new KakaoKeywordSearchResponse(List.of()));
        when(kakaoLocalApiClient.searchKeyword("부산")).thenReturn(
                new KakaoKeywordSearchResponse(List.of(
                        new KakaoKeywordSearchResponse.Document(
                                "부산광역시", "129.075642", "35.179554",
                                null, null, null, null, null)
                )));

        GeocodingResult result = kakaoGeocodingService.geocode(
                List.of("광알리"), "부산", Set.of("37.0,127.0"));

        assertThat(result.latitude()).isEqualTo(35.179554);
        assertThat(result.longitude()).isEqualTo(129.075642);
    }
}
