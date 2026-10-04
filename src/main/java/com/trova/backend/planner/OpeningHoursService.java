package com.trova.backend.planner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.recommendation.GooglePlacesApiClient;
import com.trova.backend.recommendation.GooglePlacesHoursResponse;
import com.trova.backend.replan.GeoUtils;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.service.ApiCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 일정 에이전트의 휴무 확인용 영업시간(#106). 영상 장소는 Google 장소와 연결돼 있지 않아서, 이름 + 좌표 근처(300m)로
 * Google Text Search를 한 번 불러 가장 가까운 결과의 regularOpeningHours를 장소에 저장한다(Enterprise SKU, 월 1,000건 무료).
 * 맞는 결과가 없거나 영업시간이 없어도 확인 시각을 남겨 같은 장소를 다시 묻지 않는다. 호출이 실패하면 남기지 않아 다음에 다시 시도한다.
 * 같은 카카오 장소(같은 가게)를 SHARE_FRESH_DAYS 안에 확인한 기록이 있으면 Google 대신 그 결과를 복사한다(#132) —
 * 사용자마다·분석마다 같은 가게를 다시 묻는 게 여행당 원가의 대부분이었다(개발 DB 조회 466회, 확인된 장소 41곳).
 */
@Service
public class OpeningHoursService {

    private static final Logger log = LoggerFactory.getLogger(OpeningHoursService.class);
    static final double MATCH_RADIUS_METERS = 300;
    // 영업시간은 바뀔 수 있어 오래된 확인은 나눠 쓰지 않는다.
    static final int SHARE_FRESH_DAYS = 90;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GooglePlacesApiClient googlePlacesApiClient;
    private final SavedPlaceRepository savedPlaceRepository;
    private final ApiCallLogService apiCallLogService;

    public OpeningHoursService(GooglePlacesApiClient googlePlacesApiClient, SavedPlaceRepository savedPlaceRepository,
                               ApiCallLogService apiCallLogService) {
        this.googlePlacesApiClient = googlePlacesApiClient;
        this.savedPlaceRepository = savedPlaceRepository;
        this.apiCallLogService = apiCallLogService;
    }

    /** 아직 확인하지 않은 장소만 Google에 묻는다. 반환: 이번에 실제로 부른 횟수(측정용). */
    public int fillMissing(List<SavedPlace> places) {
        int calls = 0;
        for (SavedPlace place : places) {
            if (place.getHoursCheckedAt() != null || place.getLatitude() == null || place.getLongitude() == null) {
                continue;
            }
            if (place.getKakaoPlaceUrl() != null) {
                Optional<SavedPlace> known = savedPlaceRepository.findFirstByKakaoPlaceUrlAndHoursCheckedAtAfterOrderByHoursCheckedAtDesc(
                        place.getKakaoPlaceUrl(), java.time.LocalDateTime.now().minusDays(SHARE_FRESH_DAYS));
                if (known.isPresent() && !known.get().equals(place)) {
                    place.copyOpeningHoursFrom(known.get());
                    savedPlaceRepository.save(place);
                    continue;
                }
            }
            calls++;
            long start = System.currentTimeMillis();
            try {
                GooglePlacesHoursResponse response = googlePlacesApiClient.searchTextWithHours(
                        place.getPlaceName(), place.getLatitude(), place.getLongitude(), MATCH_RADIUS_METERS);
                apiCallLogService.record("google-places", "text-search-hours", null,
                        System.currentTimeMillis() - start, true, null, null, null, null);
                Optional<GooglePlacesHoursResponse.Place> nearest = nearest(place, response);
                String periods = nearest.map(GooglePlacesHoursResponse.Place::regularOpeningHours)
                        .map(GooglePlacesHoursResponse.OpeningHours::periods)
                        .filter(p -> !p.isEmpty())
                        .map(OpeningHoursService::toJson)
                        .orElse(null);
                place.applyOpeningHours(nearest.map(GooglePlacesHoursResponse.Place::id).orElse(null), periods);
                savedPlaceRepository.save(place);
            } catch (Exception e) {
                apiCallLogService.record("google-places", "text-search-hours", null,
                        System.currentTimeMillis() - start, false, e.getMessage(), null, null, null);
                log.warn("영업시간 조회 실패(savedPlaceId={}) — 다음에 다시 시도한다", place.getId(), e);
            }
        }
        return calls;
    }

    static Optional<GooglePlacesHoursResponse.Place> nearest(SavedPlace place, GooglePlacesHoursResponse response) {
        if (response == null || response.places() == null) {
            return Optional.empty();
        }
        return response.places().stream()
                .filter(p -> p.location() != null && p.location().latitude() != null && p.location().longitude() != null)
                .filter(p -> distanceMeters(place, p) <= MATCH_RADIUS_METERS)
                .min(Comparator.comparingDouble(p -> distanceMeters(place, p)));
    }

    private static double distanceMeters(SavedPlace place, GooglePlacesHoursResponse.Place p) {
        return GeoUtils.haversineKm(place.getLatitude(), place.getLongitude(),
                p.location().latitude(), p.location().longitude()) * 1000;
    }

    private static String toJson(List<GooglePlacesHoursResponse.Period> periods) {
        try {
            return MAPPER.writeValueAsString(periods);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 그 날짜(요일)에 문을 여는지. 영업시간이 없으면 빈 값(판정 불가). close가 없는 구간은 24시간 영업(Google 규칙).
     * eval/itinerary/score.py의 open_on과 같은 규칙이다 — 측정과 기능이 같은 기준을 쓴다.
     */
    public static Optional<Boolean> isOpenOn(String periodsJson, LocalDate date) {
        if (periodsJson == null || periodsJson.isBlank()) {
            return Optional.empty();
        }
        try {
            List<GooglePlacesHoursResponse.Period> periods = MAPPER.readValue(periodsJson, new TypeReference<>() {
            });
            if (periods.isEmpty()) {
                return Optional.empty();
            }
            int googleDay = date.getDayOfWeek().getValue() % 7; // 월=1 … 일=0
            if (periods.stream().anyMatch(p -> p.close() == null)) {
                return Optional.of(true);
            }
            return Optional.of(periods.stream().anyMatch(p -> p.open() != null && p.open().day() != null
                    && p.open().day() == googleDay));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
