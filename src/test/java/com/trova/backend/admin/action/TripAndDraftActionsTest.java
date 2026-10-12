package com.trova.backend.admin.action;

import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.User;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.service.TripService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class TripAndDraftActionsTest {

    @Autowired
    private DeleteTripAction deleteTrip;

    @Autowired
    private HideDraftAction hideDraft;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TripService tripService;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private TripDraftRepository tripDraftRepository;

    @Test
    void 여행_삭제_미리보기는_이름을_확인_글자로_영향_범위를_센다() {
        User owner = userRepository.save(new User("google", "trip-owner", "주인", null));
        Trip trip = tripService.createTrip(owner, "제주 2박 3일", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3));

        ActionPreview preview = deleteTrip.preview(trip.getId(), 999L);

        assertThat(preview.confirmText()).isEqualTo("제주 2박 3일");
        assertThat(preview.targetUserId()).isEqualTo(owner.getId());
        assertThat(preview.impact()).isEqualTo("여행 1·일차 3·장소 0");
    }

    @Test
    void 여행_삭제_실행은_여행과_딸린_데이터를_지운다() {
        User owner = userRepository.save(new User("google", "trip-owner2", "주인", null));
        Trip trip = tripService.createTrip(owner, "부산", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1));

        String result = deleteTrip.execute(trip.getId(), 999L);

        assertThat(tripRepository.findById(trip.getId())).isEmpty();
        assertThat(result).contains("여행 1");
    }

    @Test
    void 미리보기_뒤_사용자가_먼저_지운_여행은_404() {
        User owner = userRepository.save(new User("google", "trip-owner3", "주인", null));
        Trip trip = tripService.createTrip(owner, "강릉", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1));
        deleteTrip.preview(trip.getId(), 999L);
        tripService.deleteTrip(owner, trip.getId());

        assertThatThrownBy(() -> deleteTrip.execute(trip.getId(), 999L))
                .isInstanceOf(AdminActionException.class)
                .satisfies(e -> assertThat(((AdminActionException) e).status().value()).isEqualTo(404));
    }

    @Test
    void 자동_초안_숨기기_실행은_숨긴_시각을_채우고_다시_하면_409() {
        User owner = userRepository.save(new User("google", "draft-owner", "주인", null));
        TripDraft draft = tripDraftRepository.save(TripDraft.auto(owner, List.of(1L), "제주 영상 공유"));

        ActionPreview preview = hideDraft.preview(draft.getId(), 999L);
        assertThat(preview.confirmText()).isNull();
        assertThat(preview.targetUserId()).isEqualTo(owner.getId());
        assertThat(preview.impact()).startsWith("자동 초안 #" + draft.getId());

        String result = hideDraft.execute(draft.getId(), 999L);

        assertThat(result).isEqualTo("초안 #" + draft.getId() + " 숨김");
        assertThat(tripDraftRepository.findById(draft.getId()).orElseThrow().getDismissedAt()).isNotNull();
        assertThatThrownBy(() -> hideDraft.execute(draft.getId(), 999L))
                .isInstanceOf(AdminActionException.class)
                .satisfies(e -> assertThat(((AdminActionException) e).status().value()).isEqualTo(409));
    }

    @Test
    void 자동이_아닌_초안_미리보기는_409() {
        User owner = userRepository.save(new User("google", "draft-owner2", "주인", null));
        TripDraft draft = tripDraftRepository.save(new TripDraft(owner, List.of(1L), "부산 1박 2일"));

        assertThatThrownBy(() -> hideDraft.preview(draft.getId(), 999L))
                .isInstanceOf(AdminActionException.class)
                .satisfies(e -> assertThat(((AdminActionException) e).status().value()).isEqualTo(409));
    }
}
