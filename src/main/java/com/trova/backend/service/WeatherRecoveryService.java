package com.trova.backend.service;

import com.trova.backend.config.ClockConfig;
import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Notification;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.pipeline.PlaceTag;
import com.trova.backend.pipeline.PlaceTaggingRunner;
import com.trova.backend.push.RainNotificationCreatedEvent;
import com.trova.backend.repository.NotificationRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.weather.OpenWeatherApiClient;
import com.trova.backend.weather.OpenWeatherForecastResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

/**
 * 일차(Itinerary)에 실외 장소가 있고, 그 날 강수확률이 높으면 인앱 알림을 만든다.
 * Plan B와 달리 푸시/스트리밍 없이 폴링 전용 인앱 알림만 만든다(0-1 원칙).
 */
@Service
public class WeatherRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(WeatherRecoveryService.class);

    // Plan B에서 그대로 가져온 값 — Trova 실사용 데이터로 재검증한 적 없음(0-5 원칙).
    private static final double RAIN_PROBABILITY_THRESHOLD = 0.5;

    private final TripPlaceRepository tripPlaceRepository;
    private final PlaceTaggingRunner placeTaggingRunner;
    private final OpenWeatherApiClient openWeatherApiClient;
    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher events;

    public WeatherRecoveryService(
            TripPlaceRepository tripPlaceRepository,
            PlaceTaggingRunner placeTaggingRunner,
            OpenWeatherApiClient openWeatherApiClient,
            NotificationRepository notificationRepository,
            ApplicationEventPublisher events
    ) {
        this.tripPlaceRepository = tripPlaceRepository;
        this.placeTaggingRunner = placeTaggingRunner;
        this.openWeatherApiClient = openWeatherApiClient;
        this.notificationRepository = notificationRepository;
        this.events = events;
    }

    /** 날씨 확인 결과 — 왜 알림을 만들었는지·안 만들었는지 하나만(#202). */
    public enum Outcome { NOTIFIED, NO_DATE, ALREADY_NOTIFIED, NO_PLACES, NO_OUTDOOR, NO_FORECAST, NO_RAIN }

    public record Check(Outcome outcome, Notification notification, String message) { }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M월 d일(E)", Locale.KOREAN);

    /** 스케줄러용 — 새로 만든 알림만. */
    public Optional<Notification> checkAndNotify(Itinerary itinerary) {
        return Optional.ofNullable(check(itinerary).notification());
    }

    /** 사용자가 [날씨 확인]을 눌렀을 때 — 결과와 이유를 사람이 읽는 문장으로. 예전엔 이유 셋을 한 문장에 뭉뚱그렸다. */
    public Check check(Itinerary itinerary) {
        if (itinerary.getDate() == null) {
            return new Check(Outcome.NO_DATE, null, "날짜를 정하면 날씨를 확인할 수 있어요.");
        }
        if (notificationRepository.findByItinerary(itinerary).isPresent()) {
            // 이미 이 일차에 대해 알림을 만든 적 있음 — 중복 방지
            return new Check(Outcome.ALREADY_NOTIFIED, null, "이미 비 소식 알림이 있어요. 알림에서 실내 대안을 볼 수 있어요.");
        }

        List<TripPlace> places = tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary);
        if (places.isEmpty()) {
            return new Check(Outcome.NO_PLACES, null, "이날 일정에 장소가 없어요.");
        }

        ensureTagged(places);

        List<TripPlace> outdoor = places.stream()
                .filter(p -> "OUTDOOR".equals(p.getSpace()))
                .filter(p -> p.getLatitude() != null && p.getLongitude() != null)
                .toList();
        if (outdoor.isEmpty()) {
            return new Check(Outcome.NO_OUTDOOR, null, "이날 일정은 모두 실내라 비가 와도 괜찮아요.");
        }

        TripPlace reference = outdoor.get(0);
        OpenWeatherForecastResponse forecast =
                openWeatherApiClient.forecast(reference.getLatitude(), reference.getLongitude());
        OptionalDouble pop = maxPopForDate(forecast, itinerary.getDate());
        if (pop.isEmpty()) {
            return new Check(Outcome.NO_FORECAST, null, "아직 이날 예보가 없어요. 5일 안으로 다가오면 다시 확인해 주세요.");
        }
        double maxPop = pop.getAsDouble();
        if (maxPop < RAIN_PROBABILITY_THRESHOLD) {
            return new Check(Outcome.NO_RAIN, null,
                    String.format(Locale.ROOT, "이날은 비 소식이 없어요(강수확률 최고 %.0f%%).", maxPop * 100));
        }

        Notification notification = new Notification(
                itinerary.getTrip().getUser(), itinerary, "비 소식이 있어요",
                String.format(Locale.ROOT, "%s 강수확률 %.0f%%예요. %s 근처 실내 대안을 확인해보세요.",
                        DATE.format(itinerary.getDate()), maxPop * 100, reference.getPlaceName()),
                maxPop, reference.getId());
        Notification saved = notificationRepository.save(notification);
        // 새 비 알림은 웹 푸시(#185)로도 보낸다 — 리스너가 엔티티를 다시 읽지 않게 문구에 쓸 값을 담는다.
        Trip trip = itinerary.getTrip();
        try {
            events.publishEvent(new RainNotificationCreatedEvent(trip.getUser().getId(), trip.getId(), itinerary.getId(),
                    reference.getId(), trip.getTitle(), itinerary.getDate(), maxPop, reference.getPlaceName()));
        } catch (RuntimeException e) {
            // 푸시를 못 맡겨도 앱 안 알림은 이미 저장됐다 — 날씨 확인 결과를 실패로 만들지 않는다.
            log.warn("비 알림 {} 푸시를 맡기지 못함: {}", saved.getId(), e.getClass().getSimpleName());
        }
        return new Check(Outcome.NOTIFIED, saved, "비 소식이 있어 알림을 만들었어요. 알림에서 실내 대안을 볼 수 있어요.");
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

    /**
     * dt_txt는 UTC 문자열이라 한국 날짜와 바로 비교하면 D일 09시 ~ D+1일 06시 예보를 보게 된다(#39).
     * dt(유닉스 시각)를 한국 시간으로 바꿔 그 날짜의 예보만 고른다.
     */
    /** 그날 예보 중 가장 높은 강수확률 — 예보 범위(5일) 밖이라 그날 항목이 없으면 비어 있다. */
    private OptionalDouble maxPopForDate(OpenWeatherForecastResponse forecast, LocalDate date) {
        if (forecast.list() == null) {
            return OptionalDouble.empty();
        }
        return forecast.list().stream()
                .filter(entry -> Instant.ofEpochSecond(entry.dt()).atZone(ClockConfig.SERVICE_ZONE).toLocalDate().equals(date))
                .mapToDouble(entry -> entry.pop() != null ? entry.pop() : 0.0)
                .max();
    }
}
