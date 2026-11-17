package com.trova.backend.service;

import com.trova.backend.entity.Place;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.entity.User;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:place-relocate-450;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
@Import(PlaceRelocateService.class)
class PlaceRelocateServiceIntegrationTest {

    @Autowired
    private PlaceRelocateService service;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private SavedPlaceRepository savedPlaceRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private TripDraftRepository tripDraftRepository;

    @Autowired
    private EntityManager entityManager;

    private User user;
    private ProcessingJob job;
    private SavedPlace saved;
    private Place picked;

    @BeforeEach
    void 준비() {
        user = userRepository.save(new User("kakao", "relocate-450", "테스터", null));
        job = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/relocate-450", SourcePlatform.YOUTUBE));
        saved = savedPlaceRepository.save(
                new SavedPlace(job, user, "부산역", "부산", "attraction", null, null));
        picked = placeRepository.save(new Place("relocate-450", "부산역", "train_station", null,
                null, null, 35.115, 129.041, "부산"));
    }

    @ParameterizedTest
    @EnumSource(value = TripDraftStatus.class, names = {"PENDING", "PROCESSING", "NEEDS_INPUT", "READY"})
    void 자동_초안과_다시_짠_초안은_만드는_중에도_위치_변경을_표시한다(TripDraftStatus status) {
        TripDraft auto = listedDraft(true);
        TripDraft replacement = listedDraft(false);
        // 생성·질문·완료 어느 단계에서도 표시를 놓치지 않아야 한다.
        for (TripDraft draft : List.of(auto, replacement)) {
            switch (status) {
                case PROCESSING -> draft.markProcessing();
                case NEEDS_INPUT -> draft.markNeedsInput("지역을 고르세요", "{}");
                case READY -> draft.markReady("{}", "{\"days\":[]}");
                default -> { }
            }
        }
        assertThat(auto.isAutoCreated()).isTrue();
        assertThat(replacement.isAutoCreated()).isFalse();
        assertThat(replacement.getReplacesDraftId()).isNotNull();
        assertThat(tripDraftRepository.findListedForPlacesChangeByUser(user))
                .containsExactlyInAnyOrder(auto, replacement);

        relocateAndReload();

        for (TripDraft draft : List.of(auto, replacement)) {
            TripDraft reloaded = tripDraftRepository.findById(draft.getId()).orElseThrow();
            assertThat(reloaded.getPlacesChangedAt()).isNotNull();
            assertThat(reloaded.getStatus()).isEqualTo(status);
            assertThat(reloaded.getDraftJson()).isEqualTo(draft.getDraftJson());
            assertThat(reloaded.getGeminiCalls()).isNull();
        }
    }

    @Test
    void 직접_짠_초안은_같은_영상이어도_위치_변경을_표시하지_않는다() {
        TripDraft manual = tripDraftRepository.save(new TripDraft(user, List.of(job.getId()), "당일치기"));
        assertThat(manual.isAutoCreated()).isFalse();
        assertThat(manual.getReplacesDraftId()).isNull();

        assertExcludedAfterRelocation(manual);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 승인한_초안은_위치_변경_조회와_표시에서_제외한다(boolean autoCreated) {
        TripDraft draft = listedDraft(autoCreated);
        draft.markApproved(10L);

        assertExcludedAfterRelocation(draft);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 닫은_초안은_위치_변경_조회와_표시에서_제외한다(boolean autoCreated) {
        TripDraft draft = listedDraft(autoCreated);
        draft.dismiss();

        assertExcludedAfterRelocation(draft);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 실패한_초안은_위치_변경_조회와_표시에서_제외한다(boolean autoCreated) {
        TripDraft draft = listedDraft(autoCreated);
        draft.markFailed("생성 실패");

        assertExcludedAfterRelocation(draft);
    }

    @Test
    void 다른_사용자의_초안은_같은_영상이어도_위치_변경을_표시하지_않는다() {
        User other = userRepository.save(new User("kakao", "relocate-450-other", "다른 사용자", null));
        TripDraft auto = tripDraftRepository.save(TripDraft.auto(other, List.of(job.getId()), "당일치기"));
        TripDraft replacement = new TripDraft(other, List.of(job.getId()), "당일치기");
        replacement.replaces(1L);
        tripDraftRepository.save(replacement);

        assertThat(tripDraftRepository.findListedForPlacesChangeByUser(user)).isEmpty();
        relocateAndReload();

        assertThat(tripDraftRepository.findById(auto.getId()).orElseThrow().getPlacesChangedAt()).isNull();
        assertThat(tripDraftRepository.findById(replacement.getId()).orElseThrow().getPlacesChangedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 고친_장소의_영상이_없는_초안은_위치_변경을_표시하지_않는다(boolean autoCreated) {
        ProcessingJob otherJob = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/relocate-450-other", SourcePlatform.YOUTUBE));
        TripDraft draft = autoCreated
                ? TripDraft.auto(user, List.of(otherJob.getId()), "당일치기")
                : new TripDraft(user, List.of(otherJob.getId()), "당일치기");
        if (!autoCreated) {
            draft.replaces(1L);
        }
        tripDraftRepository.save(draft);
        assertThat(tripDraftRepository.findListedForPlacesChangeByUser(user)).containsExactly(draft);

        relocateAndReload();

        assertThat(tripDraftRepository.findById(draft.getId()).orElseThrow().getPlacesChangedAt()).isNull();
    }

    private TripDraft listedDraft(boolean autoCreated) {
        TripDraft draft = autoCreated
                ? TripDraft.auto(user, List.of(job.getId()), "당일치기")
                : new TripDraft(user, List.of(job.getId()), "당일치기");
        if (!autoCreated) {
            draft.replaces(1L);
        }
        return tripDraftRepository.save(draft);
    }

    private void assertExcludedAfterRelocation(TripDraft draft) {
        // 엔티티의 승인·닫힘 방어가 쿼리 조건 누락을 가리지 않도록 조회 결과도 직접 단언한다.
        assertThat(tripDraftRepository.findListedForPlacesChangeByUser(user)).isEmpty();

        relocateAndReload();

        assertThat(tripDraftRepository.findById(draft.getId()).orElseThrow().getPlacesChangedAt()).isNull();
    }

    private void relocateAndReload() {
        assertThat(service.relocate(saved.getId(), user, picked.getId())).isPresent();
        entityManager.flush();
        entityManager.clear();
        SavedPlace reloaded = savedPlaceRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getLatitude()).isEqualTo(35.115);
        assertThat(reloaded.getLongitude()).isEqualTo(129.041);
    }
}
