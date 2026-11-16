package com.trova.backend.service;

import com.trova.backend.replan.GeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Notification;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SignalType;
import com.trova.backend.entity.TransportMode;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.entity.UserPreferenceSignal;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.recommendation.PlaceNameMatch;
import com.trova.backend.recommendation.PlaceSearchService;
import com.trova.backend.planner.TravelTime;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.NotificationRepository;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRegion;
import com.trova.backend.repository.TripReplanJobRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.TripShareLinkRepository;
import com.trova.backend.repository.UserPreferenceSignalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 영상 파이프라인에서 나온 SavedPlace를 Trip/Itinerary/TripPlace로 확정한다.
 * day가 배정 안 된(dayNumber == null) SavedPlace는 아직 일정 위치가 없는 상태라
 * 제외한다 — Trip 자체는 만들어지지만 그 장소는 포함되지 않는다.
 *
 * 사용자가 직접 만드는 Trip(장소 검색해서 추가하는 방식)도 이 클래스가 담당한다.
 */
@Service
public class TripService {

    private static final Logger log = LoggerFactory.getLogger(TripService.class);

    private static final Set<String> VALID_DIRECTIONS = Set.of("UP", "DOWN");
    // 찾아 넣은 장소(fitRoute)의 머무는 시간 — 사용자가 장소 시간에서 바로 고칠 수 있다.
    private static final int FIT_ROUTE_STAY_MINUTES = 60;
    private static final double SAME_PLACE_RADIUS_KM = 0.2; // 같은 이름이 이 안이면 같은 장소(지점이 다른 체인점은 보통 더 멀다)
    private static final int MIN_SUFFIX_NAME_LENGTH = 3; // "카페"처럼 짧은 이름은 끝맺음으로 비교하지 않는다(어느 "○○카페"와도 맞아 버린다)

    private final TripRepository tripRepository;
    private final TripShareLinkRepository tripShareLinkRepository;
    private final ItineraryRepository itineraryRepository;
    private final TripPlaceRepository tripPlaceRepository;
    private final PlaceRepository placeRepository;
    private final NotificationRepository notificationRepository;
    private final PlaceSearchService placeSearchService;
    private final UserPreferenceSignalRepository userPreferenceSignalRepository;
    private final PlaceEmbeddingService placeEmbeddingService;
    private final TripReplanJobRepository tripReplanJobRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final SavedPlaceRepository savedPlaceRepository;

    public TripService(
            TripRepository tripRepository,
            ItineraryRepository itineraryRepository,
            TripPlaceRepository tripPlaceRepository,
            PlaceRepository placeRepository,
            NotificationRepository notificationRepository,
            PlaceSearchService placeSearchService,
            UserPreferenceSignalRepository userPreferenceSignalRepository,
            PlaceEmbeddingService placeEmbeddingService,
            TripReplanJobRepository tripReplanJobRepository,
            ProcessingJobRepository processingJobRepository,
            SavedPlaceRepository savedPlaceRepository,
            TripShareLinkRepository tripShareLinkRepository
    ) {
        this.tripRepository = tripRepository;
        this.tripShareLinkRepository = tripShareLinkRepository;
        this.itineraryRepository = itineraryRepository;
        this.tripPlaceRepository = tripPlaceRepository;
        this.placeRepository = placeRepository;
        this.notificationRepository = notificationRepository;
        this.placeSearchService = placeSearchService;
        this.userPreferenceSignalRepository = userPreferenceSignalRepository;
        this.placeEmbeddingService = placeEmbeddingService;
        this.tripReplanJobRepository = tripReplanJobRepository;
        this.processingJobRepository = processingJobRepository;
        this.savedPlaceRepository = savedPlaceRepository;
    }

    /**
     * Trip과 그에 딸린 Itinerary/TripPlace/Notification/TripShareLink를 전부 지운다(소유자 확인 후).
     * 여러 테이블을 순서대로 지우므로 중간에 실패하면 전부 되돌린다(#41).
     */
    @Transactional
    public boolean deleteTrip(User user, Long tripId) {
        return tripRepository.findById(tripId)
                .filter(trip -> trip.getUser().getId().equals(user.getId()))
                .map(trip -> {
                    List<Itinerary> itineraries = itineraryRepository.findByTripOrderByDay(trip);
                    for (Itinerary itinerary : itineraries) {
                        notificationRepository.findByItinerary(itinerary).ifPresent(notificationRepository::delete);
                        tripPlaceRepository.deleteAll(tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary));
                    }
                    itineraryRepository.deleteAll(itineraries);
                    // 일정 재구성 작업 기록은 trip_id를 NOT NULL FK로 참조하므로 여행보다 먼저 지워야 한다
                    // (안 지우면 재구성을 한 번이라도 돌린 여행은 삭제가 FK 제약 위반으로 실패함).
                    tripReplanJobRepository.deleteAll(tripReplanJobRepository.findByTrip(trip));
                    tripShareLinkRepository.deleteByTrip(trip);
                    tripRepository.delete(trip);
                    return true;
                })
                .orElse(false);
    }

    /** 여행 목록 한 줄 — 여행과 그 여행 장소들의 지역(일차·방문 순서, 장소 수만큼). */
    public record TripSummary(Trip trip, List<String> regions) {
    }

    /**
     * 내 여행 목록과 여행별 지역(#153). 읽기 전용 트랜잭션 하나로 묶어 커넥션·영속성 컨텍스트를 한 번만 쓰고,
     * 여행 장소는 엔티티 대신 (여행 id, 지역)만 읽어 일차 수와 상관없이 쿼리 수가 같다.
     */
    @Transactional(readOnly = true)
    public List<TripSummary> listTripSummaries(User user) {
        return listTripSummaries(user.getId());
    }

    /**
     * 사용자 ID만으로 여행 목록을 만든다(ADR 0036 — 조회 API가 사용자 행을 따로 읽지 않게).
     * 트랜잭션을 걸지 않는다: 여행 값과 (여행 id, 지역) 튜플만 쓰고 지연 로딩이 없어서, 두 조회를 자동 커밋으로 보내면
     * COMMIT 왕복 한 번(운영 약 31ms)을 아낀다. 위의 User 버전은 기존 호출부를 위해 읽기 전용 트랜잭션을 유지한다.
     */
    public List<TripSummary> listTripSummaries(Long userId) {
        List<Trip> trips = tripRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (trips.isEmpty()) {
            return List.of();
        }
        Map<Long, List<String>> regionsByTrip = tripPlaceRepository.findRegionsByTripIn(trips).stream()
                .collect(Collectors.groupingBy(TripRegion::tripId,
                        Collectors.mapping(TripRegion::region, Collectors.toList())));
        return trips.stream()
                .map(t -> new TripSummary(t, regionsByTrip.getOrDefault(t.getId(), List.of())))
                .toList();
    }

    /** 사용자가 직접 새 Trip을 만든다 — 기간(startDate~endDate) 기준으로 일차를 자동 생성한다. */
    public Trip createTrip(User user, String title, LocalDate startDate, LocalDate endDate) {
        Trip trip = tripRepository.save(new Trip(user, title, startDate, endDate));
        long totalDays = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        for (int day = 1; day <= totalDays; day++) {
            itineraryRepository.save(new Itinerary(trip, day, startDate.plusDays(day - 1)));
        }
        return trip;
    }

    /**
     * 여행 날짜를 정하거나 바꾼다(#181). 시작일만 받고 종료일은 지금 일차 수로 계산해 일차마다 날짜를 채운다 —
     * 자동 초안으로 만든 여행은 날짜가 없어 날씨 확인·비 알림·다가오는/지난 여행 구분이 안 됐다.
     * 예전 날짜 기준으로 만든 비 알림은 틀린 안내가 되므로 지운다(새 날짜로 다음 날씨 검사에서 다시 만든다).
     */
    @Transactional
    public Optional<Trip> setDates(User user, Long tripId, LocalDate startDate) {
        return tripRepository.findById(tripId)
                .filter(trip -> trip.getUser().getId().equals(user.getId()))
                .map(trip -> {
                    List<Itinerary> days = itineraryRepository.findByTripOrderByDay(trip);
                    int dayCount = Math.max(1, days.size());
                    trip.applyDates(startDate, startDate.plusDays(dayCount - 1));
                    for (Itinerary itinerary : days) {
                        itinerary.applyDate(startDate.plusDays(itinerary.getDay() - 1));
                    }
                    itineraryRepository.saveAll(days);
                    notificationRepository.deleteAll(notificationRepository.findByItinerary_Trip(trip));
                    return tripRepository.save(trip);
                });
    }

    /** 여행 이름을 바꾼다(#221) — 앱 어디에도 이름을 바꿀 곳이 없었다(2026-10-18 안드로이드 QA). 본인 여행만. */
    @Transactional
    public Optional<Trip> rename(User user, Long tripId, String title) {
        return tripRepository.findById(tripId)
                .filter(trip -> trip.getUser().getId().equals(user.getId()))
                .map(trip -> {
                    trip.applyTitle(title);
                    return tripRepository.save(trip);
                });
    }

    public Optional<TripPlace> addPlaceToDay(User user, Long tripId, int day, String googlePlaceId) {
        return addPlaceToDay(user, tripId, day, googlePlaceId, false, false);
    }

    /**
     * Place 카탈로그(구글 플레이스 검색에서 이미 upsert된 장소)에서 googlePlaceId로 찾아
     * 해당 일차 맨 끝에 추가한다. 카탈로그에 없으면(검색 단계를 안 거친 잘못된 요청)
     * 아무것도 만들지 않는다.
     * fitRoute면 맨 끝 대신 이동이 가장 적게 늘어나는 자리에 넣고(#221), 그날 장소가 모두 시간이 있으면
     * 새 장소에 한 시간을 주고 새 순서대로 시간을 다시 매긴다 — 일정 초안에서 뺀 장소를 찾아 넣을 때 쓴다.
     * 같은 여행에 이미 있는 장소면 담지 않고 DuplicateTripPlaceException(#233) — 다른 날이면 allowOtherDay로 담는다.
     */
    @Transactional
    public Optional<TripPlace> addPlaceToDay(
            User user, Long tripId, int day, String googlePlaceId, boolean fitRoute, boolean allowOtherDay
    ) {
        return tripRepository.findById(tripId)
                .filter(trip -> trip.getUser().getId().equals(user.getId()))
                .flatMap(trip -> itineraryRepository.findByTripAndDay(trip, day))
                .flatMap(itinerary -> placeRepository.findByGooglePlaceId(googlePlaceId).map(place -> {
                    rejectDuplicate(itinerary, allowOtherDay, existing ->
                            googlePlaceId.equals(existing.getGooglePlaceId())
                                    || isSameNameNearby(existing, place.getName(), place.getLatitude(), place.getLongitude()));
                    List<TripPlace> siblings = tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary);
                    int index = fitRoute
                            ? RouteInsertion.bestIndex(siblings, place.getLatitude(), place.getLongitude())
                            : siblings.size();
                    for (int i = index; i < siblings.size(); i++) {
                        siblings.get(i).applyVisitOrder(i + 2);
                    }
                    TripPlace tripPlace = new TripPlace(
                            itinerary, place.getName(), null, place.getCategory(),
                            place.getLatitude(), place.getLongitude(), null, place.getAddress(),
                            index + 1, PlaceSource.NORMAL, null);
                    tripPlace.applyGooglePlaceId(place.getGooglePlaceId());
                    List<TripPlace> ordered = new ArrayList<>(siblings);
                    ordered.add(index, tripPlace);
                    if (fitRoute && !siblings.isEmpty()
                            && siblings.stream().allMatch(p -> p.getVisitStartTime() != null && p.getVisitEndTime() != null)) {
                        // 시작 시각은 다시 매길 때 정해진다 — 그날 가장 이른 시각을 넣어 두어야 출발 시각이 바뀌지 않는다.
                        LocalTime earliest = siblings.stream().map(TripPlace::getVisitStartTime).min(LocalTime::compareTo).orElseThrow();
                        tripPlace.applyDetails(earliest, earliest.plusMinutes(FIT_ROUTE_STAY_MINUTES), null, null);
                        TripDayRetimer.retime(ordered);
                    }
                    userPreferenceSignalRepository.save(new UserPreferenceSignal(user, place, SignalType.TRIP_PLACE_ADDED));
                    placeEmbeddingService.ensureEmbeddings(List.of(place));
                    tripPlaceRepository.saveAll(siblings);
                    return tripPlaceRepository.save(tripPlace);
                }));
    }

    /**
     * 영상에서 찾은 장소(SavedPlace)를 여행의 그 일차 맨 끝에 담는다(#126) — 영상 전체로 새 여행을 만들거나 여행 화면에서
     * 다시 검색하지 않아도 되게. 영상 장소의 값(이름·좌표·주소·전화)을 그대로 복사하고 외부 호출은 하지 않는다.
     * 영상 장소와 여행 모두 본인 것이어야 한다. 이미 있는 장소면 addPlaceToDay와 같이 막는다(#233).
     */
    public Optional<TripPlace> addVideoPlaceToDay(User user, Long tripId, int day, Long savedPlaceId) {
        return addVideoPlaceToDay(user, tripId, day, savedPlaceId, false);
    }

    @Transactional
    public Optional<TripPlace> addVideoPlaceToDay(User user, Long tripId, int day, Long savedPlaceId, boolean allowOtherDay) {
        Optional<SavedPlace> saved = savedPlaceRepository.findById(savedPlaceId)
                .filter(p -> p.getUser().getId().equals(user.getId()));
        if (saved.isEmpty()) {
            return Optional.empty();
        }
        SavedPlace place = saved.get();
        return tripRepository.findById(tripId)
                .filter(trip -> trip.getUser().getId().equals(user.getId()))
                .flatMap(trip -> itineraryRepository.findByTripAndDay(trip, day))
                .map(itinerary -> {
                    rejectDuplicate(itinerary, allowOtherDay, existing ->
                            place.getId().equals(existing.getSavedPlaceId())
                                    || (place.getGooglePlaceId() != null && place.getGooglePlaceId().equals(existing.getGooglePlaceId()))
                                    || isSameNameNearby(existing, place.getPlaceName(), place.getLatitude(), place.getLongitude()));
                    int nextOrder = tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary).size() + 1;
                    TripPlace tripPlace = new TripPlace(
                            itinerary, place.getPlaceName(), place.getRegion(), place.getCategory(),
                            place.getLatitude(), place.getLongitude(), place.getPhone(), place.getAddress(),
                            nextOrder, PlaceSource.VIDEO, place.getId());
                    tripPlace.applyDetails(null, null, null, place.videoNotesAsMemo()); // 영상에서 말한 내용(#134)
                    return tripPlaceRepository.save(tripPlace);
                });
    }

    /**
     * 영상 장소를 Place 카탈로그(구글 장소)에 연결한다(찜하기 #125 — 찜은 카탈로그 장소를 가리킨다).
     * 영업시간 조회로 구글 id가 이미 있으면 그것을, 없으면 여행 장소 상세(#101)와 같은 규칙으로 지역을 붙여 검색해
     * 영상 장소 좌표 3km 안의 결과만 쓴다. 본인 장소가 아니면 비우고, 연결하지 못하면 NOT_MATCHED.
     */
    @Transactional
    public Optional<SavedPlaceMatch> resolveSavedPlace(User user, Long savedPlaceId) {
        return savedPlaceRepository.findById(savedPlaceId)
                .filter(p -> p.getUser().getId().equals(user.getId()))
                .map(saved -> {
                    if (saved.getGooglePlaceId() != null) {
                        Optional<Place> known = placeRepository.findByGooglePlaceId(saved.getGooglePlaceId());
                        if (known.isPresent()) {
                            return new SavedPlaceMatch(known);
                        }
                    }
                    List<Place> candidates = placeSearchService.search(detailsSearchQuery(saved.getPlaceName(), saved.getRegion()));
                    Optional<Place> match = pickMatch(saved.getPlaceName(), saved.getLatitude(), saved.getLongitude(), candidates);
                    if (match.isEmpty()) {
                        log.info("영상 장소 찜 연결 실패 — 근처 검색 결과 없음(savedPlaceId={}, name={}, 후보 {}곳)",
                                saved.getId(), saved.getPlaceName(), candidates.size());
                    }
                    match.ifPresent(m -> userPreferenceSignalRepository.save(new UserPreferenceSignal(user, m, SignalType.VIDEO_PLACE_MATCHED)));
                    return new SavedPlaceMatch(match);
                });
    }

    /** 본인 영상 장소였을 때의 연결 결과 — place가 비면 지도에서 찾지 못한 것. */
    public record SavedPlaceMatch(Optional<Place> place) {
    }

    /**
     * 담으려는 장소가 이 여행에 이미 있으면 막는다(#233, 2026-10-21 QA — 같은 장소가 맨 끝에 시간 없이 또 들어갔다).
     * 같은 날이면 항상 막고, 다른 날이면 allowOtherDay일 때만 통과 — 숙소 근처 카페처럼 이틀 다 가는 경우가 있다.
     */
    private void rejectDuplicate(Itinerary target, boolean allowOtherDay, Predicate<TripPlace> sameAs) {
        List<TripPlace> matches = tripPlaceRepository.findByItinerary_Trip(target.getTrip()).stream()
                .filter(sameAs)
                .toList();
        Optional<TripPlace> sameDay = matches.stream()
                .filter(p -> p.getItinerary().getId().equals(target.getId()))
                .findFirst();
        if (sameDay.isPresent()) {
            throw new DuplicateTripPlaceException(true, target.getDay(), sameDay.get().getPlaceName());
        }
        if (!allowOtherDay && !matches.isEmpty()) {
            TripPlace other = matches.stream().min(Comparator.comparingInt(p -> p.getItinerary().getDay())).orElseThrow();
            throw new DuplicateTripPlaceException(false, other.getItinerary().getDay(), other.getPlaceName());
        }
    }

    /**
     * 영상 장소(구글 id 없음)와 검색 장소를 같은 곳으로 보는 기준 — 띄어쓰기·대소문자를 뺀 이름이 같거나, 한쪽이 띄어 쓴 앞말 뒤에
     * 다른 쪽 이름으로 끝나고(구글이 "대한불교조계종 진관사"처럼 앞말을 붙인다), 가까우면.
     * "진관사 연지원"처럼 뒤에 붙는 건 안쪽 시설, "더가까이"처럼 붙여 쓴 건 다른 이름이라 다른 장소.
     */
    private static boolean isSameNameNearby(TripPlace existing, String name, Double latitude, Double longitude) {
        if (name == null || existing.getPlaceName() == null || !sameName(name, existing.getPlaceName())) {
            return false;
        }
        if (latitude == null || longitude == null || existing.getLatitude() == null || existing.getLongitude() == null) {
            return true; // 좌표 없는 장소(ADR 0006)는 이름으로만 본다
        }
        return GeoUtils.haversineKm(latitude, longitude, existing.getLatitude(), existing.getLongitude()) <= SAME_PLACE_RADIUS_KM;
    }

    private static boolean sameName(String a, String b) {
        if (a.replaceAll("\\s+", "").equalsIgnoreCase(b.replaceAll("\\s+", ""))) {
            return true;
        }
        String x = a.trim().replaceAll("\\s+", " ").toLowerCase();
        String y = b.trim().replaceAll("\\s+", " ").toLowerCase();
        String shorter = x.length() <= y.length() ? x : y;
        String longer = x.length() <= y.length() ? y : x;
        return shorter.length() >= MIN_SUFFIX_NAME_LENGTH && longer.endsWith(" " + shorter);
    }

    /** 이미 이 여행에 있는 장소를 담으려 할 때. day는 이미 있는 날(sameDay면 담으려던 날과 같다). */
    public static class DuplicateTripPlaceException extends RuntimeException {
        private final boolean sameDay;
        private final int day;
        private final String placeName;

        public DuplicateTripPlaceException(boolean sameDay, int day, String placeName) {
            super(placeName + "은(는) 이미 " + day + "일차에 있어요.");
            this.sameDay = sameDay;
            this.day = day;
            this.placeName = placeName;
        }

        public boolean sameDay() {
            return sameDay;
        }

        public int day() {
            return day;
        }

        public String placeName() {
            return placeName;
        }
    }

    /** 보낸 필드만 부분적으로 갱신한다(null인 필드는 기존 값 유지 — TripPlace.applyDetails 참고). */
    public Optional<TripPlace> updateDetails(
            User user, Long tripPlaceId, LocalTime visitStartTime, LocalTime visitEndTime,
            TransportMode arrivalTransportMode, String memo
    ) {
        return tripPlaceRepository.findById(tripPlaceId)
                .filter(p -> p.getItinerary().getTrip().getUser().getId().equals(user.getId()))
                .map(place -> {
                    place.applyDetails(visitStartTime, visitEndTime, arrivalTransportMode, memo);
                    return tripPlaceRepository.save(place);
                });
    }

    /**
     * TripPlace가 가리키는 Place 카탈로그 항목을 찾는다(리뷰 요약 조회용).
     * 검색으로 추가한 장소는 이미 googlePlaceId가 있어 바로 찾고, 영상에서 자동
     * 추출된 장소(VIDEO 출처)는 googlePlaceId가 없어서 이름으로 한 번 검색해
     * 첫 매칭 결과를 카탈로그 장소로 채택하고 TripPlace에 백필한다(다음부터는
     * 재검색 없이 바로 찾음). 매칭이 하나도 없으면 빈 값을 돌려준다.
     */
    @Transactional
    public Optional<Place> resolveDetailsPlace(User user, Long tripPlaceId) {
        return tripPlaceRepository.findById(tripPlaceId)
                .filter(p -> p.getItinerary().getTrip().getUser().getId().equals(user.getId()))
                .flatMap(tripPlace -> {
                    if (tripPlace.getGooglePlaceId() != null) {
                        return placeRepository.findByGooglePlaceId(tripPlace.getGooglePlaceId());
                    }
                    // 이름만으로 찾으면 같은 이름의 다른 지역 장소가 1등일 수 있다 — 개발 DB에서 김해 "가야랜드"가 파주
                    // 가야랜드(341km)로 연결됐다(#101). 검색어에 지역을 붙이고, 여행 장소 좌표가 있으면 그 근처 결과만 연결한다.
                    List<Place> candidates = placeSearchService.search(detailsSearchQuery(tripPlace));
                    Optional<Place> nearest = pickMatch(tripPlace, candidates);
                    if (nearest.isEmpty()) {
                        log.info("여행 장소 상세 연결 실패 — 근처 검색 결과 없음(tripPlaceId={}, name={}, 후보 {}곳)",
                                tripPlace.getId(), tripPlace.getPlaceName(), candidates.size());
                        return Optional.empty();
                    }
                    Place match = nearest.get();
                    tripPlace.applyGooglePlaceId(match.getGooglePlaceId());
                    tripPlaceRepository.save(tripPlace);
                    userPreferenceSignalRepository.save(new UserPreferenceSignal(user, match, SignalType.VIDEO_PLACE_MATCHED));
                    placeEmbeddingService.ensureEmbeddings(List.of(match));
                    return Optional.of(match);
                });
    }

    // 여행 장소와 검색 결과가 이보다 멀면 다른 장소로 본다. 주소·이름으로 잡은 좌표는 보통 수십 m 안이고, 큰 시설(테마파크 등)과
    // 지역 중심으로 대신한 좌표까지 감안한 값이다. 같은 이름의 다른 지역(수십~수백 km)은 확실히 걸러진다.
    static final double MAX_MATCH_DISTANCE_KM = 3.0;

    static String detailsSearchQuery(TripPlace tripPlace) {
        return detailsSearchQuery(tripPlace.getPlaceName(), tripPlace.getRegion());
    }

    static String detailsSearchQuery(String name, String region) {
        return region == null || region.isBlank() || name.contains(region) ? name : region + " " + name;
    }

    /**
     * 여행 장소 좌표가 있으면 검색 결과 중 MAX_MATCH_DISTANCE_KM 안의 첫 결과(검색 순위 우선), 없으면 비운다.
     * 좌표가 없는 여행 장소는 확인할 방법이 없어 지역을 붙인 검색의 1등을 그대로 쓴다.
     */
    static Optional<Place> pickMatch(TripPlace tripPlace, List<Place> candidates) {
        return pickMatch(tripPlace.getPlaceName(), tripPlace.getLatitude(), tripPlace.getLongitude(), candidates);
    }

    /** 이름이 맞는 결과만 본다 — 3km 안의 첫 결과면 같은 건물·근처 다른 가게가 연결돼 그 리뷰가 요약됐다(#414). */
    static Optional<Place> pickMatch(String name, Double latitude, Double longitude, List<Place> candidates) {
        return pickMatch(latitude, longitude, candidates.stream()
                .filter(c -> PlaceNameMatch.sameBusiness(name, c.getName())).toList());
    }

    static Optional<Place> pickMatch(Double latitude, Double longitude, List<Place> candidates) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (latitude == null || longitude == null) {
            return Optional.of(candidates.get(0));
        }
        return candidates.stream()
                .filter(c -> c.getLatitude() != null && c.getLongitude() != null)
                .filter(c -> GeoUtils.haversineKm(latitude, longitude, c.getLatitude(), c.getLongitude()) <= MAX_MATCH_DISTANCE_KM)
                .findFirst();
    }

    @Transactional
    public Optional<TripPlace> replacePlace(User user, Long tripPlaceId, String googlePlaceId) {
        return tripPlaceRepository.findById(tripPlaceId)
                .filter(p -> p.getItinerary().getTrip().getUser().getId().equals(user.getId()))
                .flatMap(place -> placeRepository.findByGooglePlaceId(googlePlaceId).map(newPlace -> {
                    place.applyReplacement(newPlace);
                    // 비 알림은 교체 전 장소(예: 실외)를 기준으로 만든 것이라, 대안으로 바꿨으면 이미 대응한 것이다(#41).
                    notificationRepository.findByTripPlaceId(place.getId()).forEach(Notification::markRead);
                    userPreferenceSignalRepository.save(new UserPreferenceSignal(user, newPlace, SignalType.ALTERNATIVE_REPLACED));
                    placeEmbeddingService.ensureEmbeddings(List.of(newPlace));
                    return tripPlaceRepository.save(place);
                }));
    }

    @Transactional
    public Optional<TripPlace> insertPlaceAfter(User user, Long afterTripPlaceId, String googlePlaceId) {
        return tripPlaceRepository.findById(afterTripPlaceId)
                .filter(p -> p.getItinerary().getTrip().getUser().getId().equals(user.getId()))
                .flatMap(after -> placeRepository.findByGooglePlaceId(googlePlaceId).map(newPlace -> {
                    List<TripPlace> siblings =
                            tripPlaceRepository.findByItineraryOrderByVisitOrder(after.getItinerary());
                    for (TripPlace sibling : siblings) {
                        if (sibling.getVisitOrder() > after.getVisitOrder()) {
                            sibling.applyVisitOrder(sibling.getVisitOrder() + 1);
                            tripPlaceRepository.save(sibling);
                        }
                    }
                    TripPlace inserted = new TripPlace(
                            after.getItinerary(), newPlace.getName(), null, newPlace.getCategory(),
                            newPlace.getLatitude(), newPlace.getLongitude(), null, newPlace.getAddress(),
                            after.getVisitOrder() + 1, PlaceSource.NORMAL, null);
                    inserted.applyGooglePlaceId(newPlace.getGooglePlaceId());
                    // 빈 시간에 넣은 장소에 시간이 없어 목록 맨 끝으로 밀리고 동선 다듬기가 시간을 못 매겼다(#414) —
                    // 앞 장소가 떠난 뒤 이동 시간 다음부터 60분(다음 장소 도착 전까지)으로 넣는다. 뒤 장소 시간은 그대로 둔다.
                    if (after.getVisitEndTime() != null) {
                        LocalTime start = after.getVisitEndTime().plusMinutes(TravelTime.minutes(
                                after.getLatitude(), after.getLongitude(), newPlace.getLatitude(), newPlace.getLongitude()));
                        LocalTime end = start.plusMinutes(TripDayRetimer.DEFAULT_STAY_MINUTES);
                        TripPlace next = siblings.stream().filter(p -> p.getVisitOrder() == after.getVisitOrder() + 2)
                                .findFirst().orElse(null);
                        if (next != null && next.getVisitStartTime() != null) {
                            LocalTime latestEnd = next.getVisitStartTime().minusMinutes(TravelTime.minutes(
                                    newPlace.getLatitude(), newPlace.getLongitude(), next.getLatitude(), next.getLongitude()));
                            if (latestEnd.isAfter(start) && latestEnd.isBefore(end)) {
                                end = latestEnd;
                            }
                        }
                        if (!end.isBefore(start)) {
                            inserted.applyDetails(start, end, null, null);
                        }
                    }
                    userPreferenceSignalRepository.save(new UserPreferenceSignal(user, newPlace, SignalType.GAP_INSERTED));
                    placeEmbeddingService.ensureEmbeddings(List.of(newPlace));
                    return tripPlaceRepository.save(inserted);
                }));
    }

    @Transactional
    public boolean removePlace(User user, Long tripPlaceId) {
        return tripPlaceRepository.findById(tripPlaceId)
                .filter(p -> p.getItinerary().getTrip().getUser().getId().equals(user.getId()))
                .map(p -> {
                    // 지운 장소를 가리키는 알림이 남으면 배너를 눌렀을 때 대안 찾기가 404가 된다(#41).
                    // 읽음 처리가 아니라 지우는 이유: 일차당 알림은 1개라, 지워야 그 일차의 남은 실외 장소를
                    // 다음 날씨 검사에서 다시 판단할 수 있다.
                    notificationRepository.deleteAll(notificationRepository.findByTripPlaceId(p.getId()));
                    tripPlaceRepository.delete(p);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public Optional<TripPlace> reorderPlace(User user, Long tripPlaceId, String direction) {
        if (!VALID_DIRECTIONS.contains(direction)) {
            return Optional.empty();
        }
        return tripPlaceRepository.findById(tripPlaceId)
                .filter(p -> p.getItinerary().getTrip().getUser().getId().equals(user.getId()))
                .map(place -> {
                    List<TripPlace> siblings =
                            tripPlaceRepository.findByItineraryOrderByVisitOrder(place.getItinerary());

                    int index = -1;
                    for (int i = 0; i < siblings.size(); i++) {
                        if (siblings.get(i).getId().equals(place.getId())) {
                            index = i;
                            break;
                        }
                    }
                    int swapIndex = "UP".equals(direction) ? index - 1 : index + 1;
                    if (index < 0 || swapIndex < 0 || swapIndex >= siblings.size()) {
                        return place; // 경계값 — no-op
                    }

                    TripPlace neighbor = siblings.get(swapIndex);
                    swapVisitOrder(place, neighbor);
                    // 순서만 바꾸면 시간이 거꾸로 남는다(#175) — 새 순서대로 시간을 다시 매긴다.
                    List<TripPlace> reordered = new ArrayList<>(siblings);
                    reordered.sort(Comparator.comparingInt(TripPlace::getVisitOrder));
                    TripDayRetimer.retime(reordered);
                    tripPlaceRepository.saveAll(reordered);
                    return place;
                });
    }

    private void swapVisitOrder(TripPlace a, TripPlace b) {
        int aOrder = a.getVisitOrder();
        int bOrder = b.getVisitOrder();
        a.applyVisitOrder(bOrder);
        b.applyVisitOrder(aOrder);
        tripPlaceRepository.save(a);
        tripPlaceRepository.save(b);
    }

    /**
     * 하루 일정(Itinerary)의 TripPlace 순서를 총 이동거리가 최소가 되도록 재배열한다.
     * 영상에서 자동 추출된 일정(ItineraryEditService.optimizeRoute)과 같은 RouteOptimizer를
     * 쓰되, TripPlace는 SavedPlace와 다른 엔티티라 좌표 접근자를 넘기는 범용 버전을 쓴다.
     */
    @Transactional
    public Optional<List<TripPlace>> optimizeRoute(User user, Long tripId, int day) {
        return tripRepository.findById(tripId)
                .filter(trip -> trip.getUser().getId().equals(user.getId()))
                .flatMap(trip -> itineraryRepository.findByTripAndDay(trip, day))
                .map(itinerary -> {
                    List<TripPlace> places = tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary);
                    // 식당은 제자리에 둔다 — 거리만 보면 점심 식당이 아침 첫 순서가 됐다(#179).
                    // 좌표 없는 장소는 예전처럼 맨 뒤로 보낸다(동선을 계산할 수 없어서).
                    List<TripPlace> withCoords = places.stream()
                            .filter(p -> p.getLatitude() != null && p.getLongitude() != null).toList();
                    List<TripPlace> optimized = new ArrayList<>(RouteOptimizer.optimizeKeepingFixed(
                            withCoords, p -> TripDayRetimer.isRestaurant(p.getCategory()), TripPlace::getLatitude, TripPlace::getLongitude));
                    places.stream().filter(p -> p.getLatitude() == null || p.getLongitude() == null).forEach(optimized::add);
                    for (int i = 0; i < optimized.size(); i++) {
                        optimized.get(i).applyVisitOrder(i + 1);
                    }
                    // 순서만 바꾸면 1번 12:10, 2번 09:00처럼 시간이 거꾸로 남았다(#175).
                    TripDayRetimer.retime(optimized);
                    tripPlaceRepository.saveAll(optimized);
                    return optimized;
                });
    }

    public record ScheduleEntry(Long id, LocalTime visitStartTime, LocalTime visitEndTime) {
    }

    /**
     * 동선 다듬기 되돌리기(#414). 다듬기 전에 앱이 받아 둔 순서·시간을 그대로 돌려놓는다 — 되돌리기가 없어 결과가
     * 마음에 안 들면 하나씩 고쳐야 했다(안드로이드 사용성 테스트 2026-11-12). 그날 장소와 목록이 정확히 같을 때만
     * 적용한다(그 사이 장소가 추가·삭제됐으면 되돌릴 대상이 달라 거절).
     */
    @Transactional
    public Optional<List<TripPlace>> restoreDay(User user, Long tripId, int day, List<ScheduleEntry> entries) {
        return tripRepository.findById(tripId)
                .filter(trip -> trip.getUser().getId().equals(user.getId()))
                .flatMap(trip -> itineraryRepository.findByTripAndDay(trip, day))
                .flatMap(itinerary -> {
                    List<TripPlace> places = tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary);
                    Map<Long, TripPlace> byId = new HashMap<>();
                    places.forEach(p -> byId.put(p.getId(), p));
                    if (entries == null || entries.size() != places.size()
                            || entries.stream().map(ScheduleEntry::id).distinct().count() != places.size()
                            || !entries.stream().allMatch(e -> byId.containsKey(e.id()))) {
                        return Optional.empty();
                    }
                    List<TripPlace> restored = new ArrayList<>();
                    for (int i = 0; i < entries.size(); i++) {
                        ScheduleEntry e = entries.get(i);
                        TripPlace p = byId.get(e.id());
                        p.restoreSchedule(i + 1, e.visitStartTime(), e.visitEndTime());
                        restored.add(p);
                    }
                    tripPlaceRepository.saveAll(restored);
                    return Optional.of(restored);
                });
    }

    /**
     * 같은 영상(ProcessingJob)의 SavedPlace가 이미 다른 Trip으로 확정된 적이 있으면
     * 그 Trip을 반환한다 — confirm-trip을 두 번 누르거나(네트워크 지연 중 재시도 등)
     * 날짜를 바꿔 다시 제출했을 때 같은 영상에서 여행이 중복 생성되는 것을 막는다.
     */
    /**
     * 이 처리 작업의 영상으로 이미 만든 여행을 찾는다. 같은 영상을 다시 제출하면 처리 작업·SavedPlace가 새로
     * 생겨 SavedPlace id 기준(findExistingTripForSavedPlaces)으로는 못 찾고 여행이 중복 생성됐다(#19) —
     * 같은 사용자의 같은 영상(VideoKey) 작업들의 SavedPlace 전체로 찾는다.
     */
    public Optional<Trip> findExistingTripForVideo(User user, ProcessingJob job) {
        String key = VideoKey.of(job.getSourceUrl());
        List<SavedPlace> places = processingJobRepository.findByUserOrderByCreatedAtDescIdDesc(user).stream()
                .filter(candidate -> VideoKey.of(candidate.getSourceUrl()).equals(key))
                .flatMap(candidate -> savedPlaceRepository.findByProcessingJob(candidate).stream())
                .toList();
        return findExistingTripForSavedPlaces(places)
                .filter(trip -> trip.getUser().getId().equals(user.getId()));
    }

    /**
     * 주어진 영상 중 같은 영상(주소 모양 무관)으로 이미 여행을 만든 것의 id를 고른다(#139).
     * 초안마다 {@link #findExistingTripForVideo}를 부르면 사용자의 영상 전체를 초안 수만큼 다시 읽었다 —
     * 영상·영상 장소·여행 장소를 한 번씩만 읽어 요청당 쿼리 수를 고정한다.
     */
    public java.util.Set<Long> jobIdsWithExistingTrip(User user, java.util.Collection<ProcessingJob> jobs) {
        return jobIdsWithExistingTrip(user.getId(), jobs);
    }

    public java.util.Set<Long> jobIdsWithExistingTrip(Long userId, java.util.Collection<ProcessingJob> jobs) {
        if (jobs.isEmpty()) {
            return java.util.Set.of();
        }
        // 같은 영상을 다시 분석한 작업도 같은 영상으로 본다(VideoKey) — 여행에 들어간 장소의 영상 주소만 한 번에 읽는다(#204).
        java.util.Set<String> trippedKeys = tripPlaceRepository.findSourceUrlsInTripsOfUserId(userId).stream()
                .map(VideoKey::of)
                .collect(java.util.stream.Collectors.toSet());
        return jobs.stream()
                .filter(job -> trippedKeys.contains(VideoKey.of(job.getSourceUrl())))
                .map(ProcessingJob::getId)
                .collect(java.util.stream.Collectors.toSet());
    }

    public Optional<Trip> findExistingTripForSavedPlaces(List<SavedPlace> places) {
        List<Long> savedPlaceIds = places.stream().map(SavedPlace::getId).toList();
        if (savedPlaceIds.isEmpty()) {
            return Optional.empty();
        }
        return tripPlaceRepository.findFirstBySavedPlaceIdIn(savedPlaceIds)
                .map(tripPlace -> tripPlace.getItinerary().getTrip());
    }

    /**
     * startDate가 있으면 각 Itinerary에 실제 날짜(startDate + (day-1))를 계산해서
     * 넣는다 — 날씨 자동복구가 이 날짜를 기준으로 예보를 조회한다. startDate가 없으면
     * (날짜 모르는 여행) 지금까지처럼 date는 null로 남는다.
     */
    public Trip confirmVideoPlacesIntoTrip(User user, String title, List<SavedPlace> places, LocalDate startDate) {
        Map<Integer, List<SavedPlace>> byDay = places.stream()
                .filter(place -> place.getDayNumber() != null)
                .collect(Collectors.groupingBy(SavedPlace::getDayNumber, TreeMap::new, Collectors.toList()));

        LocalDate endDate = startDate != null && !byDay.isEmpty()
                ? startDate.plusDays(byDay.keySet().stream().mapToInt(Integer::intValue).max().orElse(1) - 1)
                : null;
        Trip trip = tripRepository.save(new Trip(user, title, startDate, endDate));

        for (Map.Entry<Integer, List<SavedPlace>> entry : byDay.entrySet()) {
            LocalDate itineraryDate = startDate != null ? startDate.plusDays(entry.getKey() - 1) : null;
            Itinerary itinerary = itineraryRepository.save(new Itinerary(trip, entry.getKey(), itineraryDate));

            List<SavedPlace> dayPlaces = entry.getValue().stream()
                    .sorted(Comparator.comparing(
                            SavedPlace::getOrderInDay, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();

            for (SavedPlace place : dayPlaces) {
                TripPlace tripPlace = new TripPlace(
                        itinerary, place.getPlaceName(), place.getRegion(), place.getCategory(),
                        place.getLatitude(), place.getLongitude(), place.getPhone(), place.getAddress(),
                        place.getOrderInDay() != null ? place.getOrderInDay() : 0,
                        PlaceSource.VIDEO, place.getId());
                tripPlace.applyDetails(null, null, null, place.videoNotesAsMemo()); // 영상에서 말한 내용(#134)
                tripPlaceRepository.save(tripPlace);
            }
        }

        return trip;
    }
}
