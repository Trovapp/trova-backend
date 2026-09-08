package com.trova.backend.service;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class ItineraryEditService {

    private final SavedPlaceRepository savedPlaceRepository;
    private final ProcessingJobRepository processingJobRepository;

    public ItineraryEditService(
            SavedPlaceRepository savedPlaceRepository, ProcessingJobRepository processingJobRepository
    ) {
        this.savedPlaceRepository = savedPlaceRepository;
        this.processingJobRepository = processingJobRepository;
    }

    @Transactional
    public Optional<SavedPlace> moveToDay(Long placeId, User user, Integer dayNumber) {
        return savedPlaceRepository.findByIdAndUser(placeId, user).map(place -> {
            ProcessingJob job = place.getProcessingJob();
            List<SavedPlace> siblings =
                    savedPlaceRepository.findByProcessingJobAndDayNumberOrderByOrderInDayAsc(job, dayNumber);
            int nextOrder = siblings.stream()
                    .map(SavedPlace::getOrderInDay)
                    .filter(Objects::nonNull)
                    .max(Integer::compareTo)
                    .orElse(0) + 1;
            place.assignToDay(dayNumber, nextOrder);
            return place;
        });
    }

    @Transactional
    public Optional<SavedPlace> reorder(Long placeId, User user, String direction) {
        return savedPlaceRepository.findByIdAndUser(placeId, user).map(place -> {
            if (place.getDayNumber() == null || place.getOrderInDay() == null) {
                return place;
            }
            List<SavedPlace> siblings = savedPlaceRepository
                    .findByProcessingJobAndDayNumberOrderByOrderInDayAsc(place.getProcessingJob(), place.getDayNumber());

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

            SavedPlace neighbor = siblings.get(swapIndex);
            int placeOrder = place.getOrderInDay();
            int neighborOrder = neighbor.getOrderInDay();
            place.assignToDay(place.getDayNumber(), neighborOrder);
            neighbor.assignToDay(neighbor.getDayNumber(), placeOrder);
            return place;
        });
    }

    /**
     * 하루 일정의 장소 순서를 총 이동거리가 최소가 되도록 재배열한다. 좌표 없는 장소는
     * RouteOptimizer가 알아서 끝으로 밀어둔 채로 온다 — 여기서는 그 순서 그대로
     * orderInDay 1..n을 다시 매긴다.
     */
    @Transactional
    public Optional<List<SavedPlace>> optimizeRoute(Long jobId, User user, int dayNumber) {
        return processingJobRepository.findById(jobId)
                .filter(job -> job.getUser().getId().equals(user.getId()))
                .map(job -> {
                    List<SavedPlace> places = savedPlaceRepository
                            .findByProcessingJobAndDayNumberOrderByOrderInDayAsc(job, dayNumber);
                    List<SavedPlace> optimized = RouteOptimizer.optimize(places);
                    for (int i = 0; i < optimized.size(); i++) {
                        optimized.get(i).assignToDay(dayNumber, i + 1);
                    }
                    return optimized;
                });
    }
}
