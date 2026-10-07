package com.trova.backend.service;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 분석이 끝나면 일정 초안을 자동으로 만든다(#136). 실제 TripPlannerService는 Gemini를 부르므로
 * @MockitoBean으로 바꿔 process가 불렸는지만 확인한다.
 */
@SpringBootTest
class AutoDraftServiceIntegrationTest {

    private static final String OWNER = "auto-draft-1";

    @Autowired private AutoDraftService autoDraftService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProcessingJobRepository processingJobRepository;
    @Autowired private SavedPlaceRepository savedPlaceRepository;
    @Autowired private TripDraftRepository tripDraftRepository;
    @Autowired private TripRepository tripRepository;
    @Autowired private ItineraryRepository itineraryRepository;
    @Autowired private TripPlaceRepository tripPlaceRepository;

    @MockitoBean
    private TripPlannerService tripPlannerService;

    @AfterEach
    void tearDown() {
        userRepository.findByProviderAndProviderUserId("google", OWNER).ifPresent(user -> {
            tripDraftRepository.deleteAll(tripDraftRepository.findAll().stream()
                    .filter(d -> d.getUser().getId().equals(user.getId())).toList());
            tripRepository.findByUserOrderByCreatedAtDesc(user).forEach(trip -> {
                itineraryRepository.findByTripOrderByDay(trip).forEach(it ->
                        tripPlaceRepository.deleteAll(tripPlaceRepository.findByItineraryOrderByVisitOrder(it)));
                itineraryRepository.deleteAll(itineraryRepository.findByTripOrderByDay(trip));
            });
            tripRepository.deleteAll(tripRepository.findByUserOrderByCreatedAtDesc(user));
            savedPlaceRepository.deleteAll(savedPlaceRepository.findByUserOrderByCreatedAtDescIdDesc(user));
            processingJobRepository.deleteAll(processingJobRepository.findByUserOrderByCreatedAtDescIdDesc(user));
            userRepository.delete(user);
        });
    }

    private User user() {
        return userRepository.save(new User("google", OWNER, "자동초안", null));
    }

    private ProcessingJob doneJob(User user, String sourceUrl, String title) {
        ProcessingJob job = new ProcessingJob(user, sourceUrl, SourcePlatform.YOUTUBE);
        job.setTitle(title);
        job.markDone();
        return processingJobRepository.save(job);
    }

    private List<TripDraft> autoDrafts(User user) {
        return tripDraftRepository.findByUserAndAutoCreatedTrue(user);
    }

    @Test
    void 분석이_끝나면_자동_초안을_만든다() {
        User owner = user();
        ProcessingJob job = doneJob(owner, "https://youtu.be/auto-" + System.nanoTime(), "제주 여행");
        savedPlaceRepository.save(new SavedPlace(job, owner, "해변", "제주", "attraction", 33.0, 126.0, 1, 1));
        savedPlaceRepository.save(new SavedPlace(job, owner, "맛집", "제주", "restaurant", 33.1, 126.1, 2, 1));

        autoDraftService.startFor(job.getId());

        List<TripDraft> drafts = autoDrafts(owner);
        assertThat(drafts).hasSize(1);
        TripDraft draft = drafts.get(0);
        assertThat(draft.isAutoCreated()).isTrue();
        assertThat(draft.getMessage()).isEqualTo("1박 2일");
        assertThat(draft.getJobIds()).containsExactly(job.getId());
        verify(tripPlannerService).process(draft.getId());
    }

    @Test
    void 주소_모양이_달라도_같은_영상이면_다시_만들지_않는다() {
        User owner = user();
        String videoId = "abc" + System.nanoTime();
        ProcessingJob first = doneJob(owner, "https://youtube.com/shorts/" + videoId, "제주 여행");
        savedPlaceRepository.save(new SavedPlace(first, owner, "해변", "제주", "attraction", 33.0, 126.0, 1, 1));

        autoDraftService.startFor(first.getId());

        ProcessingJob second = doneJob(owner, "https://youtu.be/" + videoId + "?si=x", "제주 여행");
        savedPlaceRepository.save(new SavedPlace(second, owner, "해변", "제주", "attraction", 33.0, 126.0, 1, 1));

        autoDraftService.startFor(second.getId());

        assertThat(autoDrafts(owner)).hasSize(1);
    }

    @Test
    void 장소가_없으면_만들지_않는다() {
        User owner = user();
        ProcessingJob job = doneJob(owner, "https://youtu.be/no-places-" + System.nanoTime(), "제주 여행");

        autoDraftService.startFor(job.getId());

        assertThat(autoDrafts(owner)).isEmpty();
        verify(tripPlannerService, never()).process(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void 이미_만든_여행이_있으면_만들지_않는다() {
        User owner = user();
        ProcessingJob job = doneJob(owner, "https://youtu.be/already-trip-" + System.nanoTime(), "제주 여행");
        SavedPlace place = savedPlaceRepository.save(new SavedPlace(job, owner, "해변", "제주", "attraction", 33.0, 126.0, 1, 1));

        Trip trip = tripRepository.save(new Trip(owner, "제주 여행", null, null));
        Itinerary itinerary = itineraryRepository.save(new Itinerary(trip, 1, null));
        tripPlaceRepository.save(new TripPlace(itinerary, "해변", "제주", "attraction", 33.0, 126.0, null, null,
                1, PlaceSource.VIDEO, place.getId()));

        autoDraftService.startFor(job.getId());

        assertThat(autoDrafts(owner)).isEmpty();
        verify(tripPlannerService, never()).process(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void 설정이_꺼져_있으면_만들지_않는다() {
        ReflectionTestUtils.setField(autoDraftService, "enabled", false);
        try {
            User owner = user();
            ProcessingJob job = doneJob(owner, "https://youtu.be/disabled-" + System.nanoTime(), "제주 여행");
            savedPlaceRepository.save(new SavedPlace(job, owner, "해변", "제주", "attraction", 33.0, 126.0, 1, 1));

            autoDraftService.startFor(job.getId());

            assertThat(autoDrafts(owner)).isEmpty();
            verify(tripPlannerService, never()).process(org.mockito.ArgumentMatchers.anyLong());
        } finally {
            ReflectionTestUtils.setField(autoDraftService, "enabled", true);
        }
    }
}
