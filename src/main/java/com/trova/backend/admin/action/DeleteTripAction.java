package com.trova.backend.admin.action;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Trip;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.service.TripService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 사용자 요청 시 그 사용자의 여행 하나를 연결 데이터까지 삭제 — 앱의 여행 삭제와 같은 경로(TripService.deleteTrip). */
@Component
public class DeleteTripAction implements AdminActionHandler {

    private final TripRepository tripRepository;
    private final ItineraryRepository itineraryRepository;
    private final TripPlaceRepository tripPlaceRepository;
    private final TripService tripService;

    public DeleteTripAction(TripRepository tripRepository, ItineraryRepository itineraryRepository,
                            TripPlaceRepository tripPlaceRepository, TripService tripService) {
        this.tripRepository = tripRepository;
        this.itineraryRepository = itineraryRepository;
        this.tripPlaceRepository = tripPlaceRepository;
        this.tripService = tripService;
    }

    @Override
    public AdminAction action() {
        return AdminAction.DELETE_TRIP;
    }

    @Override
    @Transactional(readOnly = true)
    public ActionPreview preview(Long targetId, Long adminUserId) {
        Trip trip = tripRepository.findById(targetId).orElseThrow(AdminActionException::notFound);
        return new ActionPreview(action(), trip.getId(), trip.getUser().getId(), impact(trip), trip.getTitle());
    }

    @Override
    @Transactional
    public String execute(Long targetId, Long adminUserId) {
        Trip trip = tripRepository.findById(targetId).orElseThrow(AdminActionException::notFound);
        String impact = impact(trip);
        tripService.deleteTrip(trip.getUser(), trip.getId());
        return impact;
    }

    private String impact(Trip trip) {
        List<Itinerary> days = itineraryRepository.findByTripOrderByDay(trip);
        int places = days.stream().mapToInt(d -> tripPlaceRepository.findByItineraryOrderByVisitOrder(d).size()).sum();
        return "여행 1·일차 " + days.size() + "·장소 " + places;
    }
}
