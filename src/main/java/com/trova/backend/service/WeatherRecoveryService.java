package com.trova.backend.service;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Notification;
import com.trova.backend.entity.NotificationAlternative;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.geocoding.KakaoKeywordSearchResponse;
import com.trova.backend.geocoding.KakaoLocalApiClient;
import com.trova.backend.pipeline.PlaceTag;
import com.trova.backend.pipeline.PlaceTaggingRunner;
import com.trova.backend.repository.NotificationRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.weather.OpenWeatherApiClient;
import com.trova.backend.weather.OpenWeatherForecastResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 일차(Itinerary)에 실외 장소가 있고, 그 날 강수확률이 높으면 인앱 알림을 만든다.
 * Plan B와 달리 푸시/스트리밍 없이 폴링 전용 인앱 알림만 만든다(0-1 원칙).
 */
@Service
public class WeatherRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(WeatherRecoveryService.class);
    private static final DateTimeFormatter DT_TEXT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Plan B에서 그대로 가져온 값 — Trova 실사용 데이터로 재검증한 적 없음(0-5 원칙).
    private static final double RAIN_PROBABILITY_THRESHOLD = 0.5;
    private static final int MAX_ALTERNATIVES = 3;

    private final TripPlaceRepository tripPlaceRepository;
    private final PlaceTaggingRunner placeTaggingRunner;
    private final OpenWeatherApiClient openWeatherApiClient;
    private final KakaoLocalApiClient kakaoLocalApiClient;
    private final NotificationRepository notificationRepository;

    public WeatherRecoveryService(
            TripPlaceRepository tripPlaceRepository,
            PlaceTaggingRunner placeTaggingRunner,
            OpenWeatherApiClient openWeatherApiClient,
            KakaoLocalApiClient kakaoLocalApiClient,
            NotificationRepository notificationRepository
    ) {
        this.tripPlaceRepository = tripPlaceRepository;
        this.placeTaggingRunner = placeTaggingRunner;
        this.openWeatherApiClient = openWeatherApiClient;
        this.kakaoLocalApiClient = kakaoLocalApiClient;
        this.notificationRepository = notificationRepository;
    }

    public Optional<Notification> checkAndNotify(Itinerary itinerary) {
        if (itinerary.getDate() == null) {
            return Optional.empty();
        }
        if (notificationRepository.findByItinerary(itinerary).isPresent()) {
            return Optional.empty(); // 이미 이 일차에 대해 알림을 만든 적 있음 — 중복 방지
        }

        List<TripPlace> places = tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary);
        if (places.isEmpty()) {
            return Optional.empty();
        }

        ensureTagged(places);

        List<TripPlace> outdoor = places.stream()
                .filter(p -> "OUTDOOR".equals(p.getSpace()))
                .filter(p -> p.getLatitude() != null && p.getLongitude() != null)
                .toList();
        if (outdoor.isEmpty()) {
            return Optional.empty();
        }

        TripPlace reference = outdoor.get(0);
        OpenWeatherForecastResponse forecast =
                openWeatherApiClient.forecast(reference.getLatitude(), reference.getLongitude());
        double maxPop = maxPopForDate(forecast, itinerary.getDate());
        if (maxPop < RAIN_PROBABILITY_THRESHOLD) {
            return Optional.empty();
        }

        List<NotificationAlternative> alternatives = findIndoorAlternatives(reference);

        Notification notification = new Notification(
                itinerary.getTrip().getUser(), itinerary, "비 소식이 있어요",
                String.format(
                        "%d일차(%s)에 강수확률 %.0f%%예요. %s 근처 실내 대안을 확인해보세요.",
                        itinerary.getDay(), itinerary.getDate(), maxPop * 100, reference.getPlaceName()),
                maxPop, alternatives);
        return Optional.of(notificationRepository.save(notification));
    }

    private void ensureTagged(List<TripPlace> places) {
        List<TripPlace> needsTagging = places.stream().filter(p -> p.getSpace() == null).toList();
        if (needsTagging.isEmpty()) {
            return;
        }

        List<PlaceTaggingRunner.TagCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < needsTagging.size(); i++) {
            TripPlace p = needsTagging.get(i);
            candidates.add(new PlaceTaggingRunner.TagCandidate(i, p.getPlaceName(), p.getCategory(), null, null, null));
        }

        List<PlaceTag> tags = placeTaggingRunner.run(candidates, System.nanoTime());
        Map<Integer, PlaceTag> tagByIndex = tags.stream().collect(Collectors.toMap(PlaceTag::index, t -> t));

        for (int i = 0; i < needsTagging.size(); i++) {
            PlaceTag tag = tagByIndex.get(i);
            if (tag != null) {
                needsTagging.get(i).applySpace(tag.space());
            }
        }
        tripPlaceRepository.saveAll(needsTagging);
    }

    private double maxPopForDate(OpenWeatherForecastResponse forecast, LocalDate date) {
        if (forecast.list() == null) {
            return 0.0;
        }
        return forecast.list().stream()
                .filter(entry -> {
                    try {
                        return java.time.LocalDateTime.parse(entry.dtText(), DT_TEXT_FORMAT).toLocalDate().equals(date);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .mapToDouble(entry -> entry.pop() != null ? entry.pop() : 0.0)
                .max()
                .orElse(0.0);
    }

    /**
     * 카카오 키워드 검색으로 실내 대안을 찾는다 — 구글 Places 카탈로그와 섞지 않고
     * (0-1: provider 혼용 방지), 영상 파이프라인과 같은 카카오 기반으로 일관되게 유지.
     * 정확한 반경 검색이 아니라 지역명 기반 키워드 검색이라 근사치다(기존 지오코딩
     * 폴백과 동일한 수준의 정밀도 — MVP 범위).
     */
    private List<NotificationAlternative> findIndoorAlternatives(TripPlace reference) {
        try {
            String region = reference.getRegion() != null ? reference.getRegion() : "";
            KakaoKeywordSearchResponse response = kakaoLocalApiClient.searchKeyword((region + " 실내 명소").trim());
            if (response == null || response.documents() == null) {
                return List.of();
            }
            return response.documents().stream()
                    .limit(MAX_ALTERNATIVES)
                    .map(doc -> new NotificationAlternative(
                            doc.placeName(), doc.addressName(),
                            doc.y() != null ? Double.parseDouble(doc.y()) : null,
                            doc.x() != null ? Double.parseDouble(doc.x()) : null))
                    .toList();
        } catch (Exception e) {
            log.warn("실내 대안 검색 실패 — 대안 없이 알림만 생성", e);
            return List.of();
        }
    }
}
