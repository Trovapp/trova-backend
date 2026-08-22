package com.trova.backend.service;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.repository.SavedPlaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ItineraryEditService {

    private final SavedPlaceRepository savedPlaceRepository;

    public ItineraryEditService(SavedPlaceRepository savedPlaceRepository) {
        this.savedPlaceRepository = savedPlaceRepository;
    }

    @Transactional
    public SavedPlace moveToDay(SavedPlace place, Integer dayNumber) {
        ProcessingJob job = place.getProcessingJob();
        List<SavedPlace> siblings =
                savedPlaceRepository.findByProcessingJobAndDayNumberOrderByOrderInDayAsc(job, dayNumber);
        int nextOrder = siblings.isEmpty() ? 1 : siblings.get(siblings.size() - 1).getOrderInDay() + 1;
        place.assignToDay(dayNumber, nextOrder);
        return place;
    }

    @Transactional
    public SavedPlace reorder(SavedPlace place, String direction) {
        if (place.getDayNumber() == null) {
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
            return place; // 경계값(맨 위/맨 아래) — no-op
        }

        SavedPlace neighbor = siblings.get(swapIndex);
        int placeOrder = place.getOrderInDay();
        int neighborOrder = neighbor.getOrderInDay();
        place.assignToDay(place.getDayNumber(), neighborOrder);
        neighbor.assignToDay(neighbor.getDayNumber(), placeOrder);
        return place;
    }
}
