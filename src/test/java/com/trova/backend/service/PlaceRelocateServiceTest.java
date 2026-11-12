package com.trova.backend.service;

import com.trova.backend.entity.Place;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.entity.User;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripDraftRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaceRelocateServiceTest {

    private final SavedPlaceRepository savedPlaceRepository = mock(SavedPlaceRepository.class);
    private final PlaceRepository placeRepository = mock(PlaceRepository.class);
    private final TripDraftRepository tripDraftRepository = mock(TripDraftRepository.class);
    private final PlaceRelocateService service =
            new PlaceRelocateService(savedPlaceRepository, placeRepository, tripDraftRepository);

    private final User user = new User("kakao", "1", "테스터", null);

    @Test
    void 위치를_바로잡으면_그_영상의_자동_초안에만_바뀜_표시를_하고_다시_만들지_않는다() {
        ProcessingJob job = mock(ProcessingJob.class);
        when(job.getId()).thenReturn(7L);
        when(job.getSourcePlatform()).thenReturn(SourcePlatform.YOUTUBE);
        SavedPlace saved = new SavedPlace(job, user, "부산역", "부산", "attraction", null, null);
        Place picked = mock(Place.class);
        when(picked.getName()).thenReturn("부산역");
        when(picked.getLatitude()).thenReturn(35.115);
        when(picked.getLongitude()).thenReturn(129.041);
        TripDraft same = TripDraft.auto(user, List.of(7L), "당일치기");
        same.markReady(null, "{\"days\":[]}");
        TripDraft other = TripDraft.auto(user, List.of(8L), "당일치기");
        when(placeRepository.findById(3L)).thenReturn(Optional.of(picked));
        when(savedPlaceRepository.findByIdAndUser(1L, user)).thenReturn(Optional.of(saved));
        when(tripDraftRepository.findByUserAndAutoCreatedTrue(user)).thenReturn(List.of(same, other));

        assertThat(service.relocate(1L, user, 3L)).isPresent();

        assertThat(same.getPlacesChangedAt()).isNotNull();
        // Gemini를 다시 부르지 않는다(ADR 0006) — 초안 내용과 상태는 그대로다.
        assertThat(same.getStatus()).isEqualTo(TripDraftStatus.READY);
        assertThat(same.getDraftJson()).isEqualTo("{\"days\":[]}");
        assertThat(other.getPlacesChangedAt()).isNull();
    }

    @Test
    void 승인했거나_닫은_초안에는_표시하지_않는다() {
        TripDraft dismissed = TripDraft.auto(user, List.of(7L), "당일치기");
        dismissed.dismiss();
        TripDraft approved = TripDraft.auto(user, List.of(7L), "당일치기");
        approved.markApproved(10L);

        assertThat(dismissed.markPlacesChanged()).isFalse();
        assertThat(approved.markPlacesChanged()).isFalse();
        assertThat(approved.getPlacesChangedAt()).isNull();
    }
}
