package com.trova.backend.service;

import com.trova.backend.entity.Place;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.User;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripDraftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 영상 속 장소의 위치를 사용자가 고른 장소로 바로잡는다(#217). 고르는 건 앱이 기존 장소 검색(GET /api/places/search)으로 하고,
 * 그 결과는 이미 places 테이블에 저장돼 있어 여기서는 외부 API를 부르지 않고 그 값을 옮겨 적기만 한다.
 * 이 영상으로 이미 만든 홈 초안(자동 초안과 다시 짠 초안)은 바로잡기 전 위치("위치를 못 찾아 뺀 곳")를 그대로 보여 줬다(안드로이드 사용성 테스트
 * 2026-11-12, #414) — 초안에 "위치가 바뀌었다"고만 표시하고, 다시 짤지는 사용자가 앱에서 고른다(Gemini를 여기서 부르지 않음,
 * ADR 0006. 자동으로 다시 만들면 바로잡을 때마다 하루 한도를 썼다 — 리뷰 S1, 2026-11-12 사용자 결정).
 */
@Service
public class PlaceRelocateService {

    private static final Logger log = LoggerFactory.getLogger(PlaceRelocateService.class);

    private final SavedPlaceRepository savedPlaceRepository;
    private final PlaceRepository placeRepository;
    private final TripDraftRepository tripDraftRepository;

    public PlaceRelocateService(SavedPlaceRepository savedPlaceRepository, PlaceRepository placeRepository,
                                TripDraftRepository tripDraftRepository) {
        this.savedPlaceRepository = savedPlaceRepository;
        this.placeRepository = placeRepository;
        this.tripDraftRepository = tripDraftRepository;
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
            markListedDrafts(user, saved);
            return saved;
        });
    }

    private void markListedDrafts(User user, SavedPlace saved) {
        if (saved.getProcessingJob() == null) {
            return;
        }
        Long jobId = saved.getProcessingJob().getId();
        List<Long> marked = tripDraftRepository.findListedForPlacesChangeByUser(user).stream()
                .filter(d -> d.getJobIds().contains(jobId))
                .filter(TripDraft::markPlacesChanged)
                .map(TripDraft::getId)
                .toList();
        if (!marked.isEmpty()) {
            log.info("SavedPlace {} 위치 바로잡기 — 초안 {} 다시 짜기 권함", saved.getId(), marked);
        }
    }
}
