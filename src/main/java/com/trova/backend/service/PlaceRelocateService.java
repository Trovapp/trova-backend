package com.trova.backend.service;

import com.trova.backend.entity.Place;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 영상 속 장소의 위치를 사용자가 고른 장소로 바로잡는다(#217). 고르는 건 앱이 기존 장소 검색(GET /api/places/search)으로 하고,
 * 그 결과는 이미 places 테이블에 저장돼 있어 여기서는 외부 API를 부르지 않고 그 값을 옮겨 적기만 한다.
 */
@Service
public class PlaceRelocateService {

    private final SavedPlaceRepository savedPlaceRepository;
    private final PlaceRepository placeRepository;

    public PlaceRelocateService(SavedPlaceRepository savedPlaceRepository, PlaceRepository placeRepository) {
        this.savedPlaceRepository = savedPlaceRepository;
        this.placeRepository = placeRepository;
    }

    /** 내 장소가 아니거나, 고른 장소가 없거나 좌표가 없으면 비어 있다. */
    @Transactional
    public Optional<SavedPlace> relocate(Long savedPlaceId, User user, Long placeId) {
        Optional<Place> picked = placeRepository.findById(placeId)
                .filter(p -> p.getLatitude() != null && p.getLongitude() != null);
        if (picked.isEmpty()) {
            return Optional.empty();
        }
        Place place = picked.get();
        return savedPlaceRepository.findByIdAndUser(savedPlaceId, user).map(saved -> {
            saved.relocateTo(place.getName(), place.getLatitude(), place.getLongitude(), place.getAddress(),
                    place.getPhone(), place.getGooglePlaceId());
            return saved;
        });
    }
}
