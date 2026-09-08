package com.trova.backend.service;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Notification;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.pipeline.PlaceTag;
import com.trova.backend.pipeline.PlaceTaggingRunner;
import com.trova.backend.repository.NotificationRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.weather.OpenWeatherApiClient;
import com.trova.backend.weather.OpenWeatherForecastResponse;
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

    private static final DateTimeFormatter DT_TEXT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Plan B에서 그대로 가져온 값 — Trova 실사용 데이터로 재검증한 적 없음(0-5 원칙).
    private static final double RAIN_PROBABILITY_THRESHOLD = 0.5;

    private final TripPlaceRepository tripPlaceRepository;
    private final PlaceTaggingRunner placeTaggingRunner;
    private final OpenWeatherApiClient openWeatherApiClient;
    private final NotificationRepository notificationRepository;

    public WeatherRecoveryService(
            TripPlaceRepository tripPlaceRepository,
            PlaceTaggingRunner placeTaggingRunner,
            OpenWeatherApiClient openWeatherApiClient,
            NotificationRepository notificationRepository
    ) {
        this.tripPlaceRepository = tripPlaceRepository;
        this.placeTaggingRunner = placeTaggingRunner;
        this.openWeatherApiClient = openWeatherApiClient;
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

        Notification notification = new Notification(
                itinerary.getTrip().getUser(), itinerary, "비 소식이 있어요",
                String.format(
                        "%d일차(%s)에 강수확률 %.0f%%예요. %s 근처 실내 대안을 확인해보세요.",
                        itinerary.getDay(), itinerary.getDate(), maxPop * 100, reference.getPlaceName()),
                maxPop, reference.getId());
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
}
