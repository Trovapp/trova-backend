package com.trova.backend.service;

import com.trova.backend.entity.Place;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.User;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.queue.DraftJobQueue;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripDraftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

/**
 * 영상 속 장소의 위치를 사용자가 고른 장소로 바로잡는다(#217). 고르는 건 앱이 기존 장소 검색(GET /api/places/search)으로 하고,
 * 그 결과는 이미 places 테이블에 저장돼 있어 여기서는 외부 API를 부르지 않고 그 값을 옮겨 적기만 한다.
 * 이 영상으로 이미 만든 자동 초안은 바로잡기 전 위치("위치를 못 찾아 뺀 곳")를 그대로 보여 줬다(안드로이드 사용성 테스트
 * 2026-11-12, #414) — 같은 초안을 커밋 뒤 다시 만들게 대기열에 넣는다(Gemini는 초안 생성분만, 요청 해석은 다시 안 함).
 */
@Service
public class PlaceRelocateService {

    private static final Logger log = LoggerFactory.getLogger(PlaceRelocateService.class);

    private final SavedPlaceRepository savedPlaceRepository;
    private final PlaceRepository placeRepository;
    private final TripDraftRepository tripDraftRepository;
    private final DraftJobQueue draftJobQueue;

    public PlaceRelocateService(SavedPlaceRepository savedPlaceRepository, PlaceRepository placeRepository,
                                TripDraftRepository tripDraftRepository, DraftJobQueue draftJobQueue) {
        this.savedPlaceRepository = savedPlaceRepository;
        this.placeRepository = placeRepository;
        this.tripDraftRepository = tripDraftRepository;
        this.draftJobQueue = draftJobQueue;
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
            requeueAutoDrafts(user, saved);
            return saved;
        });
    }

    private void requeueAutoDrafts(User user, SavedPlace saved) {
        if (saved.getProcessingJob() == null) {
            return;
        }
        Long jobId = saved.getProcessingJob().getId();
        List<Long> requeued = tripDraftRepository.findByUserAndAutoCreatedTrue(user).stream()
                .filter(d -> d.getJobIds().contains(jobId))
                .filter(TripDraft::requeueForPlaceChange)
                .map(TripDraft::getId)
                .toList();
        if (requeued.isEmpty()) {
            return;
        }
        Runnable enqueue = () -> requeued.forEach(id -> {
            try {
                draftJobQueue.enqueue(id);
            } catch (RuntimeException e) {
                // 넣지 못해도 PENDING으로 남아 멈춘 작업 다시 보내기(ADR 0043)가 한 번 더 보낸다.
                log.warn("TripDraft {} 위치 바로잡기 뒤 다시 만들기 대기열 실패", id, e);
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue.run();
                }
            });
        } else {
            enqueue.run();
        }
    }
}
