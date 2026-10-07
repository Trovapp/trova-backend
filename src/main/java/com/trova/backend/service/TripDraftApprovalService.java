package com.trova.backend.service;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.planner.DraftGenerator;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 일정 초안 승인(#106 4일차): 사용자가 승인해야 초안이 여행(Trip)이 된다 — 에이전트는 스스로 저장하지 않는다(설계 B).
 * 초안의 일차·순서·대략 방문 시각을 그대로 옮기고, 영상 장소와 연결(savedPlaceId)해 기존 여행 화면·재구성을 그대로 쓴다.
 * 승인을 두 번 눌러도 여행이 하나만 생기게 초안 행을 잠그고, 이미 승인된 초안이면 만든 여행을 그대로 돌려준다.
 */
@Service
public class TripDraftApprovalService {

    static final int MAX_TITLE = 60;

    public enum Outcome { OK, NOT_FOUND, NOT_READY }

    public record Approval(Outcome outcome, Long tripId) {
    }

    private final TripDraftRepository tripDraftRepository;
    private final TripRepository tripRepository;
    private final ItineraryRepository itineraryRepository;
    private final TripPlaceRepository tripPlaceRepository;
    private final SavedPlaceRepository savedPlaceRepository;

    public TripDraftApprovalService(TripDraftRepository tripDraftRepository, TripRepository tripRepository,
                                    ItineraryRepository itineraryRepository, TripPlaceRepository tripPlaceRepository,
                                    SavedPlaceRepository savedPlaceRepository) {
        this.tripDraftRepository = tripDraftRepository;
        this.tripRepository = tripRepository;
        this.itineraryRepository = itineraryRepository;
        this.tripPlaceRepository = tripPlaceRepository;
        this.savedPlaceRepository = savedPlaceRepository;
    }

    @Transactional
    public Approval approve(User user, Long draftId, String title) {
        TripDraft draft = tripDraftRepository.findByIdForUpdate(draftId)
                .filter(d -> d.getUser().getId().equals(user.getId()))
                .orElse(null);
        if (draft == null) {
            return new Approval(Outcome.NOT_FOUND, null);
        }
        if (draft.getStatus() == TripDraftStatus.APPROVED) {
            return new Approval(Outcome.OK, draft.getTripId());
        }
        if (draft.getStatus() != TripDraftStatus.READY || draft.getDraftJson() == null) {
            return new Approval(Outcome.NOT_READY, null);
        }
        DraftGenerator.Draft plan = DraftGenerator.fromJson(draft.getDraftJson());
        Map<Long, SavedPlace> saved = savedPlaceRepository.findAllById(plan.days().stream()
                        .flatMap(d -> d.items().stream()).map(DraftGenerator.Item::placeId).toList()).stream()
                .filter(p -> p.getUser().getId().equals(user.getId()))
                .collect(Collectors.toMap(SavedPlace::getId, Function.identity()));

        LocalDate start = draft.getStartDate();
        int days = plan.days().size();
        String name = title == null || title.isBlank() ? defaultTitle(plan, saved) : title.trim();
        if (name.length() > MAX_TITLE) {
            name = name.substring(0, MAX_TITLE);
        }
        Trip trip = tripRepository.save(new Trip(user, name, start, start == null ? null : start.plusDays(days - 1)));
        for (DraftGenerator.Day day : plan.days()) {
            Itinerary itinerary = itineraryRepository.save(
                    new Itinerary(trip, day.day(), start == null ? null : start.plusDays(day.day() - 1)));
            int order = 1;
            for (DraftGenerator.Item item : day.items()) {
                // 초안을 만든 뒤 영상 장소가 지워졌어도 초안에 담긴 이름·좌표로 일정은 만든다(연결만 끊는다).
                SavedPlace p = saved.get(item.placeId());
                TripPlace place = new TripPlace(itinerary, item.name(), p == null ? null : p.getRegion(), item.category(),
                        item.latitude(), item.longitude(), p == null ? null : p.getPhone(), p == null ? null : p.getAddress(),
                        order++, PlaceSource.VIDEO, p == null ? null : p.getId());
                // 영상에서 말한 내용을 장소 메모로 옮긴다(#134) — 사용자가 고치거나 지울 수 있게 일반 메모 칸에.
                place.applyDetails(item.start(), item.end(), null, p == null ? null : p.videoNotesAsMemo());
                if (p != null && p.getGooglePlaceId() != null) {
                    place.applyGooglePlaceId(p.getGooglePlaceId());
                }
                tripPlaceRepository.save(place);
            }
        }
        draft.markApproved(trip.getId());
        tripDraftRepository.save(draft);
        return new Approval(Outcome.OK, trip.getId());
    }

    /**
     * "제주 2박 3일"처럼 — 날마다 가장 많은 지역(지역 이름의 첫 단어)을 일차 순으로 모아 "김해·강릉 1박 2일"처럼 붙인다.
     * 지역이 3곳 이상이면 "김해·강릉 외"로 줄이고, 지역을 모르면 "새 여행".
     */
    static String defaultTitle(DraftGenerator.Draft plan, Map<Long, SavedPlace> places) {
        List<String> regions = new ArrayList<>();
        for (DraftGenerator.Day day : plan.days()) {
            day.items().stream()
                    .map(it -> places.get(it.placeId()))
                    .filter(Objects::nonNull)
                    .map(SavedPlace::getRegion).filter(Objects::nonNull).map(String::trim).filter(r -> !r.isEmpty())
                    .map(r -> r.split("\\s+")[0])
                    .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()))
                    .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                    .filter(r -> !regions.contains(r))
                    .ifPresent(regions::add);
        }
        String region = regions.isEmpty() ? "새 여행"
                : String.join("·", regions.subList(0, Math.min(2, regions.size()))) + (regions.size() > 2 ? " 외" : "");
        int days = plan.days().size();
        return region + " " + (days <= 1 ? "당일치기" : (days - 1) + "박 " + days + "일");
    }
}
